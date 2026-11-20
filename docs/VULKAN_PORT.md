# Native Vulkan support roadmap

This document tracks the native Minecraft 26.3 RenderPearl port for Vulxy.
The implementation must use Minecraft's active graphics backend; it must not
create a hidden OpenGL context or depend on VIRIS.

## Current baseline

- Target: Minecraft 26.3
- Fabric Loader: 0.19.5
- Sodium: mc26.3-0.9.3-beta.1-fabric
- Voxy base: 0.2.20-beta
- Main renderer: `VoxyRenderSystem`
- Section renderer: `MDICSectionRenderer`
- Current GPU implementation: direct LWJGL OpenGL calls, including DSA,
  compute shaders, SSBOs, persistent mapping, indirect draw commands,
  framebuffer objects, and OpenGL shader programs.
- `MixinMinecraft.voxy$forceOpenglDevice` currently rejects Vulkan during
  Minecraft device creation. This guard must remain until the renderer is
  genuinely backend-independent; removing it alone would make Voxy issue GL
  calls without a current OpenGL context.

## Porting strategy

A native port must be incremental and keep the existing OpenGL path working.

1. **Backend and capability boundary**
   - Detect the active RenderPearl device/backend without relying on GL globals.
   - Move capability checks out of the static OpenGL-only `Capabilities`
     initialization path.
   - Keep Vulkan disabled for Voxy until every required operation is provided.

2. **Resource layer**
   - Replace direct GL ownership in buffers, textures, samplers, and fences
     with backend-neutral resource wrappers.
   - Preserve explicit lifetime and synchronization semantics.
   - Implement RenderPearl-backed resources for both supported backends.

3. **Shader and pipeline layer**
   - Port GLSL compilation and pipeline creation to RenderPearl's shader and
     pipeline APIs.
   - Replace GL binding points with explicit resource bindings.
   - Audit shader layouts, locations, depth conventions, and indirect draw
     semantics under Vulkan.

4. **Compute and section rendering**
   - Port compute dispatch, barriers, SSBO access, and indirect command
     generation.
   - Port MDIC terrain rendering and verify translucent/temporal passes.
   - Preserve fallback behavior where a Vulkan device lacks an optional
     feature.

5. **Frame integration and validation**
   - Integrate Voxy into the Minecraft frame/command submission lifecycle.
   - Validate resource synchronization, depth/stencil behavior, and resize.
   - Only then remove the Vulkan rejection guard.

## First known blockers

- The current renderer assumes OpenGL 4.5/4.6 entry points throughout the
  render system and section renderer.
- `Capabilities` calls `GL.getCapabilities()` during initialization and
  directly queries OpenGL extensions.
- `GlPersistentMappedBuffer` exposes a persistent native pointer, which does
  not map one-to-one to RenderPearl's mapped buffer and synchronization model.
- The current shader loader creates OpenGL shader objects and links GL
  programs; Vulkan requires RenderPearl pipeline compilation and explicit
  resource layouts.
- The current renderer uses framebuffer and global binding state. RenderPearl
  records commands through command encoders and render passes instead.

## Acceptance criteria

Vulkan support is not complete until all of the following are true:

- Minecraft starts with Vulkan selected and Voxy enabled, without an OpenGL
  side context.
- Entering and leaving a world does not leak GPU resources or crash.
- Opaque, translucent, and temporal LOD rendering work.
- Resizing, changing render distance, and changing dimensions work.
- No OpenGL-only path is reached while Vulkan is active.
- The existing OpenGL path still builds and works.
- CI build passes; runtime validation is still required on a real Vulkan GPU.

## Current branch progress

The first code boundary is now in place:

- `VoxyGpuDevice` stores the RenderPearl `GpuDevice` and backend instance after Minecraft successfully creates a device.
- The Minecraft mixin captures the returned device only after `createDevice` succeeds.
- Vulkan is still rejected before device creation. This is intentional: Voxy's current renderer still calls OpenGL directly.
- The captured device is groundwork for replacing Voxy's GL resource layer with RenderPearl resources. It is not yet used to render Voxy frames and does not mean Vulkan support is working.

The next migration target is the buffer/resource layer. In particular, Voxy's persistent mapped buffers and direct OpenGL buffer bindings need explicit replacements before the Vulkan guard can be reconsidered.

## Native Vulkan groundwork added

The branch now contains a narrow Vulkan bridge and initial resource primitives:

