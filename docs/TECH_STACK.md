# 技术文档

> 记录本项目使用的技术栈与相关仓库链接。新增技术时同步更新本文件。

---

## 1. 项目仓库与参考仓库

| 仓库 | 链接 | 角色 |
|---|---|---|
| gogame-1.21.1（本项目） | 本地 `f:\Qoder MOD Creater\gogame-1.21.1` | 围棋 App 附属模组（开发中，基于 NeoForge MDK） |
| MCphone（主模组） | https://github.com/november521/mcphone | 手机框架本体，提供 App SPI；本地克隆于 `f:\Qoder MOD Creater\mcphone` |
| MCphone-DeepSeek（附属范本） | https://github.com/november521/mcphone-deepseek | 官方附属模组范例，围棋 App 的直接抄作业对象；本地克隆于 `f:\Qoder MOD Creater\mcphone-deepseek` |

外部 GUI 库调查（均判定不适用，仅存档）：

| 库 | 链接 | 不适用理由 |
|---|---|---|
| SpruceUI | https://github.com/LambdAurora/SpruceUI | Fabric 系，本项目 NeoForge |
| Sunscreen | https://github.com/Aizistral/Sunscreen | Fabric 系 |
| ModernUI-MC | https://github.com/BloCamLimb/ModernUI | 重型前置模组，与手机内 120×200 场景不匹配 |
| Elementa | https://github.com/EssentialGG/Elementa | 主要面向老版本，1.21.1 适配风险高 |

---

## 2. 核心技术栈

### 2.1 平台与构建
- **Minecraft 1.21.1 + NeoForge**（唯一版本目标；Forge 1.20.1 待用户提起再做）
- **Gradle 9.2.1**（Wrapper），ModDevGradle 插件，`neoforge.mods.toml` 模板
- **Java**：运行 Gradle 用 JDK 25（`F:\JAVA\Java25`，用户级 JAVA_HOME + `org.gradle.java.home` 双保险）；编译 toolchain 21 由 foojay 自动下载至 `~/.gradle/jdks`
- **代理**：Clash Verge（Mihomo）混合端口 `127.0.0.1:7897`；生效配置在用户目录 `C:\Users\29079\.gradle\gradle.properties`（systemProp.http/https.proxy*）；项目内 gradle.properties 仅注释模板（防破坏 CI）
- **git 代理**：per-URL 全局配置（github.com / raw / gist → 127.0.0.1:7897）

### 2.2 MCphone App SPI（客户端专用）
- **Java SPI 注册**：`META-INF/services/com.november.mcphone.api.client.app.IPhoneApp` 登记实现类；主模组 ServiceLoader 自动发现，附属无需被本体感知
- **接口集**：`IPhoneApp`（主屏格子）、`IPhonePage`（页面）、`PhoneCanvas`（绘制上下文，`clipped()` 裁剪）、`PhoneStyle`（ARGB 主题配色）、`MCphoneApi.VERSION=2`（静态块赋值防内联）
- **商店 SPI**（备用）：`IAppSource` / `AppInfo`；**付费**（备用）：cost 包（ICost/ItemCost/EmcCost）
- 详见 `mcphone-api-ui-reference.md`

### 2.3 GUI 方案（现成三层知识，零第三方依赖）
1. **手机内页**（首选）：IPhonePage + PhoneCanvas，120×200 逻辑像素，缩放由主模组处理
2. **UI 迷你框架模式**（抄附属）：`View` 接口 + `Theme` record（PhoneStyle 快照）+ `Ui` 静态工具（roundRect/circle/hit/truncate/滚动条），纯 GuiGraphics 手绘
3. **原版 Screen**（备用）：`opensInsidePhone()=false` 自开全屏

### 2.4 AI 对接（OpenAI 兼容协议）
- **传输**：JDK 自带 `java.net.http.HttpClient`（异步 + SSE 流式 + 可中途关流），不引第三方库
- **线程模型**：cached 守护线程池（阻塞读流不饿死协议任务）；`thenAcceptAsync(..., POOL)` 避开 ForkJoinPool.commonPool
- **线程隔离**：Turn 模式——HTTP 线程只写 Turn（StringBuffer + volatile + AtomicInteger revision），渲染线程只读；错误存翻译键非句子
- **提供者**：自定义 API 地址优先；预设顺序 GPT → 通义千问 → 深度求索 → 智谱清言（均 OpenAI 兼容 `/chat/completions`）
- **取消**：关流（服务端真停手），非 future.cancel()

### 2.5 配置与存储
- **不用 ModConfigSpec**：Gson 明文 JSON 存 `config/<modid>/`，手机屏上改完当场生效；`get()` 懒加载 + `sanitized()`（Math.clamp 拉回合法区间）
- **Key 安全三件实事**：界面只显示末四位、日志不写、只发给服务地址
- **文件分离**：配置与数据分文件（性质/写频率不同）

### 2.6 对弈架构（已确认决策）
- **服务端仲裁**：落子合法性、打劫、数子均由服务端权威判定（主模组客户端+服务端都装）
- **规则**：19 路 + 中国规则（数子法、禁自杀、打劫）

### 2.7 开发流程工具
- **代码审查子智能体**：`.qoder/agents/code-reviewer.md`，tools 仅 Read/Grep/Glob（物理只读），主程序员写码后送审
- **长期记忆**：两仓库信息、围棋构想、用户行为规定（不明确处必须提问）均已入库
- **CI**：GitHub Actions（`.github/workflows/build.yml`，MDK 自带）

---

## 3. 关键文档索引

| 文档 | 位置 | 用途 |
|---|---|---|
| 接口参考手册 | `mcphone-api-ui-reference.md` | 两仓库重要接口 + GUI 知识速查 |
| 项目记录 | `docs/PROJECT_LOG.md` | 实时修改/重大更新日志 |
| 踩坑记录 | `docs/PITFALLS.md` | 错误与修复，未来踩坑先查这里 |
