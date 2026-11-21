package me.cortex.voxy.client.mixin.minecraft;

import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the backend owned by Minecraft's RenderPearl frontend wrapper.
 *
 * The public GpuDevice deliberately hides backend-specific details. Voxy's
 * Vulkan compute path will need a narrow, version-pinned bridge to the
 * VulkanDevice implementation because RenderPearl's public API has no
 * compute-dispatch API yet.
 */
@Mixin(FrontendGpuDevice.class)
public interface MixinFrontendGpuDevice {
    @Accessor("backend")
    GpuDeviceBackend voxy$getBackend();
}
