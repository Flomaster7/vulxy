package me.cortex.voxy.client.core;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Native Vulkan compute pipeline for Voxy.
 *
 * RenderPearl 26.3 does not expose compute pipelines through its public API,
 * so this class creates a Vulkan compute pipeline on Minecraft's active
 * VkDevice. It currently supports storage-buffer and uniform-buffer
 * descriptors in descriptor set zero. Sampled images and storage images are
 * intentionally left for a later migration step.
 *
 * The pipeline must only be bound to command buffers from a Vulkan queue
 * family that supports compute. Do not close it or update descriptors until
 * all GPU submissions that reference it have completed.
 */
public final class VoxyVulkanComputePipeline implements AutoCloseable {
    public enum DescriptorType {
        STORAGE_BUFFER(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER),
        UNIFORM_BUFFER(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER),
        COMBINED_IMAGE_SAMPLER(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
        STORAGE_IMAGE(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);

        private final int vkType;

        DescriptorType(int vkType) {
            this.vkType = vkType;
        }
    }

    private final VoxyVulkanContext context;
    private final Map<Integer, DescriptorType> descriptorBindings;
    private final int pushConstantSize;

    private long descriptorSetLayout;
    private long pipelineLayout;
    private long pipeline;
    private long descriptorPool;
    private long descriptorSet;
    private boolean closed;

    /**
     * Creates a pipeline whose listed descriptor bindings are all storage buffers.
     *
     * @param spirv a direct ByteBuffer containing SPIR-V words
     * @param pushConstantSize push-constant range size in bytes; zero disables it
     * @param storageBufferBindings descriptor binding numbers in set zero
     */
    public VoxyVulkanComputePipeline(VoxyVulkanContext context, ByteBuffer spirv,
                                     int pushConstantSize, int... storageBufferBindings) {
        this(context, spirv, pushConstantSize, storageBindingMap(storageBufferBindings));
    }

    /**
     * Creates a pipeline with explicit storage/uniform buffer descriptor types.
     *
     * @param descriptorBindings map of descriptor binding number to descriptor type
     */
    public VoxyVulkanComputePipeline(VoxyVulkanContext context, ByteBuffer spirv,
                                     int pushConstantSize, Map<Integer, DescriptorType> descriptorBindings) {
        if (context == null || spirv == null) {
            throw new IllegalArgumentException("Vulkan context and SPIR-V must not be null");
        }
        if (!spirv.isDirect() || spirv.remaining() < 4 || (spirv.remaining() & 3) != 0) {
            throw new IllegalArgumentException("SPIR-V must be a direct ByteBuffer containing 32-bit words");
        }
        if (pushConstantSize < 0 || (pushConstantSize & 3) != 0) {
            throw new IllegalArgumentException("Push-constant size must be zero or a positive multiple of four");
        }
        if (descriptorBindings == null) {
            throw new IllegalArgumentException("Buffer descriptor bindings must not be null");
        }

        TreeMap<Integer, DescriptorType> copy = new TreeMap<>();
        for (Map.Entry<Integer, DescriptorType> entry : descriptorBindings.entrySet()) {
            Integer binding = entry.getKey();
            DescriptorType type = entry.getValue();
            if (binding == null || binding < 0 || type == null) {
                throw new IllegalArgumentException("Descriptor binding numbers and types must be valid");
            }
            copy.put(binding, type);
        }

        this.context = context;
        this.pushConstantSize = pushConstantSize;
        this.descriptorBindings = Collections.unmodifiableMap(copy);

        try {
            this.create(spirv.slice());
        } catch (RuntimeException | Error failure) {
            this.destroyHandles();
            throw failure;
        }
    }

    /**
     * Creates a pipeline directly from Voxy's owned SPIR-V result.
     */
    public VoxyVulkanComputePipeline(VoxyVulkanContext context, VoxyVulkanSpirv spirv,
                                     int pushConstantSize, int... storageBufferBindings) {
        this(context, requireBytecode(spirv), pushConstantSize, storageBufferBindings);
    }

    /**
     * Creates a pipeline from SPIR-V with explicit storage/uniform descriptors.
     */
    public VoxyVulkanComputePipeline(VoxyVulkanContext context, VoxyVulkanSpirv spirv,
                                     int pushConstantSize, Map<Integer, DescriptorType> descriptorBindings) {
        this(context, requireBytecode(spirv), pushConstantSize, descriptorBindings);
    }

    private static ByteBuffer requireBytecode(VoxyVulkanSpirv spirv) {
        if (spirv == null) {
            throw new IllegalArgumentException("SPIR-V result must not be null");
        }
        return spirv.bytecode();
    }

