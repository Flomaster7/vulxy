package me.cortex.voxy.client.core;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Records native Vulkan graphics commands into Minecraft's active RenderPearl
 * Vulkan render pass.
 *
 * The supplied pipeline, layout, descriptor set and buffers remain owned by
 * their creating Voxy renderer. This helper only records commands; it never
 * ends the RenderPass, command buffer or submits the encoder.
 */
public final class VoxyVulkanGraphicsCommands {
    private VoxyVulkanGraphicsCommands() {
    }

    /**
     * Binds a graphics pipeline, optional descriptor set and index buffer,
     * then issues a Vulkan indexed-indirect draw from the active Minecraft pass.
     *
     * <p>The indirect commands must use VkDrawIndexedIndirectCommand layout
     * (20 bytes minimum per entry). The index buffer may use UINT16 or UINT32;
     * UINT8 indices require an additional enabled Vulkan device feature and
     * are deliberately not exposed by this initial helper.</p>
     */
    public static void drawIndexedIndirect(
            com.mojang.renderpearl.api.commands.RenderPass renderPass,
            long pipeline,
            long pipelineLayout,
            long descriptorSet,
            VoxyVulkanBuffer indexBuffer,
            long indexBufferOffset,
            int indexType,
            VoxyVulkanBuffer indirectBuffer,
            long indirectOffset,
            int drawCount,
            int stride
    ) {
        if (pipeline == 0L || pipelineLayout == 0L) {
            throw new IllegalArgumentException("Vulkan graphics pipeline and pipeline layout must not be null");
        }
        if (indexBuffer == null || indirectBuffer == null) {
            throw new IllegalArgumentException("Index and indirect buffers must not be null");
        }
        if (indexType != VK_INDEX_TYPE_UINT16 && indexType != VK_INDEX_TYPE_UINT32) {
            throw new IllegalArgumentException("Only Vulkan UINT16 and UINT32 index types are supported");
        }
        if (!indexBuffer.supportsUsage(VK_BUFFER_USAGE_INDEX_BUFFER_BIT)) {
            throw new IllegalArgumentException("Index buffer lacks VK_BUFFER_USAGE_INDEX_BUFFER_BIT");
        }
        if (!indirectBuffer.supportsUsage(VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT)) {
            throw new IllegalArgumentException("Draw buffer lacks VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT");
        }
        if (indexBufferOffset < 0 || indexBufferOffset >= indexBuffer.size()) {
            throw new IllegalArgumentException("Index buffer offset is outside the buffer");
        }
        if (drawCount <= 0) {
            throw new IllegalArgumentException("Indirect draw count must be positive");
        }
        if (stride < 20 || (stride & 3) != 0) {
            throw new IllegalArgumentException("VkDrawIndexedIndirectCommand stride must be at least 20 and 4-byte aligned");
        }
        if (indirectOffset < 0 || (indirectOffset & 3L) != 0L) {
            throw new IllegalArgumentException("Indirect command offset must be non-negative and 4-byte aligned");
        }
        long requiredBytes = 20L + (long) (drawCount - 1) * stride;
        if (indirectOffset > indirectBuffer.size() || requiredBytes > indirectBuffer.size() - indirectOffset) {
            throw new IllegalArgumentException("Indexed indirect commands exceed the indirect buffer");
        }

        VkCommandBuffer commandBuffer = VoxyVulkanRenderPassBridge.commandBuffer(renderPass);
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
        if (descriptorSet != 0L) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                vkCmdBindDescriptorSets(
                        commandBuffer,
                        VK_PIPELINE_BIND_POINT_GRAPHICS,
                        pipelineLayout,
                        0,
                        stack.longs(descriptorSet),
                        null
                );
            }
        }
        vkCmdBindIndexBuffer(commandBuffer, indexBuffer.buffer(), indexBufferOffset, indexType);
        vkCmdDrawIndexedIndirect(commandBuffer, indirectBuffer.buffer(), indirectOffset, drawCount, stride);
    }

    /**
     * Issues a non-indexed draw in the active Minecraft Vulkan render pass.
     */
    public static void draw(
            com.mojang.renderpearl.api.commands.RenderPass renderPass,
            long pipeline,
            long pipelineLayout,
            long descriptorSet,
            int vertexCount,
            int instanceCount,
            int firstVertex,
            int firstInstance
    ) {
        if (pipeline == 0L || pipelineLayout == 0L) {
            throw new IllegalArgumentException("Vulkan graphics pipeline and pipeline layout must not be null");
        }
        if (vertexCount <= 0 || instanceCount <= 0 || firstVertex < 0 || firstInstance < 0) {
            throw new IllegalArgumentException("Invalid Vulkan draw arguments");
        }

        VkCommandBuffer commandBuffer = VoxyVulkanRenderPassBridge.commandBuffer(renderPass);
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
        if (descriptorSet != 0L) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                vkCmdBindDescriptorSets(
                        commandBuffer,
                        VK_PIPELINE_BIND_POINT_GRAPHICS,
                        pipelineLayout,
                        0,
                        stack.longs(descriptorSet),
                        null
                );
            }
        }
        vkCmdDraw(commandBuffer, vertexCount, instanceCount, firstVertex, firstInstance);
    }
}
