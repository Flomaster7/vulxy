package me.cortex.voxy.client.core.rendering.section.backend.mdic;

import me.cortex.voxy.client.core.VoxyVulkanBuffer;
import me.cortex.voxy.client.core.VoxyVulkanComputePipeline;
import me.cortex.voxy.client.core.VoxyVulkanContext;
import me.cortex.voxy.client.core.VoxyVulkanSpirv;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Conservative Vulkan visibility fallback for MDIC bring-up.
 *
 * Instead of rasterizing section bounds against the depth buffer, this pass
 * marks every section in the current indirect lookup list as visible. It
 * preserves Voxy's frameId and previous-frame visibility encoding, so the
 * existing cmdgen.comp logic can still create opaque, temporal and translucent
 * draw commands.
 *
 * This deliberately disables frustum and occlusion culling. It is useful as
 * a correctness-first fallback while the real Vulkan raster culler is ported,
 * but can be substantially slower in large worlds.
 */
public final class VoxyVulkanMDICAllVisibleFallbackPass implements AutoCloseable {
    private final VoxyVulkanContext context;
    private final VoxyVulkanComputePipeline pipeline;
    private final VoxyVulkanBuffer dispatchBuffer;
    private boolean closed;

    public VoxyVulkanMDICAllVisibleFallbackPass(
            VoxyVulkanContext context,
            VoxyVulkanBuffer sceneUniform,
            VoxyVulkanBuffer visibilityBuffer,
            VoxyVulkanBuffer indirectSectionLookupBuffer,
            VoxyVulkanBuffer drawCountBuffer
    ) {
        if (context == null || sceneUniform == null || visibilityBuffer == null
                || indirectSectionLookupBuffer == null || drawCountBuffer == null) {
            throw new IllegalArgumentException("All-visible fallback context and buffers must not be null");
        }
        context.requireComputeOnFrontendQueue();
        if (!drawCountBuffer.supportsUsage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT)) {
            throw new IllegalArgumentException("Draw-count buffer must support storage and indirect usage");
        }

        this.context = context;
        this.dispatchBuffer = drawCountBuffer;

        VoxyVulkanSpirv spirv = VoxyVulkanSpirv.compileVoxyCompute(
                "voxy:lod/vulkan/mark_all_visible.comp",
                Map.of(),
                Map.of()
        );

        VoxyVulkanComputePipeline created;
        try {
            created = new VoxyVulkanComputePipeline(
                    context,
                    spirv,
                    0,
                    Map.of(
                            0, VoxyVulkanComputePipeline.DescriptorType.UNIFORM_BUFFER,
                            1, VoxyVulkanComputePipeline.DescriptorType.STORAGE_BUFFER,
                            2, VoxyVulkanComputePipeline.DescriptorType.STORAGE_BUFFER
                    )
            );
        } finally {
            spirv.close();
        }
        this.pipeline = created;

        try {
            this.pipeline.bindUniformBuffer(0, sceneUniform, 0, sceneUniform.size());
            this.pipeline.bindStorageBuffer(1, visibilityBuffer, 0, visibilityBuffer.size());
            this.pipeline.bindStorageBuffer(2, indirectSectionLookupBuffer, 0, indirectSectionLookupBuffer.size());
        } catch (RuntimeException | Error failure) {
            this.pipeline.close();
            throw failure;
        }
    }

    /**
     * Records the fallback into an existing command buffer. The indirect group
     * count is written by prep.comp at byte offset zero of drawCountBuffer.
     */
    public void record(VkCommandBuffer commandBuffer) {
        this.checkOpen();
        if (commandBuffer == null) {
            throw new IllegalArgumentException("Command buffer must not be null");
        }
        this.context.computeToComputeBarrier(commandBuffer);
        this.pipeline.dispatchIndirect(commandBuffer, this.dispatchBuffer, 0);
        this.context.computeToComputeBarrier(commandBuffer);
    }

    public void dispatch() {
        this.checkOpen();
        if (this.context.isInRenderPass()) {
            throw new IllegalStateException("MDIC visibility fallback must run outside an active render pass");
        }
        VkCommandBuffer commandBuffer = this.context.allocateAndBeginComputeCommandBuffer();
        this.record(commandBuffer);
        this.context.endAndEnqueue(commandBuffer);
    }

    boolean usesContext(VoxyVulkanContext context) {
        return this.context == context;
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
            throw new IllegalStateException("MDIC all-visible fallback pass has already been closed");
        }
    }
}
