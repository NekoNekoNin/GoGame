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

### 2.4 AI 对接（OpenAI 兼容协议，Phase 4 已实现）
- **传输**：JDK 自带 `java.net.http.HttpClient`（异步），不引第三方库。**围棋 PVE 用非流式**（`stream=false`）——一手棋回复就几十字符，一次取回整份 JSON 比 SSE 逐字更稳（兼容服务的 SSE 实现五花八门）；范本 mcphone-deepseek 是流式聊天，两者取舍不同
- **线程模型**：cached 守护线程池（阻塞读体不饿死协议任务）；`thenAcceptAsync(..., POOL)` 避开 ForkJoinPool.commonPool
- **线程隔离**：`AiTurn` 模式——HTTP 线程只写 AiTurn（volatile String content/errorKey + State 枚举），主线程只读；错误存**无参翻译键**非句子（I18n 只在渲染线程）
- **超时**：CONNECT 20s / REQUEST 60s（对局不能像聊天等几分钟，超时即失败走兜底着）；MAX_TOKENS 128 省钱闸
- **提供者**（`AiProvider`）：自定义 CUSTOM 优先；预设 GPT / 通义千问 QWEN / DeepSeek / 智谱 ZHIPU（均 OpenAI 兼容 `/chat/completions`，默认模型挑各家快而便宜档）
- **难度**（`AiDifficulty`）：**11 档**（菜鸟/普通人/初段-九段），三旋钮 = 英文 persona + temperature（1.0→0.50）+ blunderRate（弱档扰动、段位档 0）
- **prompt**（`GoPromptBuilder`）：**纯文本、绝不截图给多模态**——system 人设+严格 JSON 格式，user = 局面说明 + SGF 序列 + ASCII 19×19 点阵（零识别误差、不绑视觉模型、省 token）
- **解析**（`MoveParser`）：三层宽容（JSON `{"move":…}` → 文本 GTP 正则[只认大写] → `\bpass\b` 词边界）；AI 不许 resign；解析失败/非法着带反馈重试 3 次→随机合法着兜底
- **取消**：关流（服务端真停手），非 future.cancel()

### 2.5 配置与存储
- **不用 ModConfigSpec**：Gson 明文 JSON 存 `FMLPaths.CONFIGDIR/gogame/config.json`（用 FMLPaths 而非相对路径 `config/…`，防自定义 gameDir / 第三方启动器下漂移），手机屏上改完当场生效；`get()` 懒加载 + `sanitized()`（枚举 null 回默认、空地址/模型回预设）；setter 即时 tmp+ATOMIC_MOVE 落盘
- **Key 安全三件实事**：界面只显示末四位（`maskedApiKey` 固定六点）、日志不写、只发给服务地址
- **文件分离**：配置与数据分文件（性质/写频率不同）

### 2.6 对弈架构（已确认决策）
- **PVP 服务端仲裁**：落子合法性、打劫、数子均由服务端权威判定（主模组客户端+服务端都装）
- **PVE 客户端本地推演**（Phase 4）：`LocalAiGame` 在客户端主线程跑**与服务端同一套** `GoRules` 引擎，每手结果本地馈送进 `GoClientCache`（roomId="PVE"），大厅/棋盘界面照常从缓存读、**不为 PVE 另写渲染**；与联机房间互斥
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
