package me.cortex.voxy.client.mixin.minecraft;

import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the command buffer currently recording Minecraft's Vulkan render pass.
 *
 * Voxy may record compatible graphics commands into this command buffer only
 * while the RenderPass is active. It must not end the pass or submit the
 * command buffer; Minecraft retains ownership of both operations.
 */
@Mixin(VulkanRenderPass.class)
public interface MixinVulkanRenderPass {
    @Accessor("commandBuffer")
    VkCommandBuffer voxy$getCommandBuffer();
}
