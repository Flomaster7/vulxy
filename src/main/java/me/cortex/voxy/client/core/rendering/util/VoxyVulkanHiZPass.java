package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.VoxyVulkanComputePipeline;
import me.cortex.voxy.client.core.VoxyVulkanContext;
import me.cortex.voxy.client.core.VoxyVulkanImage;
import me.cortex.voxy.client.core.VoxyVulkanSampler;
import me.cortex.voxy.client.core.VoxyVulkanSpirv;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan compute implementation of Voxy's subgroup-accelerated hiz.comp.
 *
 * The pass writes mip levels 1..6 of a 7-level R32_SFLOAT Hi-Z image. Mip 0
 * must already contain the source depth values and be in
 * VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL. Mips 1..6 must be in
 * VK_IMAGE_LAYOUT_GENERAL before dispatch.
 *
 * After dispatch, those output mips remain in GENERAL layout. The caller must
 * insert the appropriate image transitions before a later sampled-image use.
 * This class is an isolated rendering primitive and is not yet wired into
 * Voxy's OpenGL HiZBuffer2 or MDIC culling path.
 */
public final class VoxyVulkanHiZPass implements AutoCloseable {
    public static final int MIP_COUNT = 7;
    public static final int MIN_DIMENSION = 64;

    private final VoxyVulkanContext context;
    private final VoxyVulkanImage image;
    private final VoxyVulkanComputePipeline pipeline;
    private final int width;
    private final int height;
    private boolean closed;

    public VoxyVulkanHiZPass(VoxyVulkanContext context, VoxyVulkanImage image,
                             VoxyVulkanSampler sampler, int width, int height) {
        if (context == null || image == null || sampler == null) {
            throw new IllegalArgumentException("Vulkan Hi-Z context, image, and sampler must not be null");
        }
        if (width < MIN_DIMENSION || height < MIN_DIMENSION) {
            throw new IllegalArgumentException("Hi-Z dimensions must be at least 64x64");
        }
        if (image.width() != width || image.height() != height
                || image.mipLevels() < MIP_COUNT || image.format() != VK_FORMAT_R32_SFLOAT) {
            throw new IllegalArgumentException("Hi-Z requires a matching R32_SFLOAT image with at least seven mip levels");
        }
        if (!image.supportsUsage(VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) {
            throw new IllegalArgumentException("Hi-Z image must support both sampled and storage image usage");
        }
        if (!context.supportsClusteredComputeSubgroups()) {
            throw new IllegalStateException("The Vulkan device does not support compute clustered subgroup operations required by hiz.comp");
        }
        context.requireComputeOnFrontendQueue();

        this.context = context;
        this.image = image;
        this.width = width;
        this.height = height;

        VoxyVulkanSpirv spirv = VoxyVulkanSpirv.compileVoxyCompute(
                "voxy:hiz/hiz.comp",
                Map.of(),
                Map.of(
                        "layout(location = 0) uniform vec2 invImSize;",
                        "layout(push_constant) uniform VoxyHiZPushConstants { vec2 invImSize; };"
                )
        );

        VoxyVulkanComputePipeline created;
        try {
            created = new VoxyVulkanComputePipeline(
                    context,
                    spirv,
                    8,
                    Map.of(
                            0, VoxyVulkanComputePipeline.DescriptorType.COMBINED_IMAGE_SAMPLER,
                            1, VoxyVulkanComputePipeline.DescriptorType.STORAGE_IMAGE,
                            2, VoxyVulkanComputePipeline.DescriptorType.STORAGE_IMAGE,
                            3, VoxyVulkanComputePipeline.DescriptorType.STORAGE_IMAGE,
                            4, VoxyVulkanComputePipeline.DescriptorType.STORAGE_IMAGE,
                            5, VoxyVulkanComputePipeline.DescriptorType.STORAGE_IMAGE,
                            6, VoxyVulkanComputePipeline.DescriptorType.STORAGE_IMAGE
                    )
            );
        } finally {
            spirv.close();
        }
        this.pipeline = created;

        try {
            long mip0View = image.createView(0, 1);
            this.pipeline.bindCombinedImageSampler(
                    0, image, mip0View, sampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
            );
            for (int mip = 1; mip < MIP_COUNT; mip++) {
                long mipView = image.createView(mip, 1);
                this.pipeline.bindStorageImage(
                        mip, image, mipView, VK_IMAGE_LAYOUT_GENERAL
                );
            }
        } catch (RuntimeException | Error failure) {
            this.pipeline.close();
            throw failure;
        }
    }

    /**
     * Records the Hi-Z reduction. The input/output mip layouts must be prepared
     * as documented on the class before this method is called.
     */
    public void dispatch() {
        this.checkOpen();
        if (this.context.isInRenderPass()) {
            throw new IllegalStateException("Hi-Z compute must run outside an active RenderPearl render pass");
        }

        VkCommandBuffer commandBuffer = this.context.allocateAndBeginComputeCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer pushConstants = stack.malloc(8)
                    .putFloat(1.0f / this.width)
                    .putFloat(1.0f / this.height);
            pushConstants.flip();

            this.pipeline.dispatch(
                    commandBuffer,
                    pushConstants,
                    this.width / 64,
                    this.height / 64,
                    1
            );
        }

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
            throw new IllegalStateException("Vulkan Hi-Z pass has already been closed");
        }
    }
}