    private static Map<Integer, DescriptorType> storageBindingMap(int[] bindings) {
        if (bindings == null) {
            throw new IllegalArgumentException("Storage-buffer bindings must not be null");
        }
        Map<Integer, DescriptorType> result = new TreeMap<>();
        for (int binding : bindings) {
            if (binding < 0 || result.put(binding, DescriptorType.STORAGE_BUFFER) != null) {
                throw new IllegalArgumentException("Storage-buffer binding numbers must be unique and non-negative");
            }
        }
        return result;
    }

    private void create(ByteBuffer spirv) {
        VkDevice device = this.context.vkDevice();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer shaderOut = stack.mallocLong(1);
            VkShaderModuleCreateInfo shaderInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default()
                    .pCode(spirv);
            check(vkCreateShaderModule(device, shaderInfo, null, shaderOut), "vkCreateShaderModule");
            long shaderModule = shaderOut.get(0);

            try {
                if (!this.descriptorBindings.isEmpty()) {
                    VkDescriptorSetLayoutBinding.Buffer bindings =
                            VkDescriptorSetLayoutBinding.calloc(this.descriptorBindings.size(), stack);
                    int index = 0;
                    for (Map.Entry<Integer, DescriptorType> entry : this.descriptorBindings.entrySet()) {
                        bindings.get(index++)
                                .binding(entry.getKey())
                                .descriptorType(entry.getValue().vkType)
                                .descriptorCount(1)
                                .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                    }

                    LongBuffer layoutOut = stack.mallocLong(1);
                    VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                            .sType$Default()
                            .pBindings(bindings);
                    check(vkCreateDescriptorSetLayout(device, layoutInfo, null, layoutOut),
                            "vkCreateDescriptorSetLayout");
                    this.descriptorSetLayout = layoutOut.get(0);
                }

                VkPushConstantRange.Buffer pushRanges = null;
                if (this.pushConstantSize > 0) {
                    pushRanges = VkPushConstantRange.calloc(1, stack)
                            .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                            .offset(0)
                            .size(this.pushConstantSize);
                }

                VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                        .sType$Default()
                        .pSetLayouts(this.descriptorSetLayout == 0L ? null : stack.longs(this.descriptorSetLayout))
                        .pPushConstantRanges(pushRanges);
                LongBuffer pipelineLayoutOut = stack.mallocLong(1);
                check(vkCreatePipelineLayout(device, pipelineLayoutInfo, null, pipelineLayoutOut),
                        "vkCreatePipelineLayout");
                this.pipelineLayout = pipelineLayoutOut.get(0);

                VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                        .sType$Default()
                        .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                        .module(shaderModule)
                        .pName(stack.UTF8("main"));

                VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
                pipelineInfo.get(0)
                        .sType$Default()
                        .stage(stage)
                        .layout(this.pipelineLayout);

                LongBuffer pipelineOut = stack.mallocLong(1);
                check(vkCreateComputePipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pipelineOut),
                        "vkCreateComputePipelines");
                this.pipeline = pipelineOut.get(0);
            } finally {
                vkDestroyShaderModule(device, shaderModule, null);
            }

