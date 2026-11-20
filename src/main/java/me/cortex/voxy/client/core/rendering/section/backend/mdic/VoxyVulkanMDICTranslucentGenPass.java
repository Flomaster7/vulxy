package me.cortex.voxy.client.core.rendering.section.backend.mdic;

import me.cortex.voxy.client.core.VoxyVulkanBuffer;
import me.cortex.voxy.client.core.VoxyVulkanComputePipeline;
import me.cortex.voxy.client.core.VoxyVulkanContext;
import me.cortex.voxy.client.core.VoxyVulkanSpirv;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Map;
import java.util.TreeMap;

/**
 * Isolated Vulkan port of Voxy's MDIC buildtranslucents.comp pass.
 *
 * Reads the translucent section list and the prefix-summed distance buckets,
 * then writes the final translucent indirect draw commands. Dispatch size is
 * read from the same indirect triplet prepared for cmdgen by the preceding
 * prep.comp pass.
 */
public final class VoxyVulkanMDICTranslucentGenPass implements AutoCloseable {
    public static final int TRANSLUCENT_WRITE_BASE = 1024;
    public static final int TRANSLUCENT_OFFSET = 400_000;

    private final VoxyVulkanContext context;
    private final VoxyVulkanComputePipeline pipeline;
    private final VoxyVulkanBuffer dispatchBuffer;
    private boolean closed;

    public VoxyVulkanMDICTranslucentGenPass(
            VoxyVulkanContext context,
            VoxyVulkanBuffer sceneUniform,
            VoxyVulkanBuffer drawBuffer,
            VoxyVulkanBuffer drawCountBuffer,
            VoxyVulkanBuffer sectionMetadataBuffer,
            VoxyVulkanBuffer indirectSectionLookupBuffer,
            VoxyVulkanBuffer translucentDistanceBuffer
    ) {
        if (context == null || sceneUniform == null || drawBuffer == null || drawCountBuffer == null
                || sectionMetadataBuffer == null || indirectSectionLookupBuffer == null
                || translucentDistanceBuffer == null) {
            throw new IllegalArgumentException("MDIC translucent generation context and buffers must not be null");
        }
        context.requireComputeOnFrontendQueue();
        if (!drawBuffer.supportsUsage(org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                | org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT)) {
            throw new IllegalArgumentException("Translucent draw buffer must support storage and indirect usage");
        }
        if (!drawCountBuffer.supportsUsage(org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                | org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT)) {
            throw new IllegalArgumentException("MDIC draw-count buffer must support storage and indirect usage");
        }

        this.context = context;
        this.dispatchBuffer = drawCountBuffer;

        Map<String, String> defines = new TreeMap<>();
        defines.put("TRANSLUCENT_WRITE_BASE", Integer.toString(TRANSLUCENT_WRITE_BASE));
        defines.put("TRANSLUCENT_DISTANCE_BUFFER_BINDING", "5");
        defines.put("TRANSLUCENT_OFFSET", Integer.toString(TRANSLUCENT_OFFSET));

        VoxyVulkanSpirv spirv = VoxyVulkanSpirv.compileVoxyCompute(
                "voxy:lod/gl46/buildtranslucents.comp",
                defines,
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
                            2, VoxyVulkanComputePipeline.DescriptorType.STORAGE_BUFFER,
                            3, VoxyVulkanComputePipeline.DescriptorType.STORAGE_BUFFER,
                            4, VoxyVulkanComputePipeline.DescriptorType.STORAGE_BUFFER,
                            5, VoxyVulkanComputePipeline.DescriptorType.STORAGE_BUFFER
                    )
            );
        } finally {
            spirv.close();
        }
        this.pipeline = created;

        try {
            this.pipeline.bindUniformBuffer(0, sceneUniform, 0, sceneUniform.size());
            this.pipeline.bindStorageBuffer(1, drawBuffer, 0, drawBuffer.size());
            this.pipeline.bindStorageBuffer(2, drawCountBuffer, 0, drawCountBuffer.size());
            this.pipeline.bindStorageBuffer(3, sectionMetadataBuffer, 0, sectionMetadataBuffer.size());
            this.pipeline.bindStorageBuffer(4, indirectSectionLookupBuffer, 0, indirectSectionLookupBuffer.size());
            this.pipeline.bindStorageBuffer(5, translucentDistanceBuffer, 0, translucentDistanceBuffer.size());
        } catch (RuntimeException | Error failure) {
            this.pipeline.close();
            throw failure;
        }
    }

    /**
     * Dispatches after the prefix sum. Writes are made visible to the following
     * translucent indirect-draw pass with a compute-to-graphics barrier.
     */
    public void record(VkCommandBuffer commandBuffer) {
        this.checkOpen();
        if (commandBuffer == null) {
            throw new IllegalArgumentException("Command buffer must not be null");
        }
        this.context.computeToComputeBarrier(commandBuffer);
        this.pipeline.dispatchIndirect(commandBuffer, this.dispatchBuffer, 0);
        this.context.computeToGraphicsBarrier(commandBuffer);
    }

    public void dispatch() {
        this.checkOpen();
        if (this.context.isInRenderPass()) {
            throw new IllegalStateException("MDIC translucent generation must run outside an active render pass");
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
            throw new IllegalStateException("MDIC Vulkan translucent generation pass has already been closed");
        }
    }
}
