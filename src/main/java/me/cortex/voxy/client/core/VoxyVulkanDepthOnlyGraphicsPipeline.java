package me.cortex.voxy.client.core;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Native Vulkan graphics pipeline for Voxy passes that update depth/SSBO data
 * but do not write any color attachments.
 *
 * The pipeline uses dynamic rendering and has no vertex input state. It is
 * suitable for Voxy's raster culler, whose vertex shader builds section bounds
 * from gl_VertexID and SSBO data.
 *
 * The caller supplies the active RenderPass attachment formats. They must
 * match the Vulkan formats of the RenderPass into which the pipeline is bound.
 * Viewport and scissor are dynamic and must be set on the RenderPass before
 * drawing. All descriptors are in set zero.
 */
public final class VoxyVulkanDepthOnlyGraphicsPipeline implements AutoCloseable {
    public enum DescriptorType {
        STORAGE_BUFFER(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER),
        UNIFORM_BUFFER(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);

        private final int vkType;

        DescriptorType(int vkType) {
            this.vkType = vkType;
        }
    }

    private final VoxyVulkanContext context;
    private final Map<Integer, DescriptorType> descriptorBindings;
    private final VkDevice device;

    private long descriptorSetLayout;
    private long descriptorPool;
    private long descriptorSet;
    private long pipelineLayout;
    private long pipeline;
    private boolean closed;

