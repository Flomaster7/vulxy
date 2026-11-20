package me.cortex.voxy.client.core;

import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import me.cortex.voxy.client.mixin.minecraft.MixinFrontendGpuDevice;

/**
 * Holds the RenderPearl device selected by Minecraft and, where available,
 * its backend implementation.
 *
 * The public RenderPearl GpuDevice API does not expose compute dispatch. The
 * Vulkan renderer therefore needs a narrow bridge to Minecraft's Vulkan
 * backend while continuing to use RenderPearl for the surrounding device
 * lifecycle and ordinary rendering resources.
 */
public final class VoxyGpuDevice {
    public enum Backend {
        VULKAN,
        OTHER
    }

    private static volatile GpuDevice device;
    private static volatile GpuDeviceBackend implementation;
    private static volatile VoxyVulkanContext vulkanContext;
    private static volatile Backend backend;
    private static volatile String backendClassName = "not initialized";

    private VoxyGpuDevice() {
    }

    /**
     * Records the device Minecraft actually created. Call only after the
     * backend's createDevice method returned successfully.
     */
    public static void capture(GpuBackend selectedBackend, GpuDevice createdDevice) {
        if (selectedBackend == null || createdDevice == null) {
            throw new IllegalArgumentException("The selected GPU backend and device must not be null");
        }

        GpuDeviceBackend backendImplementation = null;
        if (createdDevice instanceof FrontendGpuDevice frontendDevice) {
            backendImplementation = ((MixinFrontendGpuDevice) frontendDevice).voxy$getBackend();
        }

        boolean vulkanSelected = selectedBackend instanceof VulkanBackend;
        VulkanDevice vulkanDevice = backendImplementation instanceof VulkanDevice selectedVulkanDevice
                ? selectedVulkanDevice
                : null;
        FrontendCommandEncoder frontendCommandEncoder = null;
        VulkanCommandEncoder vulkanCommandEncoder = null;
        if (createdDevice instanceof FrontendGpuDevice) {
            var encoder = createdDevice.createCommandEncoder();
            if (encoder instanceof FrontendCommandEncoder wrappedEncoder) {
                frontendCommandEncoder = wrappedEncoder;
                if (wrappedEncoder.backend() instanceof VulkanCommandEncoder selectedEncoder) {
                    vulkanCommandEncoder = selectedEncoder;
                }
            }
        }

        backend = vulkanSelected ? Backend.VULKAN : Backend.OTHER;
        vulkanContext = vulkanDevice == null || frontendCommandEncoder == null || vulkanCommandEncoder == null
                ? null
                : new VoxyVulkanContext(vulkanDevice, frontendCommandEncoder, vulkanCommandEncoder);
        backendClassName = backendImplementation == null
                ? selectedBackend.getClass().getName()
                : backendImplementation.getClass().getName();
        implementation = backendImplementation;
        device = createdDevice;
    }

    /**
     * Returns the captured RenderPearl device, or fails clearly if Minecraft
     * has not created its graphics device yet.
     */
    public static GpuDevice get() {
        GpuDevice current = device;
        if (current == null) {
            throw new IllegalStateException("Minecraft's RenderPearl GPU device has not been captured yet");
        }
        return current;
    }

    /**
     * Returns Minecraft's Vulkan backend. This is intentionally separate from
     * the public RenderPearl API and must only be used by the Vulkan renderer.
     */
    public static VulkanDevice getVulkanDevice() {
        GpuDeviceBackend current = implementation;
        if (backend != Backend.VULKAN || !(current instanceof VulkanDevice vulkanDevice)) {
            throw new IllegalStateException("The active RenderPearl backend is not Vulkan");
        }
        return vulkanDevice;
    }

    public static VoxyVulkanContext getVulkanContext() {
        VoxyVulkanContext current = vulkanContext;
        if (backend != Backend.VULKAN || current == null) {
            throw new IllegalStateException("Minecraft's Vulkan backend is not available through the RenderPearl frontend");
        }
        return current;
    }

    public static boolean isInitialized() {
        return device != null;
    }

    public static boolean isVulkan() {
        return backend == Backend.VULKAN;
    }

    public static Backend getBackend() {
        return backend;
    }

    public static String getBackendClassName() {
        return backendClassName;
    }
}
