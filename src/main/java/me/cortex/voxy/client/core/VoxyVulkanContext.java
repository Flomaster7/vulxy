package me.cortex.voxy.client.core;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanQueue;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryBarrier;

/**
 * Narrow bridge from Voxy to Minecraft 26.3's Vulkan implementation.
 *
 * The encoder passed to this class is extracted from Minecraft's existing
 * FrontendCommandEncoder. Voxy must never create or destroy a second encoder:
 * command recording must remain ordered with the current Minecraft frame.
 *
 * RenderPearl owns device, queues, allocator and encoder lifetime. Voxy only
 * records work into command buffers allocated by that encoder.
 */
public final class VoxyVulkanContext {
    private final VulkanDevice device;
    private final FrontendCommandEncoder frontendEncoder;
    private final VulkanCommandEncoder commandEncoder;

    VoxyVulkanContext(VulkanDevice device, FrontendCommandEncoder frontendEncoder,
                      VulkanCommandEncoder commandEncoder) {
        if (device == null || frontendEncoder == null || commandEncoder == null) {
            throw new IllegalArgumentException("Vulkan device and Minecraft command encoders must not be null");
        }
        this.device = device;
        this.frontendEncoder = frontendEncoder;
        this.commandEncoder = commandEncoder;
    }

    public VulkanDevice device() {
        return this.device;
    }

    public VkDevice vkDevice() {
        return this.device.vkDevice();
    }

    public VulkanQueue graphicsQueue() {
        return this.device.graphicsQueue();
    }

    public VulkanQueue computeQueue() {
        return this.device.computeQueue();
    }

    public VulkanQueue transferQueue() {
        return this.device.transferQueue();
    }

    public boolean hasComputeQueue() {
        return this.device.computeQueue() != null;
    }

    /**
     * Minecraft owns this allocator. Voxy may allocate and release its own
     * VMA allocations from it, but must never destroy the allocator itself.
     */
    public long vmaAllocator() {
        return this.device.vma();
    }

    /**
     * Returns the exact backend encoder used by Minecraft's RenderPearl frontend.
     */
    public VulkanCommandEncoder commandEncoder() {
        return this.commandEncoder;
    }

    /**
     * True while Minecraft's public encoder has an active render pass. Voxy
     * must not insert a custom Vulkan submission until that pass has ended.
     */
    public boolean isInRenderPass() {
        return this.frontendEncoder.isInRenderPass();
    }

    /**
     * Starts a transient command buffer managed by Minecraft's command pool.
     * The caller must end it with vkEndCommandBuffer before enqueueing it.
     */
    public VkCommandBuffer allocateAndBeginTransientCommandBuffer() {
        return this.commandEncoder.allocateAndBeginTransientCommandBuffer();
    }

    /**
     * Adds a compute-write barrier for data that will next be consumed by
     * indirect drawing, vertex/index input, or graphics shaders.
     */
    public void computeToGraphicsBarrier(VkCommandBuffer commandBuffer) {
        if (commandBuffer == null) {
            throw new IllegalArgumentException("Command buffer must not be null");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack)
                    .sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_INDIRECT_COMMAND_READ_BIT
                            | VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT
                            | VK_ACCESS_INDEX_READ_BIT
                            | VK_ACCESS_SHADER_READ_BIT);
            vkCmdPipelineBarrier(
                    commandBuffer,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT
                            | VK_PIPELINE_STAGE_VERTEX_INPUT_BIT
                            | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT
                            | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    0,
                    barrier,
                    null,
                    null
            );
        }
    }

    /**
     * Ends and inserts a transient command buffer into Minecraft's current
     * submission. It must have been obtained from this encoder and must not
     * have been submitted before. It is only safe outside an active render pass.
     */
    public void endAndEnqueue(VkCommandBuffer commandBuffer) {
        if (commandBuffer == null) {
            throw new IllegalArgumentException("Command buffer must not be null");
        }
        if (this.frontendEncoder.isInRenderPass()) {
            throw new IllegalStateException("Close the active RenderPearl render pass before enqueueing Vulkan commands");
        }
        int result = VK10.vkEndCommandBuffer(commandBuffer);
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer failed with VkResult " + result);
        }
        this.commandEncoder.execute(commandBuffer);
    }
}
