# GoGame 界面素材（2026-10-01）

本轮采用深青玉背景、暖木棋盘与亮面黑白棋子。应用图标按用户游戏截图反馈，改为与 MCphone 原生应用一致的 20×20 像素风格。大厅沿用 MCphone 的 PhoneStyle，因此能跟随手机主题；对局界面使用固定的深色棋室配色。

## 最终素材

背景和棋子使用 Codex 内置 image_gen 工具生成新图（generate 模式），保留其原始透明通道。当前应用图标以代码原生像素绘制，采用 20×20 网格、16 色调色板与全透明/全不透明像素，避免高清图缩小后的模糊轮廓。所有资源保存在项目中，运行时不依赖用户的 Codex 目录。

| 用途 | 项目文件 | 分辨率 |
| --- | --- | --- |
| 对局背景 | [backdrop.png](../gogame-1.21.1/src/main/resources/assets/gogame/textures/ui/backdrop.png) | 1672×941 |
| 黑棋子 | [stone_black.png](../gogame-1.21.1/src/main/resources/assets/gogame/textures/ui/stone_black.png) | 1254×1254 |
| 白棋子 | [stone_white.png](../gogame-1.21.1/src/main/resources/assets/gogame/textures/ui/stone_white.png) | 1254×1254 |
| 手机桌面 / 商店 / 页面图标 | [go-v2.png](../gogame-1.21.1/src/main/resources/assets/gogame/textures/app/go-v2.png) | 20×20 |
| NeoForge 模组列表 Logo | [gogame-logo.png](../gogame-1.21.1/src/main/resources/gogame-logo.png) | 20×20 |

`gogame-logo.png` 是应用图标的同内容副本。NeoForge 1.21.1 的 [logoFile 文档](https://docs.neoforged.net/docs/1.21.1/gettingstarted/modfiles/#mod-specific-properties) 要求 Logo 位于 JAR 根目录，因此在资源根目录单独保留。当前设置 logoBlur=false，以保持像素边缘。

背景和棋子的 `.png.mcmeta` 启用 blur/clamp；应用图标启用 clamp、关闭 blur，以最近邻采样缩放。GoArt 按整幅图的 UV 0–1 绘制；GoUi 负责木框、网格、星位、阴影和按钮。棋盘交叉点与命中坐标仍沿用 19×19 的既有算法。

旧的 `textures/app/go.png` 保留，GoIcon 仍引用 `go-v2.png`，当前内容为像素版；资源路径保持一致，已打开的开发客户端可通过 F3+T 重新加载资源。先前高清图标留存在 [go-hd.png](ui-asset-archive/go-hd.png)，不进入模组 JAR。

像素图标的可编辑源是 [GeneratePixelIcon.java](../gogame-1.21.1/tools/GeneratePixelIcon.java)。在 `gogame-1.21.1/` 内使用 Java 运行此文件，可重新输出应用 PNG、同内容的根目录 Logo、关闭平滑的 metadata 和放大预览。它使用阶梯圆角、暖木底、内缩棋盘和同尺寸的黑白棋子；棋子关于画布中心对称。20 像素为偶数，五条网格线中的中线在栅格上保留半像素偏差。

## 样式预览与验证范围

[对局预览](ui-preview/board.png)、[小屏对局预览](ui-preview/board-small.png)、[深色大厅](ui-preview/lobby-dark.png)、[浅色大厅](ui-preview/lobby-light.png)。

[像素图标放大预览](ui-preview/icon-pixel.png) 与 [原生应用风格对比](ui-preview/icon-pixel-strip.png)；对比条中的原生图标取自实际依赖 `mcphone-1.9.3.jar`，仅用于文档预览，不打包进附属模组。

这些是本地组件渲染预览，**不是 Minecraft 截图**。预览通过 AWT 适配器调用生产 GoUi / GoArt / GoIcon，使用示例棋局、面板布局和手机主题。字体用 Microsoft YaHei 代替 Minecraft 字库；实际字体、悬停、键盘焦点和 OpenGL 混合需要游戏内验收。

已完成 Wrapper 的离线 build、101 项既有测试、JAR 内资源/元数据检查，以及开发客户端启动日志检查（GoGame setup、MCphone SPI 注册、资源重载完成）。本轮没有进入双人对局，也没有调用用户的 AI API。

## 最终生成提示词

每项独立调用内置工具；未传入参考图。以下为实际使用的 prompt 和透明设置。

### background

transparent_background: false

```text
Use case: stylized-concept. Create a polished landscape background texture for a Minecraft Go/Weiqi board game screen, 16:9 landscape. Deep charcoal and muted dark jade, subtle natural woven-paper / linen fibers, very faint warm ambient glow around the left-middle where a wooden board UI will be overlaid, quiet dark right side for a player information panel. Elegant restrained tactile game UI art, low contrast, evenly composed, full bleed. No visible board, no grid, no stones, no furniture, no text, no symbols, no logos, no border. The image is an actual background asset, not a UI mockup. Background opaque.
```

### icon（先前高清版本，已归档）

transparent_background: true

```text
Use case: logo-brand. Create one finished square rounded-corner app tile icon for a Go/Weiqi game app. Warm honey-colored fine-grain wood rounded square, entirely front-facing straight orthographic view. Centered simple 5 by 5 dark brown thin-line Go grid with exactly equal margins on all four sides. One glossy obsidian black Go stone on an upper-left interior intersection and one pearl-white Go stone on the symmetric lower-right interior intersection. Stones have subtle curved highlights and small soft shadows, but instantly recognizable at 20 pixels. Tasteful premium game icon, clean balanced silhouette, minimal detail, luminous warm wood with restrained depth. Tile fills 94 percent of square canvas, perfectly centered, corners rounded equally. No text, letters, logos, outer shadow outside the tile, perspective or extra objects. Outside the rounded square must be genuinely transparent.
```

### stone_black

transparent_background: true

```text
Use case: stylized-concept. One black Go / Weiqi playing stone game sprite, centered on a square transparent canvas. Exact circular silhouette viewed from directly overhead, flattened convex polished obsidian lens, very dark charcoal-black body, broad subtle curved soft gray highlight at upper-left, dark rim with smooth antialiasing, slight rounded volume. Simple elegant game UI sprite readable at 8 to 32 pixels, not a realistic photograph with busy reflections. Circle occupies exactly about 90 percent of image width and height, equal margins, no perspective ellipse, no ground plane, no cast shadow outside the circle, no text or other objects. Background genuinely transparent.
```

### stone_white

transparent_background: true

```text
Use case: stylized-concept. One white Go / Weiqi playing stone game sprite, centered on a square transparent canvas. Exact circular silhouette viewed from directly overhead, flattened convex polished pearl / porcelain lens, warm ivory-white body, broad subtle curved white highlight at upper-left and soft warm-gray shading toward lower-right, delicately defined warm-gray rim with smooth antialiasing, slight rounded volume. Simple elegant game UI sprite readable at 8 to 32 pixels, not a realistic photograph with busy reflections. Circle occupies exactly about 90 percent of image width and height, equal margins, no perspective ellipse, no ground plane, no cast shadow outside the circle, no text or other objects. Background genuinely transparent.
```
