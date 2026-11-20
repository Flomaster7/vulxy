package me.cortex.voxy.client.core;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * A VMA-backed Vulkan buffer owned by Voxy but allocated from Minecraft's
 * RenderPearl allocator.
 *
 * This wrapper deliberately exposes the VkBuffer handle rather than trying
 * to imitate the integer OpenGL object id used by the old renderer. All
 * commands that use this buffer must be recorded on a queue family compatible
 * with the buffer's exclusive-sharing creation mode.
 *
 * The buffer must not be closed until all submitted GPU work using it has
 * completed. For mapped allocations, callers must flush host writes before
 * GPU reads and invalidate GPU writes before host reads.
 */
public final class VoxyVulkanBuffer implements AutoCloseable {
    public enum MemoryAccess {
        DEVICE_LOCAL,
        HOST_WRITE,
        HOST_READ
    }

    private final long allocator;
    private final long buffer;
    private final long allocation;
    private final long size;
    private final long mappedAddress;
    private boolean closed;

    public VoxyVulkanBuffer(VoxyVulkanContext context, long size, int usage, MemoryAccess access) {
        if (context == null) {
            throw new IllegalArgumentException("Vulkan context must not be null");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("Buffer size must be positive");
        }
        if (usage == 0) {
            throw new IllegalArgumentException("At least one Vulkan buffer usage flag is required");
        }
        if (access == null) {
            throw new IllegalArgumentException("Memory access must not be null");
        }

        this.allocator = context.vmaAllocator();
        if (this.allocator == 0L) {
            throw new IllegalStateException("Minecraft's Vulkan VMA allocator is not initialized");
        }
        this.size = size;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(size)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            int allocationFlags;
            int memoryUsage;
            switch (access) {
                case DEVICE_LOCAL -> {
                    allocationFlags = 0;
                    memoryUsage = VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE;
                }
                case HOST_WRITE -> {
                    allocationFlags = VMA_ALLOCATION_CREATE_MAPPED_BIT
                            | VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
                    memoryUsage = VMA_MEMORY_USAGE_AUTO_PREFER_HOST;
                }
                case HOST_READ -> {
                    allocationFlags = VMA_ALLOCATION_CREATE_MAPPED_BIT
                            | VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT;
                    memoryUsage = VMA_MEMORY_USAGE_AUTO_PREFER_HOST;
                }
                default -> throw new IllegalStateException("Unhandled memory access mode: " + access);
            }

            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(memoryUsage)
                    .flags(allocationFlags);

            LongBuffer bufferOut = stack.mallocLong(1);
            PointerBuffer allocationOut = stack.mallocPointer(1);
            VmaAllocationInfo allocationDetails = access == MemoryAccess.DEVICE_LOCAL
                    ? null
                    : VmaAllocationInfo.malloc(stack);

            int result = vmaCreateBuffer(
                    this.allocator,
                    bufferInfo,
                    allocationInfo,
                    bufferOut,
                    allocationOut,
                    allocationDetails
            );
            if (result != VK_SUCCESS) {
                throw new IllegalStateException("vmaCreateBuffer failed with VkResult " + result);
            }

            this.buffer = bufferOut.get(0);
            this.allocation = allocationOut.get(0);
            this.mappedAddress = allocationDetails == null ? 0L : allocationDetails.pMappedData();
            if (access != MemoryAccess.DEVICE_LOCAL && this.mappedAddress == 0L) {
                vmaDestroyBuffer(this.allocator, this.buffer, this.allocation);
                this.closed = true;
                throw new IllegalStateException("VMA created a host-accessible buffer without a mapped address");
            }
        }
    }

    public long buffer() {
        this.checkOpen();
        return this.buffer;
    }

    public long size() {
        return this.size;
    }

    public boolean isMapped() {
        return this.mappedAddress != 0L;
    }

    /**
     * Returns the persistent mapped native address. The caller must not use
     * this address after this buffer is closed.
     */
    public long mappedAddress() {
        this.checkOpen();
        if (this.mappedAddress == 0L) {
            throw new IllegalStateException("This Vulkan buffer is not host-mapped");
        }
        return this.mappedAddress;
    }

    /**
     * Makes host writes visible to the GPU. Call after writing mapped memory
     * and before submitting commands that read this buffer.
     */
    public void flush(long offset, long length) {
        this.checkRange(offset, length);
        this.requireMapped();
        vmaFlushAllocation(this.allocator, this.allocation, offset, length);
    }

    /**
     * Makes GPU writes visible to the CPU. Call only after the GPU work that
     * writes this buffer has completed.
     */
    public void invalidate(long offset, long length) {
        this.checkRange(offset, length);
        this.requireMapped();
        vmaInvalidateAllocation(this.allocator, this.allocation, offset, length);
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        vmaDestroyBuffer(this.allocator, this.buffer, this.allocation);
    }

    private void requireMapped() {
        this.checkOpen();
        if (this.mappedAddress == 0L) {
            throw new IllegalStateException("This Vulkan buffer is not host-mapped");
        }
    }

    private void checkRange(long offset, long length) {
        this.checkOpen();
        if (offset < 0 || length <= 0 || offset > this.size || length > this.size - offset) {
            throw new IndexOutOfBoundsException("Buffer range [" + offset + ", " + (offset + length)
                    + ") exceeds buffer size " + this.size);
        }
    }

    private void checkOpen() {
        if (this.closed) {
            throw new IllegalStateException("Vulkan buffer has already been closed");
        }
    }
}
