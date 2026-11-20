package me.cortex.voxy.client.core;

import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;

/**
 * Holds the RenderPearl device selected by Minecraft.
 *
 * Voxy's renderer still uses OpenGL directly. Keeping the selected RenderPearl
 * device behind this small boundary lets the renderer migrate incrementally
 * without assuming that an OpenGL context exists.
 *
 * This class does not make the current renderer Vulkan-compatible by itself.
 */
public final class VoxyGpuDevice {
    public enum Backend {
        OPENGL,
        VULKAN,
        OTHER
    }

    private static volatile GpuDevice device;
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

        if (selectedBackend instanceof VulkanBackend) {
            backend = Backend.VULKAN;
        } else if (selectedBackend.getClass().getName().toLowerCase().contains("gl")) {
            backend = Backend.OPENGL;
        } else {
            backend = Backend.OTHER;
        }

        backendClassName = selectedBackend.getClass().getName();
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

    public static boolean isInitialized() {
        return device != null;
    }

    public static Backend getBackend() {
        return backend;
    }

    public static String getBackendClassName() {
        return backendClassName;
    }
}
