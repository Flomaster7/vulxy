package me.cortex.voxy.client.mixin.minecraft;

import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the backend object used by Minecraft's active RenderPearl pass.
 */
@Mixin(FrontendRenderPass.class)
public interface MixinFrontendRenderPass {
    @Accessor("backend")
    RenderPassBackend voxy$getBackend();
}
