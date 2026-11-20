package me.cortex.voxy.client.core;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanQueue;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

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
    private final VulkanCommandEncoder commandEncoder;

    VoxyVulkanContext(VulkanDevice device, VulkanCommandEncoder commandEncoder) {
        if (device == null || commandEncoder == null) {
            throw new IllegalArgumentException("Vulkan device and Minecraft command encoder must not be null");
        }
        this.device = device;
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
     * Returns the exact encoder used by Minecraft's RenderPearl frontend.
     */
    public VulkanCommandEncoder commandEncoder() {
        return this.commandEncoder;
    }

    /**
     * Starts a transient command buffer managed by Minecraft's command pool.
     * The caller must end it with vkEndCommandBuffer before enqueueing it.
     */
    public VkCommandBuffer allocateAndBeginTransientCommandBuffer() {
        return this.commandEncoder.allocateAndBeginTransientCommandBuffer();
    }

    /**
     * Ends and inserts a transient command buffer into Minecraft's current
     * submission. It must have been obtained from this encoder and must not
     * have been submitted before.
     */
    public void endAndEnqueue(VkCommandBuffer commandBuffer) {
        if (commandBuffer == null) {
            throw new IllegalArgumentException("Command buffer must not be null");
        }
        int result = VK10.vkEndCommandBuffer(commandBuffer);
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer failed with VkResult " + result);
        }
        this.commandEncoder.execute(commandBuffer);
    }
}
