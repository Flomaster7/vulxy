package me.cortex.voxy.client.core;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.HashSet;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Minimal native Vulkan compute pipeline for Voxy.
 *
 * RenderPearl 26.3 currently exposes render pipelines but no public compute
 * pipeline/dispatch API. This class therefore creates a Vulkan compute
 * pipeline against Minecraft's existing VkDevice. It supports one descriptor
 * set containing storage-buffer bindings; sampled images and other descriptor
 * types can be added when Voxy's shaders are migrated.
 *
 * The pipeline must only be used with command buffers from VoxyVulkanContext.
 * Do not close it until all GPU submissions that reference it have completed.
 */
public final class VoxyVulkanComputePipeline implements AutoCloseable {
    private final VoxyVulkanContext context;
    private final Set<Integer> storageBindings;
    private final int pushConstantSize;

    private long descriptorSetLayout;
    private long pipelineLayout;
    private long pipeline;
    private long descriptorPool;
    private long descriptorSet;
    private boolean closed;

    /**
     * @param spirv a direct ByteBuffer containing compiled SPIR-V words
     * @param storageBufferBindings descriptor binding numbers for storage buffers
     * @param pushConstantSize push-constant range size in bytes; zero disables it
     */
    public VoxyVulkanComputePipeline(VoxyVulkanContext context, ByteBuffer spirv,
                                     int pushConstantSize, int... storageBufferBindings) {
        if (context == null || spirv == null) {
            throw new IllegalArgumentException("Vulkan context and SPIR-V must not be null");
        }
        if (!spirv.isDirect() || spirv.remaining() < 4 || (spirv.remaining() & 3) != 0) {
            throw new IllegalArgumentException("SPIR-V must be a direct ByteBuffer containing 32-bit words");
        }
        if (pushConstantSize < 0 || (pushConstantSize & 3) != 0) {
            throw new IllegalArgumentException("Push-constant size must be zero or a positive multiple of four");
        }
        if (storageBufferBindings == null) {
            throw new IllegalArgumentException("Storage-buffer bindings must not be null");
        }

        this.context = context;
        this.pushConstantSize = pushConstantSize;
        this.storageBindings = new HashSet<>();
        for (int binding : storageBufferBindings) {
            if (binding < 0 || !this.storageBindings.add(binding)) {
                throw new IllegalArgumentException("Storage-buffer binding numbers must be unique and non-negative");
            }
        }

        try {
            this.create(spirv.slice());
        } catch (RuntimeException | Error failure) {
            this.destroyHandles();
            throw failure;
        }
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
                if (!this.storageBindings.isEmpty()) {
                    VkDescriptorSetLayoutBinding.Buffer bindings =
                            VkDescriptorSetLayoutBinding.calloc(this.storageBindings.size(), stack);
                    int index = 0;
                    for (int binding : this.storageBindings) {
                        bindings.get(index++)
                                .binding(binding)
                                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
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

            if (!this.storageBindings.isEmpty()) {
                VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack)
                        .type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .descriptorCount(this.storageBindings.size());

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
     * Compiles a compute pipeline directly from Voxy's owned SPIR-V result.
     */
    public VoxyVulkanComputePipeline(VoxyVulkanContext context, VoxyVulkanSpirv spirv,
                                     int pushConstantSize, int... storageBufferBindings) {
        this(context, requireBytecode(spirv), pushConstantSize, storageBufferBindings);
    }

    private static ByteBuffer requireBytecode(VoxyVulkanSpirv spirv) {
        if (spirv == null) {
            throw new IllegalArgumentException("SPIR-V result must not be null");
        }
        return spirv.bytecode();
    }

    /**
     * Writes or replaces one storage-buffer descriptor in set zero.
     */
    public void bindStorageBuffer(int binding, VoxyVulkanBuffer buffer, long offset, long range) {
        this.checkOpen();
        if (!this.storageBindings.contains(binding)) {
            throw new IllegalArgumentException("Pipeline has no storage-buffer binding " + binding);
        }
        if (buffer == null || range <= 0 || offset < 0 || offset > buffer.size()
                || range > buffer.size() - offset) {
            throw new IllegalArgumentException("Invalid storage-buffer range");
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
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(bufferInfo);
            vkUpdateDescriptorSets(this.context.vkDevice(), write, null);
        }
    }

    /**
     * Binds this compute pipeline and its descriptor set to a command buffer.
     */
    public void bind(VkCommandBuffer commandBuffer) {
        this.checkOpen();
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

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.destroyHandles();
        this.closed = true;
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
