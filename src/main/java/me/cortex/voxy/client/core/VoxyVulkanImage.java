package me.cortex.voxy.client.core;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.HashSet;
import java.util.Set;

import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * VMA-backed Vulkan 2D image and image views owned by Voxy.
 *
 * Images are created in VK_IMAGE_LAYOUT_UNDEFINED. Callers must record the
 * appropriate image barriers before using them as sampled or storage images,
 * and must not close this object until submitted GPU work that references its
 * image/views has completed.
 */
public final class VoxyVulkanImage implements AutoCloseable {
    private final long allocator;
    private final VkDevice device;
    private final long image;
    private final long allocation;
    private final int format;
    private final int width;
    private final int height;
    private final int mipLevels;
    private final int usage;
    private final int aspectMask;
    private final Set<Long> views = new HashSet<>();
    private boolean closed;

    private long defaultView;

    public VoxyVulkanImage(VoxyVulkanContext context, int format, int width, int height,
                           int mipLevels, int usage, int aspectMask) {
        if (context == null) {
            throw new IllegalArgumentException("Vulkan context must not be null");
        }
        if (format <= 0 || width <= 0 || height <= 0 || mipLevels <= 0 || usage == 0 || aspectMask == 0) {
            throw new IllegalArgumentException("Invalid Vulkan image format, dimensions, mip count, usage, or aspect");
        }
        int maxMipLevels = 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
        if (mipLevels > maxMipLevels) {
            throw new IllegalArgumentException("Requested " + mipLevels + " mip levels but image dimensions allow only "
                    + maxMipLevels);
        }

        this.allocator = context.vmaAllocator();
        this.device = context.vkDevice();
        if (this.allocator == 0L) {
            throw new IllegalStateException("Minecraft's Vulkan VMA allocator is not initialized");
        }
        this.format = format;
        this.width = width;
        this.height = height;
        this.mipLevels = mipLevels;
        this.usage = usage;
        this.aspectMask = aspectMask;

        long createdImage = 0L;
        long createdAllocation = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(format);
            imageInfo.extent()
                    .width(width)
                    .height(height)
                    .depth(1);
            imageInfo.mipLevels(mipLevels)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);

            LongBuffer imageOut = stack.mallocLong(1);
            PointerBuffer allocationOut = stack.mallocPointer(1);
            int result = vmaCreateImage(
                    this.allocator,
                    imageInfo,
                    allocationInfo,
                    imageOut,
                    allocationOut,
                    null
            );
            if (result != VK_SUCCESS) {
                throw new IllegalStateException("vmaCreateImage failed with VkResult " + result);
            }

            createdImage = imageOut.get(0);
            createdAllocation = allocationOut.get(0);
        }

        this.image = createdImage;
        this.allocation = createdAllocation;
        try {
            this.defaultView = this.createView(0, this.mipLevels);
        } catch (RuntimeException | Error failure) {
            vmaDestroyImage(this.allocator, this.image, this.allocation);
            this.closed = true;
            throw failure;
        }
    }

    public long image() {
        this.checkOpen();
        return this.image;
    }

    public long view() {
        this.checkOpen();
        return this.defaultView;
    }

    /**
     * Creates and tracks an additional 2D view over the specified mip range.
     * The returned view is destroyed when this image is closed.
     */
    public long createView(int baseMipLevel, int levelCount) {
        this.checkOpen();
        if (baseMipLevel < 0 || levelCount <= 0 || baseMipLevel >= this.mipLevels
                || levelCount > this.mipLevels - baseMipLevel) {
            throw new IllegalArgumentException("Image view mip range is outside the image");
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkComponentMapping components = VkComponentMapping.calloc(stack)
                    .r(VK_COMPONENT_SWIZZLE_IDENTITY)
                    .g(VK_COMPONENT_SWIZZLE_IDENTITY)
                    .b(VK_COMPONENT_SWIZZLE_IDENTITY)
                    .a(VK_COMPONENT_SWIZZLE_IDENTITY);

            VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
                    .aspectMask(this.aspectMask)
                    .baseMipLevel(baseMipLevel)
                    .levelCount(levelCount)
                    .baseArrayLayer(0)
                    .layerCount(1);

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType$Default()
                    .image(this.image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(this.format)
                    .components(components)
                    .subresourceRange(range);

            LongBuffer viewOut = stack.mallocLong(1);
            int result = vkCreateImageView(this.device, viewInfo, null, viewOut);
            if (result != VK_SUCCESS) {
                throw new IllegalStateException("vkCreateImageView failed with VkResult " + result);
            }

            long view = viewOut.get(0);
            this.views.add(view);
            return view;
        }
    }

    public int format() {
        return this.format;
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    public int mipLevels() {
        return this.mipLevels;
    }

    public int usage() {
        return this.usage;
    }

    public boolean supportsUsage(int requiredUsage) {
        return (this.usage & requiredUsage) == requiredUsage;
    }

    public int aspectMask() {
        return this.aspectMask;
    }

    /**
     * Records an image layout transition for a selected mip range.
     *
     * Queue-family ownership is left unchanged (VK_QUEUE_FAMILY_IGNORED);
     * this helper is for transitions on Minecraft's graphics/compute queue.
     */
    public void transition(VkCommandBuffer commandBuffer, int oldLayout, int newLayout,
                           int sourceAccessMask, int destinationAccessMask,
                           int sourceStageMask, int destinationStageMask,
                           int baseMipLevel, int levelCount) {
        this.checkOpen();
        if (commandBuffer == null) {
            throw new IllegalArgumentException("Command buffer must not be null");
        }
        if (baseMipLevel < 0 || levelCount <= 0 || baseMipLevel >= this.mipLevels
                || levelCount > this.mipLevels - baseMipLevel) {
            throw new IllegalArgumentException("Image transition mip range is outside the image");
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
            barrier.get(0)
                    .sType$Default()
                    .srcAccessMask(sourceAccessMask)
                    .dstAccessMask(destinationAccessMask)
                    .oldLayout(oldLayout)
                    .newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(this.image);
            barrier.get(0).subresourceRange()
                    .aspectMask(this.aspectMask)
                    .baseMipLevel(baseMipLevel)
                    .levelCount(levelCount)
                    .baseArrayLayer(0)
                    .layerCount(1);

            vkCmdPipelineBarrier(
                    commandBuffer,
                    sourceStageMask,
                    destinationStageMask,
                    0,
                    null,
                    null,
                    barrier
            );
        }
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        for (long view : this.views) {
            vkDestroyImageView(this.device, view, null);
        }
        this.views.clear();
        this.defaultView = 0L;
        vmaDestroyImage(this.allocator, this.image, this.allocation);
    }

    private void checkOpen() {
        if (this.closed) {
            throw new IllegalStateException("Vulkan image has already been closed");
        }
    }
}
