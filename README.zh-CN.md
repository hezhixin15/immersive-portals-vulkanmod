# Immersive Portals — VulkanMod 分支

<p align="center">
  <a href="README.md"><strong>English</strong></a> &nbsp;|&nbsp; <strong>简体中文</strong> &nbsp;|&nbsp; <a href="README.zh-TW.md"><strong>繁體中文</strong></a>
</p>

> **非官方分支，不是 Immersive Portals 官方仓库。**
>
> 本仓库是 [iPortalTeam/ImmersivePortalsMod](https://github.com/iPortalTeam/ImmersivePortalsMod)
> 的分支，基于 Immersive Portals 6.0.6（Minecraft 1.21.1 / Fabric），改造后可以在
> **VulkanMod** 上正常显示传送门画面，不再依赖 OpenGL。由
> [hezhixin15](https://github.com/hezhixin15) 维护，与 qouteall、iPortalTeam 以及 VulkanMod
> 项目均无隶属关系，也未获得其认可或支持。分支本身的问题请提到
> [本仓库的 issues](https://github.com/hezhixin15/immersive-portals-vulkanmod/issues)，不要提到上游。

**下载：**[最新版本](https://github.com/hezhixin15/immersive-portals-vulkanmod/releases/latest)
—— 发布页里每个 jar 都是用它的 tag 所指的那次提交构建的，所以这里的源码和二进制是同一份东西；
想自己编的话看下面的「构建」。

<img width="2560" height="1440" alt="image" src="https://github.com/user-attachments/assets/869768a7-0cb3-469a-ba80-bc319be5778b" />


## 运行要求

| | |
|---|---|
| Minecraft | 1.21.1 |
| 加载器 | Fabric Loader 0.16.2 及以上 |
| 前置 | Fabric API（1.21.1）、[VulkanMod](https://github.com/xCollateral/VulkanMod) 0.6.7（实测版本） |
| Java | 21 及以上 |

**不支持** Sodium / Iris / OptiFine，它们也不能和 VulkanMod 同时安装。

## 安装

1. 先把 `mods/` 里已有的 Immersive Portals 删掉。mod id 仍然是 `immersive_portals`，
   上游包和本分支不能共存。
2. 把本分支的 jar、Fabric API 和 VulkanMod 一起放进 `mods/`。
3. 启动时选择 Vulkan 渲染后端。

## 相比上游 6.0.6 改了什么

- **地形渲染桥**——按维度各驱动一份 VulkanMod 世界渲染器，并对区块加载状态做路由，
  这样没有 OpenGL 上下文也能渲染门后的世界。
- **Vulkan 下的传送门渲染**——门画面仍然画进离屏帧缓冲，但门渲染器依赖的每一处 GL 调用
  都做了 Vulkan 侧的替代或桥接。
- **门内半透明排序**——透明层按每个门的视角重新排序，而不是直接跳过。
- **indirect draw**——绘制参数按各个门的 pass 分别追加，不再被强制退回 direct 路径。
- **每门缓存图**——每个可见的门各自持有一张离屏目标。每帧最多两个门跑完整的世界渲染，
  其余门合成上一次的旧图（超过 250 ms + 每个槽位 40 ms、门状态变化、或门占屏超过一半时
  强制刷新）。
- **遮挡剔除**——Vulkan 后端没有遮挡查询，所以当一个门完全被更近的门挡住时直接跳过，
  不再为它跑一整趟世界渲染。只剔除这种绝对安全的情况，只要有一点点可见就照常渲染。
- **门内水面修复**——VulkanMod 把 Y 轴翻转写在视口高度的正负号里，门内 pass 现在会重新
  断言自己开始时的视口，而不是继承一个未翻转的。
- OpenGL 路径完全没动。不用 VulkanMod 的话请直接用原作者的包。

## 已知限制

- 只在 Minecraft 1.21.1 + VulkanMod 0.6.7 上验证过。
- 缓存的门画面在镜头移动时最多滞后 250–370 ms，停下来后会自动变清晰。
- GUI 里的门视图和跨门视图不走缓存。
- 每个**实时**的门仍然要跑一整趟世界渲染。同时开着的门越多帧率越低，成本取决于实时的门数，
  不是屏幕上可见的门数。
- 水面修复和遮挡剔除只做了结构性验证，没覆盖所有维度/显卡驱动组合。遇到异常请附日志提 issue。

## 构建

```bash
./gradlew build
```

产物在 `build/libs/`。VulkanMod **不是**编译期依赖：所有涉及它的代码都走反射，
以及一个只在非 OpenGL 渲染器存在时才生效的 mixin 插件。

## 许可证

采用 [Apache License 2.0](LICENSE)。上游归属与「无隶属关系」声明见
[NOTICE](NOTICE)；本分支新增、修改、删除的每个文件列在 [FORK_CHANGES.md](FORK_CHANGES.md)。

按 Apache-2.0 第 4(b) 条的要求，改动过的上游文件在文件开头都加了改动声明。部分文件含有
改编自 Minecraft（© Mojang Studios）的代码，部分内置材质源自 Minecraft 贴图，这些内容
不受 Apache-2.0 再授权。

## 致谢

- Immersive Portals，作者 qouteall 及贡献者 ——
  [上游仓库](https://github.com/iPortalTeam/ImmersivePortalsMod) ·
  [Wiki](https://qouteall.fun/immptl/wiki/) · [Modrinth](https://modrinth.com/mod/immersiveportals)
- VulkanMod，作者 xCollateral —— [仓库](https://github.com/xCollateral/VulkanMod)

## AI 参与说明

本分支更换渲染 API 的工作（把传送门渲染从 OpenGL 移植到 VulkanMod）有 AI 参与并提供帮助：
AI 协助阅读和分析两边代码、起草与重构实现、排查渲染问题。AI 生成的代码在提交前，人工都
完整审核过一遍；仓库里遗留的问题由维护者负责。