- `MixinFrontendGpuDevice` exposes the selected RenderPearl backend.
- `VoxyGpuDevice` captures the actual Minecraft device and extracts the backend encoder from the existing `FrontendCommandEncoder`. It does not create a second command encoder.
- `VoxyVulkanContext` exposes Minecraft's Vulkan device, queues and VMA allocator. It can allocate transient command buffers from Minecraft's encoder, end and enqueue them, and issue a compute-write-to-graphics-read barrier.
- `VoxyVulkanBuffer` creates VMA-backed Vulkan buffers, including storage buffers and mapped host-write/read allocations. Callers must keep allocations alive until their GPU work has completed.
- `VoxyVulkanComputePipeline` creates a native compute pipeline from precompiled SPIR-V and supports a descriptor set of storage-buffer bindings plus push constants.

These classes are foundations only. No Voxy section renderer uses them yet. The existing OpenGL renderer remains active, and the Vulkan rejection guard remains in place.

### Remaining blockers before enabling Voxy on Vulkan

- Compile Voxy's existing shader sources to Vulkan SPIR-V and map their resource layouts.
- Port the MDIC visibility/command-generation compute passes to the new pipeline and storage-buffer resources.
- Replace OpenGL fences, upload/download streams, textures, samplers and framebuffer usage.
- Port terrain drawing, including opaque, translucent and temporal passes, to Vulkan render passes/pipelines.
- Verify queue-family compatibility, barriers, resource lifetime and render-thread scheduling on real Vulkan hardware.
- Run a full Gradle build and runtime tests. The new primitives have not yet been build- or runtime-verified.

### Shader compilation and queue safety

- `VoxyVulkanSpirv` now compiles compute GLSL through LWJGL Shaderc for Vulkan 1.2 / SPIR-V 1.5.
- `compileVoxyCompute` loads Voxy shader assets through the existing `ShaderLoader`, so Voxy's recursive `#import` tree is expanded before Shaderc sees the source. Compile-time defines and textual replacements can be supplied explicitly.
- The compute pipeline now supports both storage-buffer and uniform-buffer descriptors. This is enough for the descriptor shape used by the first small Voxy compute pass (`lod/gl46/prep.comp`), but it does not yet mean that pass is scheduled by the renderer.
- Compute dispatch through Minecraft's frontend command encoder is now gated on the selected graphics and compute queues sharing a queue family. If they differ, Voxy refuses to dispatch through that encoder rather than recording an invalid command buffer. A dedicated compute-queue submission path, ownership transfers and semaphores are still required for that case.

The compiler and pipeline still need a real Gradle build, shader compilation test and Vulkan runtime validation. Some Voxy shaders also rely on OpenGL-only extensions or capabilities and cannot be assumed to compile unchanged for Vulkan.

### First MDIC pass extracted

`VoxyVulkanMDICPrepPass` is now an isolated Vulkan implementation of the existing `voxy:lod/gl46/prep.comp` step:

- It loads the original Voxy shader resource and expands its existing `#import` files.
- It compiles the shader to Vulkan SPIR-V through Shaderc.
- It creates a compute pipeline with binding 0 as a uniform buffer and bindings 1/2 as storage buffers.
- It records one compute workgroup into a transient command buffer from Minecraft's RenderPearl encoder.
- It inserts a compute-to-indirect-draw/graphics memory barrier, then enqueues the command buffer into Minecraft's current encoder submission.

This pass is deliberately isolated and is **not yet called by `MDICSectionRenderer`**. Its buffers must be persistent for the pass lifetime, and the queue-family compatibility check must pass. The next renderer stages still include Vulkan culling, command generation, prefix-sum/translucent sorting, texture/Hi-Z handling and all terrain draw passes.

### Build classpath

Gradle now declares `lwjgl-shaderc` and `lwjgl-vma` as compile-only modules. Minecraft 26.3 already supplies their Java bindings and platform natives at runtime, so Voxy does not bundle a second copy of those native libraries.

### More MDIC compute stages extracted

The native compute path now has isolated passes for the next three stages:

- `VoxyVulkanMDICCommandGenPass` compiles `lod/gl46/cmdgen.comp`, binds the scene uniform plus seven storage-buffer roles (and optional statistics binding 8), then dispatches indirectly from the command triplet written by `prep.comp`.
- `VoxyVulkanMDICPrefixSumPass` compiles the existing simple 256-lane prefix-sum shader. This is the correctness-first path and does not require subgroup-size assumptions.
- `VoxyVulkanMDICTranslucentGenPass` compiles `lod/gl46/buildtranslucents.comp` and creates the final translucent indirect commands using the prefix-summed distance buckets.

The pass ordering is intended to be:

1. Prep dispatch
2. Raster visibility/culling (not ported yet)
3. Graphics-to-compute barrier, then command generation
4. Compute-to-compute barrier, then prefix sum
5. Compute-to-compute barrier, then translucent command generation
6. Compute-to-graphics barrier, then opaque/translucent/temporal drawing

These classes remain isolated and are not called by the existing OpenGL MDIC renderer. They still need a build, SPIR-V validation, and device testing. The raster visibility/culling stage and all Vulkan draw passes remain outstanding.
