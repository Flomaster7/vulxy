package me.cortex.voxy.client.core.rendering.section.backend.mdic;

import me.cortex.voxy.client.core.VoxyVulkanBuffer;
import me.cortex.voxy.client.core.VoxyVulkanComputePipeline;
import me.cortex.voxy.client.core.VoxyVulkanContext;
import me.cortex.voxy.client.core.VoxyVulkanSpirv;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Map;
import java.util.TreeMap;

/**
 * Isolated Vulkan implementation of Voxy's MDIC cmdgen.comp pass.
 *
 * This pass turns the visibility results produced by the raster culler into
 * opaque/temporal indirect draw commands and the distance buckets used by
 * translucent sorting. It is not wired into MDICSectionRenderer yet.
 *
 * The caller must provide the exact buffer roles used by the OpenGL renderer.
 * The draw-count buffer must contain the indirect dispatch triplet in bytes
 * 0..11, prepared by VoxyVulkanMDICPrepPass.
 *
 * This stage deliberately uses a Vulkan compute queue-capable graphics queue
 * and waits for the prior graphics culling writes with a pipeline barrier.
 */
public final class VoxyVulkanMDICCommandGenPass implements AutoCloseable {
    public static final int TRANSLUCENT_WRITE_BASE = 1024;
    public static final int TEMPORAL_OFFSET = 500_000;
    public static final int STATISTICS_BINDING = 8;

    private final VoxyVulkanContext context;
    private final VoxyVulkanComputePipeline pipeline;
    private final VoxyVulkanBuffer dispatchBuffer;
    private final boolean statisticsEnabled;
    private boolean closed;

    public VoxyVulkanMDICCommandGenPass(
            VoxyVulkanContext context,
            VoxyVulkanBuffer sceneUniform,
            VoxyVulkanBuffer drawBuffer,
            VoxyVulkanBuffer drawCountBuffer,
            VoxyVulkanBuffer sectionMetadataBuffer,
            VoxyVulkanBuffer visibilityBuffer,
            VoxyVulkanBuffer indirectSectionLookupBuffer,
            VoxyVulkanBuffer positionScratchBuffer,
            VoxyVulkanBuffer translucentDistanceBuffer,
            VoxyVulkanBuffer statisticsBuffer
    ) {
        if (context == null || sceneUniform == null || drawBuffer == null || drawCountBuffer == null
                || sectionMetadataBuffer == null || visibilityBuffer == null
                || indirectSectionLookupBuffer == null || positionScratchBuffer == null
                || translucentDistanceBuffer == null) {
            throw new IllegalArgumentException("MDIC command-generation context and required buffers must not be null");
        }
        context.requireComputeOnFrontendQueue();

        this.context = context;
        this.dispatchBuffer = drawCountBuffer;
        this.statisticsEnabled = statisticsBuffer != null;

        Map<String, String> defines = Map.of(
                "TRANSLUCENT_WRITE_BASE", Integer.toString(TRANSLUCENT_WRITE_BASE),
                "TEMPORAL_OFFSET", Integer.toString(TEMPORAL_OFFSET),
                "TRANSLUCENT_DISTANCE_BUFFER_BINDING", "7"
        );
        Map<String, String> replacements = Map.of();
        VoxyVulkanSpirv spirv = VoxyVulkanSpirv.compileVoxyCompute(
                "voxy:lod/gl46/cmdgen.comp",
                defines,
                replacements
        );

        VoxyVulkanComputePipeline createdPipeline;
        try {
            Map<Integer, VoxyVulkanComputePipeline.DescriptorType> bindings = new TreeMap<>();
            bindings.put(0, VoxyVulkanComputePipeline.DescriptorType.UNIFORM_BUFFER);
            for (int binding = 1; binding <= 7; binding++) {
                bindings.put(binding, VoxyVulkanComputePipeline.DescriptorType.STORAGE_BUFFER);
            }
            if (this.statisticsEnabled) {
                bindings.put(STATISTICS_BINDING, VoxyVulkanComputePipeline.DescriptorType.STORAGE_BUFFER);
            }

            createdPipeline = new VoxyVulkanComputePipeline(context, spirv, 0, bindings);
        } finally {
            spirv.close();
        }
        this.pipeline = createdPipeline;

        try {
            this.pipeline.bindUniformBuffer(0, sceneUniform, 0, sceneUniform.size());
            this.pipeline.bindStorageBuffer(1, drawBuffer, 0, drawBuffer.size());
            this.pipeline.bindStorageBuffer(2, drawCountBuffer, 0, drawCountBuffer.size());
            this.pipeline.bindStorageBuffer(3, sectionMetadataBuffer, 0, sectionMetadataBuffer.size());
            this.pipeline.bindStorageBuffer(4, visibilityBuffer, 0, visibilityBuffer.size());
            this.pipeline.bindStorageBuffer(5, indirectSectionLookupBuffer, 0, indirectSectionLookupBuffer.size());
            this.pipeline.bindStorageBuffer(6, positionScratchBuffer, 0, positionScratchBuffer.size());
            this.pipeline.bindStorageBuffer(7, translucentDistanceBuffer, 0, translucentDistanceBuffer.size());
            if (this.statisticsEnabled) {
                this.pipeline.bindStorageBuffer(STATISTICS_BINDING, statisticsBuffer, 0, statisticsBuffer.size());
            }
        } catch (RuntimeException | Error failure) {
            this.pipeline.close();
            throw failure;
        }
    }

    /**
     * Records one indirect command-generation dispatch.
     *
     * The graphics-to-compute barrier is required because the preceding
     * visibility raster pass writes the visibility storage buffer. The final
     * compute-to-compute barrier makes generated counters and draw commands
     * available to subsequent prefix-sum/translucency compute stages.
     */
    public void dispatch() {
        this.checkOpen();
        if (this.context.isInRenderPass()) {
            throw new IllegalStateException("MDIC command generation must run outside an active render pass");
        }

        VkCommandBuffer commandBuffer = this.context.allocateAndBeginComputeCommandBuffer();
        this.context.graphicsToComputeBarrier(commandBuffer);
        this.pipeline.dispatchIndirect(commandBuffer, this.indirectDispatchBuffer(), 0);
        this.context.computeToComputeBarrier(commandBuffer);
        this.context.endAndEnqueue(commandBuffer);
    }

    private VoxyVulkanBuffer indirectDispatchBuffer() {
        return this.dispatchBuffer;
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
            throw new IllegalStateException("MDIC Vulkan command-generation pass has already been closed");
        }
    }
}
