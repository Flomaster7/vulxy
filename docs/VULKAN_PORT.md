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
