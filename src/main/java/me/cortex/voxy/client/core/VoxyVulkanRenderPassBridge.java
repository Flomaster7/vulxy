package me.cortex.voxy.client.core;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import me.cortex.voxy.client.mixin.minecraft.MixinFrontendRenderPass;
import me.cortex.voxy.client.mixin.minecraft.MixinVulkanRenderPass;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Narrow bridge for recording native Vulkan graphics commands inside the
 * RenderPass currently owned by Minecraft.
 *
 * The command buffer belongs to Minecraft's VulkanCommandEncoder. Callers
 * may only record graphics commands while the supplied RenderPass is open.
 * They must not end the render pass, end the command buffer, submit it, or
 * retain the handle beyond the RenderPass lifetime.
 */
public final class VoxyVulkanRenderPassBridge {
    private VoxyVulkanRenderPassBridge() {
    }

    public static boolean isVulkan(RenderPass renderPass) {
        if (!(renderPass instanceof FrontendRenderPass frontendPass)) {
            return false;
        }
        RenderPassBackend backend = ((MixinFrontendRenderPass) frontendPass).voxy$getBackend();
        return backend instanceof VulkanRenderPass;
    }

    /**
     * Returns Minecraft's active Vulkan command buffer for this pass.
     *
     * @throws IllegalArgumentException when the supplied pass is not Vulkan
     * @throws IllegalStateException when the supplied pass has already closed
     */
    public static VkCommandBuffer commandBuffer(RenderPass renderPass) {
        if (!(renderPass instanceof FrontendRenderPass frontendPass)) {
            throw new IllegalArgumentException("RenderPass is not backed by Minecraft's FrontendRenderPass");
        }

        MixinFrontendRenderPass frontendAccessor = (MixinFrontendRenderPass) frontendPass;
        if (frontendAccessor.voxy$isClosed()) {
            throw new IllegalStateException("Cannot record Vulkan commands into a closed RenderPass");
        }

        RenderPassBackend backend = frontendAccessor.voxy$getBackend();
        if (!(backend instanceof VulkanRenderPass vulkanPass)) {
            throw new IllegalArgumentException("RenderPass is not using Minecraft's Vulkan backend");
        }

        VkCommandBuffer commandBuffer =
                ((MixinVulkanRenderPass) vulkanPass).voxy$getCommandBuffer();
        if (commandBuffer == null) {
            throw new IllegalStateException("Minecraft's Vulkan render pass has no active command buffer");
        }
        return commandBuffer;
    }
}
