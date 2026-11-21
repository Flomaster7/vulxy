package me.cortex.voxy.client.core;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkSamplerCreateInfo;


import static org.lwjgl.vulkan.VK10.*;

/**
 * Small owned Vulkan sampler wrapper for Voxy's sampled-image passes.
 */
public final class VoxyVulkanSampler implements AutoCloseable {
    public enum Filter {
        NEAREST(VK_FILTER_NEAREST),
        LINEAR(VK_FILTER_LINEAR);

        private final int vkValue;

        Filter(int vkValue) {
            this.vkValue = vkValue;
        }
    }

    public enum AddressMode {
        REPEAT(VK_SAMPLER_ADDRESS_MODE_REPEAT),
        MIRRORED_REPEAT(VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT),
        CLAMP_TO_EDGE(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE),
        CLAMP_TO_BORDER(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER);

        private final int vkValue;

        AddressMode(int vkValue) {
            this.vkValue = vkValue;
        }
    }

    private final VkDevice device;
    private final long sampler;
    private boolean closed;

    public VoxyVulkanSampler(VoxyVulkanContext context, Filter minFilter, Filter magFilter,
                             AddressMode addressMode, float maxLod) {
        if (context == null || minFilter == null || magFilter == null || addressMode == null) {
            throw new IllegalArgumentException("Vulkan context and sampler modes must not be null");
        }
        if (!Float.isFinite(maxLod) || maxLod < 0.0f) {
            throw new IllegalArgumentException("Maximum sampler LOD must be finite and non-negative");
        }

        this.device = context.vkDevice();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo createInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType$Default()
                    .magFilter(magFilter.vkValue)
                    .minFilter(minFilter.vkValue)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(addressMode.vkValue)
                    .addressModeV(addressMode.vkValue)
                    .addressModeW(addressMode.vkValue)
                    .mipLodBias(0.0f)
                    .anisotropyEnable(false)
                    .maxAnisotropy(1.0f)
                    .compareEnable(false)
                    .compareOp(VK_COMPARE_OP_ALWAYS)
                    .minLod(0.0f)
                    .maxLod(maxLod)
                    .borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK)
                    .unnormalizedCoordinates(false);

            var samplerOut = stack.mallocLong(1);
            int result = vkCreateSampler(this.device, createInfo, null, samplerOut);
            if (result != VK_SUCCESS) {
                throw new IllegalStateException("vkCreateSampler failed with VkResult " + result);
            }
            this.sampler = samplerOut.get(0);
        }
    }

    public long sampler() {
        this.checkOpen();
        return this.sampler;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        vkDestroySampler(this.device, this.sampler, null);
    }

    private void checkOpen() {
        if (this.closed) {
            throw new IllegalStateException("Vulkan sampler has already been closed");
        }
    }
}
