package me.cortex.voxy.client.mixin.minecraft;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.checkpoints.CheckpointExtension;
import me.cortex.voxy.client.core.VoxyGpuDevice;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the physical device selected by Minecraft's RenderPearl Vulkan
 * backend. VulkanDevice does not currently expose VulkanPhysicalDevice from
 * its public methods, so the constructor argument is the reliable source.
 */
@Mixin(VulkanDevice.class)
public abstract class MixinVulkanDevice {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void voxy$capturePhysicalDevice(
            VulkanInstance instance,
            VulkanPhysicalDevice physicalDevice,
            FeatureSet enabledFeatureSet,
            VkDevice vkDevice,
            long vma,
            CheckpointExtension checkpointExtension,
            CallbackInfo ci
    ) {
        VoxyGpuDevice.capturePhysicalDevice(physicalDevice);
    }
}
