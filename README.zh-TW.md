# Immersive Portals — VulkanMod 分支

<p align="center">
  <a href="README.md"><strong>English</strong></a> &nbsp;|&nbsp; <a href="README.zh-CN.md"><strong>简体中文</strong></a> &nbsp;|&nbsp; <strong>繁體中文</strong>
</p>

> **非官方分支，不是 Immersive Portals 官方倉庫。**
>
> 本倉庫是 [iPortalTeam/ImmersivePortalsMod](https://github.com/iPortalTeam/ImmersivePortalsMod)
> 的分支，基於 Immersive Portals 6.0.6（Minecraft 1.21.1 / Fabric），改造後可以在
> **VulkanMod** 上正常顯示傳送門畫面，不再依賴 OpenGL。由
> [hezhixin15](https://github.com/hezhixin15) 維護，與 qouteall、iPortalTeam 以及 VulkanMod
> 專案均無隸屬關係，也未獲得其認可或支援。分支本身的問題請提到
> [本倉庫的 issues](https://github.com/hezhixin15/immersive-portals-vulkanmod/issues)，不要提到上游。

**下載：**[最新版本](https://github.com/hezhixin15/immersive-portals-vulkanmod/releases/latest)
—— 發布頁裡每個 jar 都是用它的 tag 所指的那次提交建置的，所以這裡的原始碼和二進位是同一份東西；
想自己編的話看下面的「建置」。

<img width="2560" height="1440" alt="image" src="https://github.com/user-attachments/assets/6cff330c-d268-46a1-a77d-99a5d29ff1e1" />


## 執行需求

| | |
|---|---|
| Minecraft | 1.21.1 |
| 載入器 | Fabric Loader 0.16.2 以上 |
| 前置模組 | Fabric API（1.21.1）、[VulkanMod](https://github.com/xCollateral/VulkanMod) 0.6.7（實測版本） |
| Java | 21 以上 |

**不支援** Sodium / Iris / OptiFine，它們也不能和 VulkanMod 同時安裝。

## 安裝

1. 先把 `mods/` 裡已有的 Immersive Portals 刪掉。mod id 仍然是 `immersive_portals`，
   上游包和本分支不能共存。
2. 把本分支的 jar、Fabric API 和 VulkanMod 一起放進 `mods/`。
3. 啟動時選擇 Vulkan 渲染後端。

## 相比上游 6.0.6 改了什麼

- **地形渲染橋**——依維度各驅動一份 VulkanMod 世界渲染器，並對區塊載入狀態做路由，
  這樣沒有 OpenGL 脈絡也能渲染門後的世界。
- **Vulkan 下的傳送門渲染**——門畫面仍然畫進離屏幀緩衝，但門渲染器所依賴的每一處 GL 呼叫
  都做了 Vulkan 側的取代或橋接。
- **門內半透明排序**——透明層依每個門的視角重新排序，而不是直接跳過。
- **indirect draw**——繪製參數依各個門的 pass 分別追加，不再被強制退回 direct 路徑。
- **每門快取圖**——每個可見的門各自持有一張離屏目標。每幀最多兩個門跑完整的世界渲染，
  其餘門合成上一次的舊圖（超過 250 ms + 每個槽位 40 ms、門狀態變化、或門佔螢幕超過一半時
  強制更新）。
- **遮擋剔除**——Vulkan 後端沒有遮擋查詢，所以當一個門完全被更近的門擋住時直接跳過，
  不再為它跑一整趟世界渲染。只剔除這種絕對安全的情況，只要有一點點可見就照常渲染。
- **門內水面修復**——VulkanMod 把 Y 軸翻轉寫在視埠高度的正負號裡，門內 pass 現在會重新
  宣告自己開始時的視埠，而不是繼承一個未翻轉的。
- OpenGL 路徑完全沒動。不用 VulkanMod 的話請直接用原作者的包。

## 已知限制

- 只在 Minecraft 1.21.1 + VulkanMod 0.6.7 上驗證過。
- 快取的門畫面在鏡頭移動時最多延遲 250–370 ms，停下來後會自動變清晰。
- GUI 裡的門視圖和跨門視圖不走快取。
- 每個**即時**的門仍然要跑一整趟世界渲染。同時開著的門越多幀率越低，成本取決於即時的門數，
  不是螢幕上可見的門數。
- 水面修復和遮擋剔除只做了結構性驗證，沒涵蓋所有維度／顯示卡驅動組合。遇到異常請附記錄檔提 issue。

## 建置

```bash
./gradlew build
```

產物在 `build/libs/`。VulkanMod **不是**編譯期相依：所有涉及它的程式碼都走反射，
以及一個只在非 OpenGL 渲染器存在時才生效的 mixin 外掛。

## 授權條款

採用 [Apache License 2.0](LICENSE)。上游歸屬與「無隸屬關係」聲明見
[NOTICE](NOTICE)；本分支新增、修改、刪除的每個檔案列在 [FORK_CHANGES.md](FORK_CHANGES.md)。

依 Apache-2.0 第 4(b) 條的要求，改動過的上游檔案在檔案開頭都加了改動聲明。部分檔案含有
改編自 Minecraft（© Mojang Studios）的程式碼，部分內建材質源自 Minecraft 貼圖，這些內容
不受 Apache-2.0 再授權。

## 致謝

- Immersive Portals，作者 qouteall 及貢獻者 ——
  [上游倉庫](https://github.com/iPortalTeam/ImmersivePortalsMod) ·
  [Wiki](https://qouteall.fun/immptl/wiki/) · [Modrinth](https://modrinth.com/mod/immersiveportals)
- VulkanMod，作者 xCollateral —— [倉庫](https://github.com/xCollateral/VulkanMod)

## AI 參與說明

本分支更換渲染 API 的工作（把傳送門渲染從 OpenGL 移植到 VulkanMod）有 AI 參與並提供協助：
AI 協助閱讀與分析兩邊程式碼、起草與重構實作、排查渲染問題。AI 產生的程式碼在提交前，人工
都完整審過一遍；倉庫裡遺留的問題由維護者負責。
