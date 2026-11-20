package me.cortex.voxy.client.core.rendering.section.backend.mdic;

import me.cortex.voxy.client.core.VoxyVulkanBuffer;
import me.cortex.voxy.client.core.VoxyVulkanComputePipeline;
import me.cortex.voxy.client.core.VoxyVulkanContext;
import me.cortex.voxy.client.core.VoxyVulkanSpirv;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Map;


/**
 * First isolated Vulkan port of the MDIC renderer's prep.comp pass.
 *
 * This pass initializes the indirect culling command and the command-generation
 * dispatch dimensions. It intentionally does not replace the existing
 * MDICSectionRenderer yet; the remaining culling and draw stages still use
 * OpenGL and must be ported before the Vulkan backend can be enabled.
 *
 * The three buffers are fixed for this pass' lifetime. The descriptor set is
 * written once during construction and is not updated per frame, avoiding
 * descriptor mutation while a previous frame may still be using the set.
 *
 * Expected buffer usage:
 * - sceneUniform: VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT
 * - drawCountBuffer: VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
 * - renderListBuffer: VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
 */
public final class VoxyVulkanMDICPrepPass implements AutoCloseable {
    private final VoxyVulkanContext context;
    private final VoxyVulkanComputePipeline pipeline;
    private boolean closed;

    public VoxyVulkanMDICPrepPass(VoxyVulkanContext context,
                                  VoxyVulkanBuffer sceneUniform,
                                  VoxyVulkanBuffer drawCountBuffer,
                                  VoxyVulkanBuffer renderListBuffer) {
        if (context == null || sceneUniform == null || drawCountBuffer == null || renderListBuffer == null) {
            throw new IllegalArgumentException("Vulkan context and all MDIC prep buffers must not be null");
        }
        if (!context.canDispatchComputeOnFrontendQueue()) {
            throw new IllegalStateException("MDIC prep needs a graphics command queue that supports compute");
        }

        this.context = context;
        VoxyVulkanSpirv spirv = VoxyVulkanSpirv.compileVoxyCompute(
                "voxy:lod/gl46/prep.comp",
                Map.of(),
                Map.of()
        );

        try {
            this.pipeline = new VoxyVulkanComputePipeline(
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

        try {
            this.pipeline.bindUniformBuffer(0, sceneUniform, 0, sceneUniform.size());
            this.pipeline.bindStorageBuffer(1, drawCountBuffer, 0, drawCountBuffer.size());
            this.pipeline.bindStorageBuffer(2, renderListBuffer, 0, renderListBuffer.size());
        } catch (RuntimeException | Error failure) {
            this.pipeline.close();
            throw failure;
        }
    }

    /**
     * Records the prep dispatch into Minecraft's active Vulkan encoder.
     *
     * Call outside a RenderPearl render pass, before the culling draw that
     * consumes drawCountBuffer's indirect command. The following barrier makes
     * compute writes visible to indirect drawing and graphics shader reads.
     */
    public void dispatch() {
        this.checkOpen();
        if (this.context.isInRenderPass()) {
            throw new IllegalStateException("MDIC prep must run outside an active render pass");
        }

        VkCommandBuffer commandBuffer = this.context.allocateAndBeginComputeCommandBuffer();
        this.pipeline.dispatch(commandBuffer, null, 1, 1, 1);
        this.context.computeToGraphicsBarrier(commandBuffer);
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
            throw new IllegalStateException("MDIC Vulkan prep pass has already been closed");
        }
    }
}
