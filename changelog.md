## 6.0.6-vulkan.1 (unofficial VulkanMod fork)

Based on upstream 6.0.6. Changes in this fork:

- Portal rendering runs on VulkanMod with no OpenGL context: per-dimension terrain renderer
  bridge, chunk status routing, framebuffer compositing without stencil, oblique near-plane
  clipping instead of `glClipPlane`.
- Translucent layers are re-sorted per portal view, and the in-portal water/translucent layer
  is no longer mirrored (VulkanMod encodes the Y flip in the viewport height sign).
- Indirect draw is kept across portal passes instead of falling back to the direct path.
- Per-portal off-screen image cache: at most two portals run a full world render per frame,
  the rest composite their previous image.
- A portal completely hidden behind a nearer portal is skipped instead of costing a full
  world render.
- The temporary terrain probes and diagnostic classes are gone; no always-on reflection in
  hot paths.
- The OpenGL path is unchanged.

---

## 6.0.6 (upstream)

Changed Sodium compat to Sodium 0.6.0 and Iris compat to 1.8.0. Note that this version is experimental.