            if (!this.descriptorBindings.isEmpty()) {
                Map<Integer, Integer> descriptorCounts = new TreeMap<>();
                for (DescriptorType type : this.descriptorBindings.values()) {
                    descriptorCounts.merge(type.vkType, 1, Integer::sum);
                }

                VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(descriptorCounts.size(), stack);
                int index = 0;
                for (Map.Entry<Integer, Integer> entry : descriptorCounts.entrySet()) {
                    poolSizes.get(index++)
                            .type(entry.getKey())
                            .descriptorCount(entry.getValue());
                }

                VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                        .sType$Default()
                        .maxSets(1)
                        .pPoolSizes(poolSizes);
                LongBuffer poolOut = stack.mallocLong(1);
                check(vkCreateDescriptorPool(device, poolInfo, null, poolOut), "vkCreateDescriptorPool");
                this.descriptorPool = poolOut.get(0);

                VkDescriptorSetAllocateInfo setInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                        .sType$Default()
                        .descriptorPool(this.descriptorPool)
                        .pSetLayouts(stack.longs(this.descriptorSetLayout));
                LongBuffer setOut = stack.mallocLong(1);
                check(vkAllocateDescriptorSets(device, setInfo, setOut), "vkAllocateDescriptorSets");
                this.descriptorSet = setOut.get(0);
            }
        }
    }

    /**
     * Writes or replaces a storage/uniform buffer descriptor in set zero.
     */
    public void bindBuffer(int binding, VoxyVulkanBuffer buffer, long offset, long range) {
        this.checkOpen();
        DescriptorType type = this.descriptorBindings.get(binding);
        if (type == null) {
            throw new IllegalArgumentException("Pipeline has no buffer descriptor at binding " + binding);
        }
        if (buffer == null || range <= 0 || offset < 0 || offset > buffer.size()
                || range > buffer.size() - offset) {
            throw new IllegalArgumentException("Invalid buffer descriptor range");
        }
        if (type != DescriptorType.STORAGE_BUFFER && type != DescriptorType.UNIFORM_BUFFER) {
            throw new IllegalArgumentException("Binding " + binding + " is an image descriptor, not a buffer descriptor");
        }
        int requiredUsage = type == DescriptorType.STORAGE_BUFFER
                ? VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                : VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
        if (!buffer.supportsUsage(requiredUsage)) {
            throw new IllegalArgumentException("Buffer at descriptor binding " + binding
                    + " was not created with required Vulkan usage flag 0x"
                    + Integer.toHexString(requiredUsage));
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(buffer.buffer())
                    .offset(offset)
                    .range(range);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0)
                    .sType$Default()
                    .dstSet(this.descriptorSet)
                    .dstBinding(binding)
                    .descriptorCount(1)
                    .descriptorType(type.vkType)
                    .pBufferInfo(bufferInfo);
            vkUpdateDescriptorSets(this.context.vkDevice(), write, null);
        }
    }

    public void bindStorageBuffer(int binding, VoxyVulkanBuffer buffer, long offset, long range) {
        this.requireDescriptorType(binding, DescriptorType.STORAGE_BUFFER);
        this.bindBuffer(binding, buffer, offset, range);
    }

    public void bindUniformBuffer(int binding, VoxyVulkanBuffer buffer, long offset, long range) {
        this.requireDescriptorType(binding, DescriptorType.UNIFORM_BUFFER);
        this.bindBuffer(binding, buffer, offset, range);
    }

    /**
     * Binds an image view to a storage-image descriptor in set zero.
     * The image must have been created with VK_IMAGE_USAGE_STORAGE_BIT and
     * transitioned to the supplied layout before dispatch.
     */
    public void bindStorageImage(int binding, VoxyVulkanImage image, long imageView, int imageLayout) {
        this.checkOpen();
        this.requireDescriptorType(binding, DescriptorType.STORAGE_IMAGE);
        if (image == null || imageView == 0L) {
            throw new IllegalArgumentException("Storage image and image view must not be null");
        }
        if (!image.supportsUsage(VK_IMAGE_USAGE_STORAGE_BIT)) {
            throw new IllegalArgumentException("Image lacks VK_IMAGE_USAGE_STORAGE_BIT");
        }
        this.writeImageDescriptor(binding, imageView, 0L, imageLayout);
    }

    public void bindStorageImage(int binding, VoxyVulkanImage image, int imageLayout) {
        if (image == null) {
            throw new IllegalArgumentException("Storage image must not be null");
        }
        this.bindStorageImage(binding, image, image.view(), imageLayout);
    }

    /**
     * Binds a sampled image and sampler to a combined-image-sampler descriptor.
     */
    public void bindCombinedImageSampler(int binding, VoxyVulkanImage image, long imageView,
                                         VoxyVulkanSampler sampler, int imageLayout) {
        this.checkOpen();
        this.requireDescriptorType(binding, DescriptorType.COMBINED_IMAGE_SAMPLER);
        if (image == null || imageView == 0L || sampler == null) {
            throw new IllegalArgumentException("Sampled image, view, and sampler must not be null");
        }
        if (!image.supportsUsage(VK_IMAGE_USAGE_SAMPLED_BIT)) {
            throw new IllegalArgumentException("Image lacks VK_IMAGE_USAGE_SAMPLED_BIT");
        }
        this.writeImageDescriptor(binding, imageView, sampler.sampler(), imageLayout);
    }

    public void bindCombinedImageSampler(int binding, VoxyVulkanImage image,
                                         VoxyVulkanSampler sampler, int imageLayout) {
        if (image == null) {
            throw new IllegalArgumentException("Sampled image must not be null");
        }
        this.bindCombinedImageSampler(binding, image, image.view(), sampler, imageLayout);
    }

    private void writeImageDescriptor(int binding, long imageView, long sampler, int imageLayout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .sampler(sampler)
                    .imageView(imageView)
                    .imageLayout(imageLayout);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0)
                    .sType$Default()
                    .dstSet(this.descriptorSet)
                    .dstBinding(binding)
                    .descriptorCount(1)
                    .descriptorType(this.descriptorBindings.get(binding).vkType)
                    .pImageInfo(imageInfo);
            vkUpdateDescriptorSets(this.context.vkDevice(), write, null);
        }
    }

    /**
     * Binds this compute pipeline and its descriptor set to a command buffer.
     * Use context.allocateAndBeginComputeCommandBuffer() for that command buffer.
     */
    public void bind(VkCommandBuffer commandBuffer) {
        this.checkOpen();
        this.context.requireComputeOnFrontendQueue();
        if (commandBuffer == null) {
            throw new IllegalArgumentException("Command buffer must not be null");
        }
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
        if (this.descriptorSet != 0L) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                vkCmdBindDescriptorSets(
                        commandBuffer,
                        VK_PIPELINE_BIND_POINT_COMPUTE,
                        this.pipelineLayout,
                        0,
                        stack.longs(this.descriptorSet),
                        null
                );
            }
        }
    }

    /**
     * Binds this pipeline, optional push constants, and dispatches the workgroups.
     */
    public void dispatch(VkCommandBuffer commandBuffer, ByteBuffer pushConstants,
                         int groupsX, int groupsY, int groupsZ) {
        this.checkOpen();
        if (groupsX <= 0 || groupsY <= 0 || groupsZ <= 0) {
            throw new IllegalArgumentException("Compute workgroup counts must be positive");
        }
        int pushBytes = pushConstants == null ? 0 : pushConstants.remaining();
        if (pushBytes > this.pushConstantSize || (pushBytes & 3) != 0) {
            throw new IllegalArgumentException("Push constants exceed the declared range or are not 4-byte aligned");
        }
        if (pushBytes > 0 && !pushConstants.isDirect()) {
            throw new IllegalArgumentException("Vulkan push constants must be stored in a direct ByteBuffer");
        }

        this.bind(commandBuffer);
        if (pushBytes > 0) {
            vkCmdPushConstants(
                    commandBuffer,
                    this.pipelineLayout,
                    VK_SHADER_STAGE_COMPUTE_BIT,
                    0,
                    pushConstants.slice()
            );
        }
        vkCmdDispatch(commandBuffer, groupsX, groupsY, groupsZ);
    }

    /**
     * Dispatches using a VkDispatchIndirectCommand stored in a Vulkan buffer.
     * The buffer must have been created with VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT.
     */
    public void dispatchIndirect(VkCommandBuffer commandBuffer, VoxyVulkanBuffer indirectBuffer,
                                 long offset) {
        this.checkOpen();
        this.context.requireComputeOnFrontendQueue();
        if (commandBuffer == null || indirectBuffer == null) {
            throw new IllegalArgumentException("Command buffer and indirect buffer must not be null");
        }
        if ((offset & 3L) != 0L || offset < 0 || offset > indirectBuffer.size()
                || 12L > indirectBuffer.size() - offset) {
            throw new IllegalArgumentException("Indirect dispatch command must be 4-byte aligned and fit in the buffer");
        }
        if (!indirectBuffer.supportsUsage(VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT)) {
            throw new IllegalArgumentException("Indirect dispatch buffer lacks VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT");
        }

        this.bind(commandBuffer);
        vkCmdDispatchIndirect(commandBuffer, indirectBuffer.buffer(), offset);
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.destroyHandles();
        this.closed = true;
    }

    private void requireDescriptorType(int binding, DescriptorType required) {
        this.checkOpen();
        if (this.descriptorBindings.get(binding) != required) {
            throw new IllegalArgumentException("Binding " + binding + " is not a " + required + " descriptor");
        }
    }

    private void destroyHandles() {
        VkDevice device = this.context.vkDevice();
        if (this.descriptorPool != 0L) {
            vkDestroyDescriptorPool(device, this.descriptorPool, null);
            this.descriptorPool = 0L;
        }
        if (this.pipeline != 0L) {
            vkDestroyPipeline(device, this.pipeline, null);
            this.pipeline = 0L;
        }
        if (this.pipelineLayout != 0L) {
            vkDestroyPipelineLayout(device, this.pipelineLayout, null);
            this.pipelineLayout = 0L;
        }
        if (this.descriptorSetLayout != 0L) {
            vkDestroyDescriptorSetLayout(device, this.descriptorSetLayout, null);
            this.descriptorSetLayout = 0L;
        }
    }

    private void checkOpen() {
        if (this.closed) {
            throw new IllegalStateException("Vulkan compute pipeline has already been closed");
        }
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed with VkResult " + result);
        }
    }
}
