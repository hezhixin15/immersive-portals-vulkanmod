# Immersive Portals — VulkanMod Fork

<p align="center">
  <strong>English</strong> &nbsp;|&nbsp; <a href="README.zh-CN.md"><strong>简体中文</strong></a> &nbsp;|&nbsp; <a href="README.zh-TW.md"><strong>繁體中文</strong></a>
</p>

> **Unofficial fork. Not the official Immersive Portals repository.**
>
> Fork of [iPortalTeam/ImmersivePortalsMod](https://github.com/iPortalTeam/ImmersivePortalsMod)
> (Immersive Portals 6.0.6, Minecraft 1.21.1, Fabric), modified to render see-through portals
> on **VulkanMod** without an OpenGL context. Maintained by
> [hezhixin15](https://github.com/hezhixin15).
>
> Not affiliated with, endorsed by, or supported by qouteall, iPortalTeam, or the VulkanMod
> project. Report fork-specific bugs to
> [this repository's issues](https://github.com/hezhixin15/immersive-portals-vulkanmod/issues),
> not upstream.

**Download:** [latest release](https://github.com/hezhixin15/immersive-portals-vulkanmod/releases/latest)
— every published jar is built from the commit its tag points at, so the source here and the
binary are the same thing. Building it yourself is described under [Building](#building).

## Requirements

| | |
|---|---|
| Minecraft | 1.21.1 |
| Mod loader | Fabric Loader 0.16.2+ |
| Dependencies | Fabric API (1.21.1), [VulkanMod](https://github.com/xCollateral/VulkanMod) 0.6.7 (tested version) |
| Java | 21+ |

Sodium / Iris / OptiFine are **not** supported and must not be installed alongside VulkanMod.

## Installation

1. Remove any existing Immersive Portals jar from `mods/`. The mod id is still
   `immersive_portals`, so the upstream jar and this fork cannot coexist.
2. Drop the fork jar and VulkanMod into `mods/`.
3. Launch with the Vulkan renderer selected.

## What changed compared to upstream 6.0.6

- **Terrain rendering bridge** — VulkanMod's world renderer is driven per dimension, with
  chunk-status routing, so secondary worlds (portal destinations) can be rendered without
  an OpenGL context.
- **Portal rendering on Vulkan** — portals are still drawn into off-screen framebuffers, but
  every GL call the portal renderer depends on was replaced or bridged for Vulkan.
- **Translucent layer ordering inside portal passes** — transparency is re-sorted per portal
  view instead of being skipped.
- **Indirect draw support** — draw parameters are appended per portal pass instead of being
  forced onto the direct path.
- **Per-portal image cache** — each visible portal owns an off-screen target. At most two
  portals run a full world render per frame; the rest composite their previous image
  (refreshed after 250 ms + 40 ms per slot, on portal state change, or when the portal covers
  more than half the screen).
- **Occlusion culling** — on Vulkan there are no occlusion queries, so a portal that is
  completely hidden behind a nearer portal's opening is skipped instead of costing a full
  world render. Only the conservative case is culled; anything partially visible is still
  rendered.
- **In-portal water / translucent fix** — VulkanMod encodes the Y flip in the sign of the
  viewport height; portal passes now re-assert the viewport instead of inheriting an
  unflipped one.
- The OpenGL path is untouched. If you play without VulkanMod, use the upstream jar.

## Known limitations

- Only VulkanMod 0.6.7 on Minecraft 1.21.1 has been tested.
- Cached portal images can lag 250–370 ms while the camera moves; they snap in once you stop.
- The image cache is bypassed for the GUI portal view and for cross-portal views.
- Every *live* portal still costs one full world render. With several open portals the frame
  rate drops roughly linearly in the number of live portals, not in the number of visible
  portals.
- The water fix and the occlusion culling are verified structurally, not across every
  dimension/driver combination. Please report anomalies with a log attached.

## Building

```bash
./gradlew build
```

The jar is written to `build/libs/`. VulkanMod is **not** a compile-time dependency:
everything that touches it goes through reflection and a mixin plugin that only applies when
a non-OpenGL renderer is present.

## License

Licensed under the [Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for attribution of
the upstream project and the non-affiliation statement; [FORK_CHANGES.md](FORK_CHANGES.md)
lists every file added, modified or removed by this fork.

Modified upstream files carry a notice at the top of the file, as required by
Apache-2.0 section 4(b). Some files contain code adapted from Minecraft (© Mojang Studios)
and some bundled assets are derived from Minecraft textures; those are not relicensed by
Apache-2.0.

## Credits

- Immersive Portals, by qouteall and contributors —
  [upstream repository](https://github.com/iPortalTeam/ImmersivePortalsMod) ·
  [wiki](https://qouteall.fun/immptl/wiki/) · [Modrinth](https://modrinth.com/mod/immersiveportals)
- VulkanMod, by xCollateral — [repository](https://github.com/xCollateral/VulkanMod)

## AI involvement

The rendering-API adaptation work in this fork — porting portal rendering off OpenGL and onto
VulkanMod — was done with AI assistance: an AI agent helped read and analyse both codebases,
draft and refactor code, and trace rendering bugs. Every piece of AI-generated code was
reviewed by the human maintainer before it was committed, and the remaining issues are the
maintainer's responsibility.
