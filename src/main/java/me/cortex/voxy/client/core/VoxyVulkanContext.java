package me.cortex.voxy.client.core;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanQueue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkQueue;

/**
 * Narrow bridge from Voxy to Minecraft 26.3's Vulkan implementation.
 *
 * RenderPearl owns device and queue lifetime. Voxy must not destroy the
 * returned handles or submit work independently of Minecraft's encoder.
 * Any command recording through this context must happen on the render
 * thread and be ordered with the normal RenderPearl frame submission.
 */
public final class VoxyVulkanContext {
    private final VulkanDevice device;
    private final VulkanCommandEncoder commandEncoder;

    VoxyVulkanContext(VulkanDevice device) {
        if (device == null) {
            throw new IllegalArgumentException("Vulkan device must not be null");
        }
        this.device = device;
        this.commandEncoder = device.createCommandEncoder();
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

    /**
     * The allocator is owned by Minecraft's VulkanDevice. This handle is
     * exposed for allocations whose usage is not represented by GpuBuffer.
     * Never destroy the allocator from Voxy.
     */
    public long vmaAllocator() {
        return this.device.vma();
    }

    /**
     * Returns Minecraft's existing encoder, not a separately owned encoder.
     * Callers must respect its frame lifecycle and render-thread ownership.
     */
    public VulkanCommandEncoder commandEncoder() {
        return this.commandEncoder;
    }

    public VkCommandBuffer allocateAndBeginTransientCommandBuffer() {
        return this.commandEncoder.allocateAndBeginTransientCommandBuffer();
    }

    public void enqueueCommandBuffer(VkCommandBuffer commandBuffer) {
        if (commandBuffer == null) {
            throw new IllegalArgumentException("Command buffer must not be null");
        }
        this.commandEncoder.execute(commandBuffer);
    }
}
