package me.cortex.voxy.client.core.rendering.section.backend.mdic;

import me.cortex.voxy.client.core.VoxyVulkanBuffer;
import me.cortex.voxy.client.core.VoxyVulkanComputePipeline;
import me.cortex.voxy.client.core.VoxyVulkanContext;
import me.cortex.voxy.client.core.VoxyVulkanSpirv;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Map;

/**
 * Isolated Vulkan port of the MDIC translucent-distance prefix-sum pass.
 *
 * The simple shared-memory shader is used as the correctness baseline. It
 * avoids depending on subgroup-size assumptions while the Vulkan feature set
 * is still being integrated. The dispatch processes the 256 entries used by
 * Voxy's existing distance bucket array.
 */
public final class VoxyVulkanMDICPrefixSumPass implements AutoCloseable {
    public static final int WORK_SIZE = 256;

    private final VoxyVulkanContext context;
    private final VoxyVulkanComputePipeline pipeline;
    private boolean closed;

    public VoxyVulkanMDICPrefixSumPass(VoxyVulkanContext context, VoxyVulkanBuffer distanceCountBuffer) {
        if (context == null || distanceCountBuffer == null) {
            throw new IllegalArgumentException("Vulkan context and distance-count buffer must not be null");
        }
        context.requireComputeOnFrontendQueue();
        if (distanceCountBuffer.size() < (long) WORK_SIZE * 16L) {
            throw new IllegalArgumentException("Distance-count buffer must contain at least 256 uvec4 entries");
        }

        this.context = context;
        VoxyVulkanSpirv spirv = VoxyVulkanSpirv.compileVoxyCompute(
                "voxy:util/prefixsum/simple.comp",
                Map.of("IO_BUFFER", "0"),
                Map.of()
        );

        VoxyVulkanComputePipeline created;
        try {
            created = new VoxyVulkanComputePipeline(
                    context,
                    spirv,
                    0,
                    0
            );
        } finally {
            spirv.close();
        }
        this.pipeline = created;

        try {
            this.pipeline.bindStorageBuffer(0, distanceCountBuffer, 0, distanceCountBuffer.size());
        } catch (RuntimeException | Error failure) {
            this.pipeline.close();
            throw failure;
        }
    }

    /**
     * Runs the 256-lane prefix sum after cmdgen has populated distance buckets.
     * The resulting offsets are consumed by the translucent command generator.
     */
    public void record(VkCommandBuffer commandBuffer) {
        this.checkOpen();
        if (commandBuffer == null) {
            throw new IllegalArgumentException("Command buffer must not be null");
        }
        this.context.computeToComputeBarrier(commandBuffer);
        this.pipeline.dispatch(commandBuffer, null, 1, 1, 1);
        this.context.computeToComputeBarrier(commandBuffer);
    }

    public void dispatch() {
        this.checkOpen();
        if (this.context.isInRenderPass()) {
            throw new IllegalStateException("MDIC prefix sum must run outside an active render pass");
        }
        VkCommandBuffer commandBuffer = this.context.allocateAndBeginComputeCommandBuffer();
        this.record(commandBuffer);
        this.context.endAndEnqueue(commandBuffer);
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.pipeline.close();
    }

    private void checkOpen() {
        if (this.closed) {
            throw new IllegalStateException("MDIC Vulkan prefix-sum pass has already been closed");
        }
    }
}
