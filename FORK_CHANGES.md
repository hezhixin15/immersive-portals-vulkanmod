# Fork changes

Unofficial fork of [Immersive Portals](https://github.com/iPortalTeam/ImmersivePortalsMod)
6.0.6 (Minecraft 1.21.1, Fabric), modified to run on VulkanMod without an OpenGL
context. Maintained by [hezhixin15](https://github.com/hezhixin15).

The mod id stays `immersive_portals` on purpose: this jar replaces the upstream
one and keeps existing worlds, configs and dependents working. Only the
non-OpenGL rendering path is changed; the OpenGL path is intended to behave as
upstream.

## What changed

- **Terrain bridge**: one VulkanMod terrain context (world renderer + chunk
  status table) per dimension; chunk load/unload events are forwarded to the
  router; the singleton `WorldRenderer.setLevel` is suppressed only for
  secondary worlds so vanilla `setLevel` still runs.
- **Portal rendering without OpenGL**: framebuffer-based portal renderer with
  per-portal targets, second-buffer compositing instead of stencil, oblique
  near-plane clipping instead of `glClipPlane`, and clear-colour save/restore
  to avoid magenta leaking into the swapchain.
- **Translucent sorting**: the sort task uses the camera carried by the task
  instead of VulkanMod's process-wide static camera, and sorting is cancelled
  inside portal passes (mirrors upstream's GL behavior).
- **Indirect draw**: the per-frame parameter buffer is reset once per renderer
  per Vulkan frame, so each portal pass appends at its own offset; this avoids
  a later pass overwriting parameters of a command already recorded.
- **Performance**: per-portal off-screen image cache with a budget of two live
  world renders per frame, and conservative portal-behind-portal occlusion
  culling (a portal fully inside a nearer portal's opening is skipped).
- **Water fix**: each terrain layer inside a portal re-binds the portal target
  at layer start, re-asserting the viewport the pass started with.
- **Diagnostics removed**: the temporary terrain probe and diagnostic classes
  are gone; no always-on reflection in hot paths.

## Files added

```
src/main/java/qouteall/imm_ptl/core/compat/mixin/vulkanmod/MixinVulkanModChunkStatusMap.java
src/main/java/qouteall/imm_ptl/core/compat/mixin/vulkanmod/MixinVulkanModClientPacketListener.java
src/main/java/qouteall/imm_ptl/core/compat/mixin/vulkanmod/MixinVulkanModDefaultMainPass.java
src/main/java/qouteall/imm_ptl/core/compat/mixin/vulkanmod/MixinVulkanModRenderer.java
src/main/java/qouteall/imm_ptl/core/compat/mixin/vulkanmod/MixinVulkanModSortTransparencyTask.java
src/main/java/qouteall/imm_ptl/core/compat/mixin/vulkanmod/MixinVulkanModWorldRenderer.java
src/main/java/qouteall/imm_ptl/core/compat/vulkanmod_compatibility/VulkanChunkStatusRouter.java
src/main/java/qouteall/imm_ptl/core/compat/vulkanmod_compatibility/VulkanModAccess.java
src/main/java/qouteall/imm_ptl/core/compat/vulkanmod_compatibility/VulkanTerrainBridge.java
src/main/java/qouteall/imm_ptl/core/render/ObliqueFrontClipping.java
src/main/java/qouteall/imm_ptl/core/render/renderer/PortalOcclusionCuller.java
src/main/java/qouteall/imm_ptl/core/render/renderer/PortalRenderCache.java
src/main/java/qouteall/imm_ptl/peripheral/mixin/common/nether_portal/MixinNetherPortalBlock_CVB.java
```

## Files modified

```
src/main/java/qouteall/imm_ptl/core/CHelper.java
src/main/java/qouteall/imm_ptl/core/ClientWorldLoader.java
src/main/java/qouteall/imm_ptl/core/chunk_loading/ImmPtlClientChunkMap.java
src/main/java/qouteall/imm_ptl/core/compat/IPCompatMixinPlugin.java
src/main/java/qouteall/imm_ptl/core/compat/RenderBackendCompat.java
src/main/java/qouteall/imm_ptl/core/mixin/client/render/MixinFrustum_FixDeadLoop.java
src/main/java/qouteall/imm_ptl/core/mixin/client/render/MixinGameRenderer.java
src/main/java/qouteall/imm_ptl/core/mixin/client/render/MixinLevelRenderer.java
src/main/java/qouteall/imm_ptl/core/platform_specific/IPConfig.java
src/main/java/qouteall/imm_ptl/core/render/CrossPortalViewRendering.java
src/main/java/qouteall/imm_ptl/core/render/MyGameRenderer.java
src/main/java/qouteall/imm_ptl/core/render/MyRenderHelper.java
src/main/java/qouteall/imm_ptl/core/render/renderer/PortalRenderer.java
src/main/java/qouteall/imm_ptl/core/render/renderer/RendererUsingFrameBuffer.java
src/main/resources/imm_ptl_compat.mixins.json
src/main/resources/imm_ptl_peripheral.mixins.json
```

## Files removed

```
src/main/java/qouteall/imm_ptl/core/compat/VulkanDiagnostics.java
```

## Build and repository files changed

```
build.gradle
gradle.properties
changelog.md
```

- `gradle.properties`: `mod_version` is `6.0.6-vulkan.1` rather than upstream's
  `6.0.6`, so this fork's jar name cannot collide with an upstream build. The
  upstream-only `git_branch` property was dropped along with the block that
  used it.
- `build.gradle`: the upstream `publishMods` block was removed. It pointed at
  the author's own CurseForge project (332273), Modrinth project (zJpHMkdD) and
  `iPortalTeam/ImmersivePortalsMod`, so a fork must not carry it. Nothing else
  in the build changed; the block was never wired into `build`, and the
  `from("LICENSE")` entry that embeds the license into the jar is kept.
- `changelog.md`: describes this fork's release, with the upstream 6.0.6 note
  retained below it.
- Upstream tooling that only served the author's own workflows was removed:
  `sync-crowdin.bat` (the author's Crowdin translation project) and the three
  `.github/DISCUSSION_TEMPLATE` forms, which ask reporters to phrase issues
  against upstream's supported configurations.

## Fork-only files

```
LICENSE
NOTICE
FORK_CHANGES.md
README.md
README.zh-CN.md
README.zh-TW.md
```

`LICENSE` is upstream's Apache-2.0 text, byte for byte. `NOTICE`, this file and
the three READMEs are written by this fork.