    public VoxyVulkanDepthOnlyGraphicsPipeline(
            VoxyVulkanContext context,
            VoxyVulkanSpirv vertexShader,
            VoxyVulkanSpirv fragmentShader,
            int[] colorAttachmentFormats,
            int depthAttachmentFormat,
            int stencilAttachmentFormat,
            int sampleCount,
            int depthCompareOp,
            boolean depthTest,
            boolean depthWrite,
            Map<Integer, DescriptorType> descriptorBindings
    ) {
        if (context == null || vertexShader == null || fragmentShader == null) {
            throw new IllegalArgumentException("Context and both graphics shader modules must not be null");
        }
        if (colorAttachmentFormats == null || descriptorBindings == null) {
            throw new IllegalArgumentException("Attachment formats and descriptor bindings must not be null");
        }
        if (depthTest && depthAttachmentFormat == VK_FORMAT_UNDEFINED) {
            throw new IllegalArgumentException("Depth testing requires an active depth attachment format");
        }
        if (sampleCount != VK_SAMPLE_COUNT_1_BIT && sampleCount != VK_SAMPLE_COUNT_2_BIT
                && sampleCount != VK_SAMPLE_COUNT_4_BIT && sampleCount != VK_SAMPLE_COUNT_8_BIT
                && sampleCount != VK_SAMPLE_COUNT_16_BIT) {
            throw new IllegalArgumentException("Unsupported Vulkan sample count");
        }
        for (int format : colorAttachmentFormats) {
            if (format == VK_FORMAT_UNDEFINED) {
                throw new IllegalArgumentException("Color attachment formats must be concrete Vulkan formats");
            }
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
        this.device = context.vkDevice();
        this.descriptorBindings = Collections.unmodifiableMap(copy);

        try {
            this.create(
                    vertexShader.bytecode(),
                    fragmentShader.bytecode(),
                    colorAttachmentFormats.clone(),
                    depthAttachmentFormat,
                    stencilAttachmentFormat,
                    sampleCount,
                    depthCompareOp,
                    depthTest,
                    depthWrite
            );
        } catch (RuntimeException | Error failure) {
            this.destroyHandles();
            throw failure;
        }
    }

    private void create(ByteBuffer vertexSpirv, ByteBuffer fragmentSpirv, int[] colorFormats,
                        int depthFormat, int stencilFormat, int sampleCount,
                        int depthCompareOp, boolean depthTest, boolean depthWrite) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long vertexModule = createShaderModule(vertexSpirv);
            long fragmentModule = 0L;
            try {
                fragmentModule = createShaderModule(fragmentSpirv);

                this.createDescriptorLayout(stack);
                this.createPipelineLayout(stack);

                VkPipelineShaderStageCreateInfo.Buffer stages =
                        VkPipelineShaderStageCreateInfo.calloc(2, stack);
                stages.get(0)
                        .sType$Default()
                        .stage(VK_SHADER_STAGE_VERTEX_BIT)
                        .module(vertexModule)
                        .pName(stack.UTF8("main"));
                stages.get(1)
                        .sType$Default()
                        .stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                        .module(fragmentModule)
                        .pName(stack.UTF8("main"));

                VkPipelineVertexInputStateCreateInfo vertexInput =
                        VkPipelineVertexInputStateCreateInfo.calloc(stack)
                                .sType$Default();

                VkPipelineInputAssemblyStateCreateInfo inputAssembly =
                        VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                                .sType$Default()
                                .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
                                .primitiveRestartEnable(false);

                VkPipelineViewportStateCreateInfo viewport =
                        VkPipelineViewportStateCreateInfo.calloc(stack)
                                .sType$Default()
                                .viewportCount(1)
                                .scissorCount(1);

                VkPipelineRasterizationStateCreateInfo rasterization =
                        VkPipelineRasterizationStateCreateInfo.calloc(stack)
                                .sType$Default()
                                .depthClampEnable(false)
                                .rasterizerDiscardEnable(false)
                                .polygonMode(VK_POLYGON_MODE_FILL)
                                .cullMode(VK_CULL_MODE_NONE)
                                .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                                .depthBiasEnable(false)
                                .lineWidth(1.0f);

                VkPipelineMultisampleStateCreateInfo multisample =
                        VkPipelineMultisampleStateCreateInfo.calloc(stack)
                                .sType$Default()
                                .rasterizationSamples(sampleCount)
                                .sampleShadingEnable(false)
                                .alphaToCoverageEnable(false)
                                .alphaToOneEnable(false);

                VkPipelineDepthStencilStateCreateInfo depthStencil =
                        VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                                .sType$Default()
                                .depthTestEnable(depthTest)
                                .depthWriteEnable(depthWrite)
                                .depthCompareOp(depthCompareOp)
                                .depthBoundsTestEnable(false)
                                .stencilTestEnable(false);

                VkPipelineColorBlendAttachmentState.Buffer colorAttachments =
                        colorFormats.length == 0 ? null
                                : VkPipelineColorBlendAttachmentState.calloc(colorFormats.length, stack);
                if (colorAttachments != null) {
                    for (int i = 0; i < colorFormats.length; i++) {
                        colorAttachments.get(i)
                                .blendEnable(false)
                                .colorWriteMask(0);
                    }
                }

                VkPipelineColorBlendStateCreateInfo colorBlend =
                        VkPipelineColorBlendStateCreateInfo.calloc(stack)
                                .sType$Default()
                                .logicOpEnable(false)
                                .attachmentCount(colorFormats.length)
                                .pAttachments(colorAttachments);

                IntBuffer dynamicValues = stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR);
                VkPipelineDynamicStateCreateInfo dynamicState =
                        VkPipelineDynamicStateCreateInfo.calloc(stack)
                                .sType$Default()
                                .pDynamicStates(dynamicValues);

                VkPipelineRenderingCreateInfo rendering =
                        VkPipelineRenderingCreateInfo.calloc(stack)
                                .sType$Default()
                                .viewMask(0)
                                .depthAttachmentFormat(depthFormat)
                                .stencilAttachmentFormat(stencilFormat);
                if (colorFormats.length > 0) {
                    rendering.pColorAttachmentFormats(stack.ints(colorFormats));
                }

                VkGraphicsPipelineCreateInfo.Buffer pipelineInfo =
                        VkGraphicsPipelineCreateInfo.calloc(1, stack);
                pipelineInfo.get(0)
                        .sType$Default()
                        .pNext(rendering.address())
                        .pStages(stages)
                        .pVertexInputState(vertexInput)
                        .pInputAssemblyState(inputAssembly)
                        .pViewportState(viewport)
                        .pRasterizationState(rasterization)
                        .pMultisampleState(multisample)
                        .pDepthStencilState(depthStencil)
                        .pColorBlendState(colorBlend)
                        .pDynamicState(dynamicState)
                        .layout(this.pipelineLayout)
                        .renderPass(VK_NULL_HANDLE)
                        .subpass(0)
                        .basePipelineHandle(VK_NULL_HANDLE)
                        .basePipelineIndex(-1);

                LongBuffer pipelineOut = stack.mallocLong(1);
                int result = vkCreateGraphicsPipelines(
                        this.device,
                        VK_NULL_HANDLE,
                        pipelineInfo,
                        null,
                        pipelineOut
                );
                if (result != VK_SUCCESS) {
                    throw new IllegalStateException("vkCreateGraphicsPipelines failed with VkResult " + result);
                }
                this.pipeline = pipelineOut.get(0);
            } finally {
                if (fragmentModule != 0L) {
                    vkDestroyShaderModule(this.device, fragmentModule, null);
                }
                vkDestroyShaderModule(this.device, vertexModule, null);
            }
        }
    }

    private long createShaderModule(ByteBuffer spirv) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default()
                    .pCode(spirv);
            LongBuffer moduleOut = stack.mallocLong(1);
            int result = vkCreateShaderModule(this.device, createInfo, null, moduleOut);
            if (result != VK_SUCCESS) {
                throw new IllegalStateException("vkCreateShaderModule failed with VkResult " + result);
            }
            return moduleOut.get(0);
        }
    }

    private void createDescriptorLayout(MemoryStack stack) {
        if (this.descriptorBindings.isEmpty()) {
            return;
        }

        VkDescriptorSetLayoutBinding.Buffer bindings =
                VkDescriptorSetLayoutBinding.calloc(this.descriptorBindings.size(), stack);
        int index = 0;
        int shaderStages = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
        for (Map.Entry<Integer, DescriptorType> entry : this.descriptorBindings.entrySet()) {
            bindings.get(index++)
                    .binding(entry.getKey())
                    .descriptorType(entry.getValue().vkType)
                    .descriptorCount(1)
                    .stageFlags(shaderStages);
        }

        VkDescriptorSetLayoutCreateInfo layoutInfo =
                VkDescriptorSetLayoutCreateInfo.calloc(stack)
                        .sType$Default()
                        .pBindings(bindings);
        LongBuffer layoutOut = stack.mallocLong(1);
        int result = vkCreateDescriptorSetLayout(this.device, layoutInfo, null, layoutOut);
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("vkCreateDescriptorSetLayout failed with VkResult " + result);
        }
        this.descriptorSetLayout = layoutOut.get(0);
    }

    private void createPipelineLayout(MemoryStack stack) {
        VkPipelineLayoutCreateInfo layoutInfo =
                VkPipelineLayoutCreateInfo.calloc(stack)
                        .sType$Default()
                        .pSetLayouts(this.descriptorSetLayout == 0L
                                ? null
                                : stack.longs(this.descriptorSetLayout));
        LongBuffer layoutOut = stack.mallocLong(1);
        int result = vkCreatePipelineLayout(this.device, layoutInfo, null, layoutOut);
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("vkCreatePipelineLayout failed with VkResult " + result);
        }
        this.pipelineLayout = layoutOut.get(0);

        if (!this.descriptorBindings.isEmpty()) {
            Map<Integer, Integer> counts = new TreeMap<>();
            for (DescriptorType type : this.descriptorBindings.values()) {
                counts.merge(type.vkType, 1, Integer::sum);
            }
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(counts.size(), stack);
            int index = 0;
            for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
                poolSizes.get(index++)
                        .type(entry.getKey())
                        .descriptorCount(entry.getValue());
            }

            VkDescriptorPoolCreateInfo poolInfo =
                    VkDescriptorPoolCreateInfo.calloc(stack)
                            .sType$Default()
                            .maxSets(1)
                            .pPoolSizes(poolSizes);
            LongBuffer poolOut = stack.mallocLong(1);
            int result = vkCreateDescriptorPool(this.device, poolInfo, null, poolOut);
            if (result != VK_SUCCESS) {
                throw new IllegalStateException("vkCreateDescriptorPool failed with VkResult " + result);
            }
            this.descriptorPool = poolOut.get(0);

            VkDescriptorSetAllocateInfo setInfo =
                    VkDescriptorSetAllocateInfo.calloc(stack)
                            .sType$Default()
                            .descriptorPool(this.descriptorPool)
                            .pSetLayouts(stack.longs(this.descriptorSetLayout));
            LongBuffer setOut = stack.mallocLong(1);
            result = vkAllocateDescriptorSets(this.device, setInfo, setOut);
            if (result != VK_SUCCESS) {
                throw new IllegalStateException("vkAllocateDescriptorSets failed with VkResult " + result);
            }
            this.descriptorSet = setOut.get(0);
        }
    }

    public long pipeline() {
        this.checkOpen();
        return this.pipeline;
    }

    public long pipelineLayout() {
        this.checkOpen();
        return this.pipelineLayout;
    }

    public long descriptorSet() {
        this.checkOpen();
        return this.descriptorSet;
    }

    public void bindStorageBuffer(int binding, VoxyVulkanBuffer buffer, long offset, long range) {
        this.bindBuffer(binding, DescriptorType.STORAGE_BUFFER, buffer, offset, range);
    }

    public void bindUniformBuffer(int binding, VoxyVulkanBuffer buffer, long offset, long range) {
        this.bindBuffer(binding, DescriptorType.UNIFORM_BUFFER, buffer, offset, range);
    }

    private void bindBuffer(int binding, DescriptorType requiredType,
                            VoxyVulkanBuffer buffer, long offset, long range) {
        this.checkOpen();
        if (this.descriptorBindings.get(binding) != requiredType) {
            throw new IllegalArgumentException("Descriptor binding " + binding + " is not " + requiredType);
        }
        if (buffer == null || range <= 0 || offset < 0 || offset > buffer.size()
                || range > buffer.size() - offset) {
            throw new IllegalArgumentException("Invalid Vulkan buffer descriptor range");
        }

        int requiredUsage = requiredType == DescriptorType.STORAGE_BUFFER
                ? VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                : VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
        if (!buffer.supportsUsage(requiredUsage)) {
            throw new IllegalArgumentException("Buffer does not have the required Vulkan usage flag for binding " + binding);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorBufferInfo.Buffer bufferInfo =
                    VkDescriptorBufferInfo.calloc(1, stack)
                            .buffer(buffer.buffer())
                            .offset(offset)
                            .range(range);
            VkWriteDescriptorSet.Buffer write =
                    VkWriteDescriptorSet.calloc(1, stack);
            write.get(0)
                    .sType$Default()
                    .dstSet(this.descriptorSet)
                    .dstBinding(binding)
                    .descriptorCount(1)
                    .descriptorType(requiredType.vkType)
                    .pBufferInfo(bufferInfo);
            vkUpdateDescriptorSets(this.device, write, null);
        }
    }

    public void bind(VkCommandBuffer commandBuffer) {
        this.checkOpen();
        if (commandBuffer == null) {
            throw new IllegalArgumentException("Command buffer must not be null");
        }
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, this.pipeline);
        if (this.descriptorSet != 0L) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                vkCmdBindDescriptorSets(
                        commandBuffer,
                        VK_PIPELINE_BIND_POINT_GRAPHICS,
                        this.pipelineLayout,
                        0,
                        stack.longs(this.descriptorSet),
                        null
                );
            }
        }
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
        if (this.descriptorPool != 0L) {
            vkDestroyDescriptorPool(this.device, this.descriptorPool, null);
            this.descriptorPool = 0L;
        }
        if (this.pipeline != 0L) {
            vkDestroyPipeline(this.device, this.pipeline, null);
            this.pipeline = 0L;
        }
        if (this.pipelineLayout != 0L) {
            vkDestroyPipelineLayout(this.device, this.pipelineLayout, null);
            this.pipelineLayout = 0L;
        }
        if (this.descriptorSetLayout != 0L) {
            vkDestroyDescriptorSetLayout(this.device, this.descriptorSetLayout, null);
            this.descriptorSetLayout = 0L;
        }
    }

    private void checkOpen() {
        if (this.closed) {
            throw new IllegalStateException("Vulkan graphics pipeline has already been closed");
        }
    }
}
