package me.cortex.voxy.client.core.rendering.section.backend.mdic;

import me.cortex.voxy.client.core.VoxyVulkanContext;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Records the Vulkan compute stages that follow Voxy's visibility/culling pass.
 *
 * The chain keeps the dependent dispatches in one transient command buffer so
 * the pipeline barriers explicitly connect the command-generation,
 * prefix-sum and translucent-generation stages. It does not own the passes
 * or their GPU buffers; the renderer that creates them must close them after
 * the GPU has finished using their resources.
 *
 * Required frame order:
 * 1. VoxyVulkanMDICPrepPass.dispatch() before visibility culling
 * 2. Vulkan visibility/culling render pass
 * 3. dispatchAfterVisibilityCulling() to generate commands and sort translucency
 * 4. opaque/translucent/temporal Vulkan drawing
 *
 * The visibility/culling render pass and the final drawing stages have not
 * yet been ported, so this coordinator is not currently called by Voxy.
 */
public final class VoxyVulkanMDICComputeChain {
    private final VoxyVulkanContext context;
    private final VoxyVulkanMDICCommandGenPass commandGeneration;
    private final VoxyVulkanMDICPrefixSumPass prefixSum;
    private final VoxyVulkanMDICTranslucentGenPass translucentGeneration;

    public VoxyVulkanMDICComputeChain(
            VoxyVulkanContext context,
            VoxyVulkanMDICCommandGenPass commandGeneration,
            VoxyVulkanMDICPrefixSumPass prefixSum,
            VoxyVulkanMDICTranslucentGenPass translucentGeneration
    ) {
        if (context == null || commandGeneration == null || prefixSum == null || translucentGeneration == null) {
            throw new IllegalArgumentException("MDIC compute chain and all passes must not be null");
        }
        context.requireComputeOnFrontendQueue();
        if (!commandGeneration.usesContext(context) || !prefixSum.usesContext(context)
                || !translucentGeneration.usesContext(context)) {
            throw new IllegalArgumentException("All MDIC compute passes must use the same Vulkan context");
        }
        this.context = context;
        this.commandGeneration = commandGeneration;
        this.prefixSum = prefixSum;
        this.translucentGeneration = translucentGeneration;
    }

    /**
     * Records command generation, translucent prefix sum and final translucent
     * indirect command generation in one command buffer. Call only after
     * visibility culling has finished writing the visibility buffer and before
     * any terrain draw reads the generated indirect commands.
     */
    public void dispatchAfterVisibilityCulling() {
        if (this.context.isInRenderPass()) {
            throw new IllegalStateException("MDIC compute chain must run outside an active RenderPearl render pass");
        }

        VkCommandBuffer commandBuffer = this.context.allocateAndBeginComputeCommandBuffer();
        this.commandGeneration.record(commandBuffer);
        this.prefixSum.record(commandBuffer);
        this.translucentGeneration.record(commandBuffer);
        this.context.endAndEnqueue(commandBuffer);
    }

    /**
     * Runs a correctness-first path without the raster visibility culler.
     *
     * The prep pass creates indirect dispatch parameters, the fallback marks
     * every section in the current list visible, and the remaining MDIC
     * compute passes generate indirect draw commands. This avoids false-
     * negative culling but may render substantially more geometry.
     */
    public void dispatchWithAllVisibleFallback(
            VoxyVulkanMDICPrepPass prep,
            VoxyVulkanMDICAllVisibleFallbackPass visibilityFallback
    ) {
        if (prep == null || visibilityFallback == null) {
            throw new IllegalArgumentException("Prep and visibility fallback passes must not be null");
        }
        if (!prep.usesContext(this.context) || !visibilityFallback.usesContext(this.context)) {
            throw new IllegalArgumentException("All MDIC passes must use the same Vulkan context");
        }
        if (this.context.isInRenderPass()) {
            throw new IllegalStateException("MDIC compute chain must run outside an active RenderPearl render pass");
        }

        VkCommandBuffer commandBuffer = this.context.allocateAndBeginComputeCommandBuffer();
        prep.record(commandBuffer);
        visibilityFallback.record(commandBuffer);
        this.commandGeneration.recordAfterComputeVisibility(commandBuffer);
        this.prefixSum.record(commandBuffer);
        this.translucentGeneration.record(commandBuffer);
        this.context.endAndEnqueue(commandBuffer);
    }

}
