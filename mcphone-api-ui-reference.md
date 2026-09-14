# MCphone 接口与 UI 参考手册

> 面向本项目（mcphone 围棋附属模组）整理的两仓库重要接口速查。
> 来源：`https://github.com/november521/mcphone`（主模组）、`https://github.com/november521/mcphone-deepseek`（附属范本）。
> 本地克隆：`f:\Qoder MOD Creater\mcphone`、`f:\Qoder MOD Creater\mcphone-deepseek`。
> 文档唯一真源是主模组 wiki；本文件是开发期速查快照，接口以源码 javadoc 为准。

---

## 1. 主模组 App SPI（做附属 App 必须掌握的）

所有接口位于 `com.november.mcphone.api` 包（主模组承诺：包名即 API，api 包外不保证兼容）。
**全部是客户端专用**——签名里带 `GuiGraphics`，实现类在专用服务器上加载即崩。

### 1.1 IPhoneApp — 手机主屏上的一格 App

`api/client/app/IPhoneApp.java`。通过 **Java SPI** 注册：
`META-INF/services/com.november.mcphone.api.client.app.IPhoneApp` 里写一行实现类全名。

| 方法 | 说明 |
|---|---|
| `ResourceLocation getId()` | App 唯一 id，**命名空间必须用自己的 modid**（如 `gogame:go`）。撞车后果：后登记的被静默丢弃，App 凭空消失且无日志 |
| `Component getDisplayName()` | 主屏图标下的名字，用 `Component.translatable` |
| `ResourceLocation getIconTexture()` | 图标贴图（如 `assets/<modid>/textures/app/xxx.png`） |
| `void renderIcon(GuiGraphics, x, y, size, partialTick)` | 每帧调用；不覆盖则只画 `getIconTexture()` |
| `void onPress()` | 点击回调；覆盖了 `openPage()` 就不会被调 |
| `IPhonePage openPage()` | 返回页面实例；返回 `null` 走 `onPress()`。**建议懒建 + 复用同一实例**（草稿/滚动位置跨开关机保留，见 DeepSeekApp 范本） |
| `boolean opensInsidePhone()` | 默认 `true` 在手机内 120×200 打开；`false` 则 App 自开全屏 Screen |
| `int getBadgeCount()` | 每帧调用（有限流），图标角标数 |
| `void onUninstall()` | 卸载清理钩子 |
| `boolean isSystemApp()` | 系统 App 不可卸载 |
| `List<RequiredMod> requiredMods()` / `companionMods()` | 缺前置时商店显示"需要 XX 模组" |
| `boolean isAvailable()` | 不可用时图标置灰 |
| `boolean isPreinstalled()` | 是否预装 |
| `String getVersion()` / `getAuthor()` / `getDescription()` | 商店详情页；`getDescription()` 建议先 `I18n.exists(key)` 再取，查不到返回 `""` |

**禁止继承主模组内建的 `PhoneApp` 基类**（命名空间写死 `mcphone`，文档明确要求别碰）。

### 1.2 IPhonePage — App 打开后的页面

`api/client/ui/IPhonePage.java`。

| 方法 | 说明 |
|---|---|
| `void render(PhoneCanvas canvas)` | **唯一必须实现**。每帧画整页 |
| `boolean mouseClicked(double mx, double my, int button)` | 坐标为页内相对坐标；返回 true 表示已消费 |
| `boolean mouseScrolled(double mx, double my, double amount)` | 滚轮 |
| `boolean keyPressed(int keyCode, int scanCode, int modifiers)` / `charTyped(char, int)` | 键盘 |
| `boolean capturesKeyboard()` | **页面有输入框时必须返回 true**，否则背包键 E 等会被手机吃掉/误关手机 |
| `void onBack()` / `onOpen()` / `onClose()` | 导航栏返回键 / 页面打开 / 关闭钩子 |

- ESC 统一是"关机"，**不可拦截**。
- 所有回调主模组都有兜底 try/catch，抛异常不会崩游戏，但功能会哑掉。

### 1.3 PhoneCanvas — 页面绘制上下文

`api/client/ui/PhoneCanvas.java`。render 的唯一入参。

| 方法 | 说明 |
|---|---|
| `GuiGraphics graphics()` / `Font font()` | 原版绘制入口 |
| `int x()` / `y()` / `width()` / `height()` | 内容区**绝对**坐标（已扣除状态栏与导航栏），width≈120、height≈200 逻辑像素 |
| `double mouseX()` / `mouseY()` | 已换算到手机坐标系的鼠标位置 |
| `float partialTick()` | 插值 |
| `PhoneStyle style()` | 当前主题配色 |
| `boolean hovered(rx, ry, rw, rh)` | 传入**相对内容区**坐标判悬停 |
| `boolean hoveredContent()` | 鼠标是否在内容区内 |
| `void clipped(x, y, w, h, Runnable)` | 裁剪绘制（可嵌套，异常也会收回裁剪） |

> ⚠️ **陷阱**：不要直接 `graphics().enableScissor(...)`。原版 scissor 收的是**窗口物理坐标**且不看 PoseStack，而手机界面可被缩放 75%–300%，直接裁会错位。**一律用 `clipped()`**。

### 1.4 PhoneStyle — 主题配色

`api/client/ui/PhoneStyle.java`，全部 ARGB int：

`titleColor()`、`bodyColor()`、`subtleColor()`、`accentColor()`、`screenBackground()`、`pressedOverlay()`、`buttonColor()`、`buttonHoverColor()`、`buttonDisabledColor()`、`buttonDisabledTextColor()`。

### 1.5 RequiredMod — 前置声明

```java
public record RequiredMod(String modId, String displayName) {}
```

### 1.6 MCphoneApi — API 版本与兼容承诺

`api/MCphoneApi.java`：

- `MCphoneApi.VERSION`：当前为 **2**。注意它在静态块里赋值（防止编译期常量内联进附属 jar），运行时读取才可靠。
- 兼容五承诺：不给接口加抽象方法、不改 record 构造、不改已有签名、包名即 API、只对 `api` 包负责。
- 附属应在 `neoforge.mods.toml` 里对 mcphone 声明 versionRange 依赖，旧版本会先被挡下。

### 1.7 商店来源 SPI（IAppSource / AppInfo）

`api/client/store/`。想给手机商店提供第三方 App 分发时用；SPI 文件：
`META-INF/services/com.november.mcphone.api.client.store.IAppSource`。

```java
public interface IAppSource {
    ResourceLocation getId();                 // 如 mymod:official_repo
    Component getDisplayName();
    void listAvailable(Consumer<List<AppInfo>> callback);   // 可异步，回调必须 Minecraft.getInstance().execute 切回主线程
    void install(AppInfo info, Consumer<IPhoneApp> onSuccess, Consumer<Component> onError);
    default boolean isReady() { return true; } // false = 商店标记为不可用
}
```

`AppInfo`：builder 模式 `AppInfo.builder(id, displayName, sourceId).icon(...).version(...).author(...).description(...).build()`；或 `AppInfo.of(IPhoneApp, sourceId)`。

> 围棋 App 走 `IPhoneApp` SPI 直接进主屏即可，**不需要** IAppSource（那是"商店里再分发别的 App"用的）。

### 1.8 cost 包（付费 App）

`api/cost/`：`ICost`（价格抽象）、`ItemCost`（物品支付）、`EmcCost` + `IEmcWallet` + `EmcWallets`（EMC 支付，与 ProjectE 类模组对接）、`IAppPriceProvider`（给 App 定价）。围棋 App 若免费可不碰。

### 1.9 主模组内部工具（非 API，参考勿依赖）

`core/client/GuiUtil.java` 等属于 `core` 包，**不在兼容承诺范围内**，附属不要 import。需要绘制工具时参考附属的 `Ui` 类（见 §2.4）。

---

## 2. 附属范本 mcphone-deepseek（围棋 App 的抄作业对象）

单模块 Gradle 工程，`@Mod(value = MODID, dist = Dist.CLIENT)` —— 整个模组只有客户端内容，jar 丢进服务端 mods/ 只是不加载、不崩、不要求玩家装。

### 2.1 模组入口 MCphoneDeepSeek

- 构造函数里从 `ModContainer.getModInfo().getVersion()` 取运行时版本号存静态字段（**不写死字面量**），`version()` 供 App 详情页用。
- App 完全靠 SPI 被发现，入口类不注册任何东西。

### 2.2 DeepSeekApp — IPhoneApp 最小实现范本

对 MCphone 的接触面只有六个方法：`getId`（`ResourceLocation.fromNamespaceAndPath(MODID, "deepseek")`）、`getDisplayName`、`getIconTexture`、`openPage`（懒建、单实例复用）、`onPress`（留空，防旧版 MCphone 崩）、`getVersion/getAuthor/getDescription`。

### 2.3 AI 网络层（围棋 AI 对接可直接套用的模式）

**DeepSeekClient**（`client/net/`）— OpenAI 兼容 `/chat/completions` SSE 流式客户端：

- 只用 JDK 自带 `java.net.http.HttpClient`（异步、流式、可中途关流），不引第三方库。
- 懒建 HttpClient；`connectTimeout(20s)`，**整请求不设超时**（回复可能吐几分钟）。
- 专用 **cached 守护线程池**（`"mcphone-deepseek-http"`）：读流是阻塞的，固定池会饿死 HttpClient 协议任务；守护线程保证退游戏不卡。
- `thenAcceptAsync(..., POOL)` 而非 `thenAccept`：避免阻塞任务落到 `ForkJoinPool.commonPool`。
- SSE 逐行解析：`data:` 前缀、`[DONE]` 结束、`delta.content` / `delta.reasoning_content` / `delta.reasoning`（兼容别名）、流内夹 `error` 块也要认。
- 错误分类到 **lang key**：HTTP 400/401/402/404/429/5xx 各一个键；网络异常细分 timeout/dns/connect/tls/network；响应体截断 160 字。
- `URI.create` 抛 `IllegalArgumentException` 进不了 `exceptionally`，须单独 try/catch 兜住（玩家填了畸形地址）。

**Turn**（`client/net/`）— HTTP 线程与渲染线程之间**唯一的接触面**：

- 写侧只有 HTTP 线程：`appendReasoning/appendAnswer/finish/fail/attach`（包私有）。
- 读侧只有渲染线程：`state()/isRunning()/answerText()/reasoningText()/revision()/thoughtSeconds()/errorMessage()` 等。
- 正文用 **StringBuffer**（同步，边追加边 toString 安全），状态字段全 **volatile**，`revision` 用 AtomicInteger 供界面判断"变了才重排版"。
- 错误只存 **翻译键 + 参数**，渲染线程显示时才 `Component.translatable`（I18n 不能在别的线程碰）。
- `cancel()` = 关流（让读循环抛异常退出，服务端真的停手），不是 `future.cancel()`（那只让自己不等，钱照扣）。`attach()` 里补一刀：玩家可能在连接建立前就按了停止。
- 状态机：`CONNECTING → THINKING → ANSWERING → DONE / ERROR / CANCELLED`。

**ModelCatalog** — `/models` 拉模型列表，`state()/models()/refreshIfStale()/refresh()`，拉不到用 `FALLBACK_MODELS` 垫底（不是白名单，设置页允许手输——模型名会换代）。

### 2.4 UI 迷你框架（`client/ui/`，纯 GuiGraphics 手绘，无第三方依赖）

| 类 | 角色 |
|---|---|
| `DeepSeekPage implements IPhonePage` | 页面容器，持有各 View 并分发事件 |
| `View`（interface） | `void render(PhoneCanvas, Theme)` + 鼠标/键盘分发 |
| `ChatView` / `HistoryView` / `SettingsView` | 三个子页（聊天/历史/设置） |
| `Theme`（record） | 从 `PhoneStyle` 抓一份配色快照：`title, body, subtle, accent, pressedOverlay, button, buttonHover, buttonDisabled, buttonDisabledText` |
| `Ui`（静态工具） | `roundRect(g,x,y,w,h,radius,color)`、`circle`、`hLine/vLine`、`textY/controlTextY/alignY`（垂直居中）、`truncate(font,s,maxWidth)`、`centered`、`hit(mx,my,x,y,w,h)`、`thumbHeight/thumbTop`（滚动条） |
| `Blocks` | 列表条目绘制块（SettingsView 的 `section/text/toggle/number/pill/danger/note` 条目工厂） |
| `Md` | 轻量 Markdown 渲染（粗体/代码等） |
| `MessageLayout` | 聊天气泡排版（气泡最宽占比等） |
| `DeepSeekIcon` | 图标贴图常量 + 程序化绘制 |

**围棋棋盘绘制直接复用 `Ui.roundRect/hit/truncate` 这套模式即可。**

### 2.5 存储层（`client/store/`）

- **DeepSeekConfig** — `config/mcphone_deepseek/config.json`（Gson 明文 JSON）。**刻意不用 NeoForge ModConfigSpec**：所有项要在手机屏上改完当场生效。`get()` 懒加载 + `sanitized()` 把手改 json 拉回合法区间（`Math.clamp`）；`save()` 每次 setter 里调。API Key 明文存放，配套三件实事：界面只显示末四位（`maskedApiKey()`）、日志一个字不写、只发给服务地址。字段：apiKey / baseUrl（默认 `https://api.deepseek.com`，getter 去尾斜杠）/ model（默认 `deepseek-v4-flash`）/ thinking / contextMessages(0–100) / maxTokens(0–65536, 默认 2048 是花钱闸)。
- **ConversationStore** — 对话记录仓库：`all()`（按 updatedMs 倒序）/ `create()` / `remove(c)` / `clear()` / `changed()` / `save()`，懒加载 + 静态缓存。
- **JsonFile** — 通用 JSON 文件读写（`read(Path, Class)` / `write(Path, Object)`），读坏返回 null。
- 配置文件与数据文件**分开存**：性质不同（Key 不能随手拷给人）、写频率不同（数据文件写坏不该带走 Key）。

### 2.6 资源文件清单

- `assets/mcphone_deepseek/lang/{en_us,zh_cn}.json`（所有错误/文案的 lang key）
- `assets/mcphone_deepseek/textures/app/deepseek.png`（图标）
- `META-INF/services/com.november.mcphone.api.client.app.IPhoneApp`（SPI 登记）
- `templates/META-INF/neoforge.mods.toml`（对 mcphone 声明依赖 versionRange）

---

## 3. 现成的 MC GUI 生成知识（本项目采用方案）

结论：**不引入第三方 UI 引擎**，用现成的三层知识组合：

1. **手机内页（首选）**：`IPhonePage` + `PhoneCanvas` + `PhoneStyle`，120×200 逻辑像素，缩放由主模组处理，只需 `clipped()` 裁剪。围棋 App 的棋盘/对局/设置页走这条路。
2. **附属 UI 迷你框架模式**：View 接口 + Theme record + Ui 静态绘制工具（roundRect/hit/truncate/滚动条），纯 GuiGraphics 手绘，零依赖，已被 DeepSeek 附属验证。
3. **原版 Screen 模式（备用）**：若需要超出手机的大界面，`opensInsidePhone() = false`，App 自开全屏 `Screen`，用原版 `GuiGraphics` 全套。

### 已确认的陷阱清单

| 陷阱 | 规避 |
|---|---|
| `enableScissor` 收窗口物理坐标、不看 PoseStack，手机缩放 75%–300% 时裁错 | 一律用 `PhoneCanvas.clipped()` |
| 页面有输入框但 `capturesKeyboard()` 返回 false | 背包键 E 误关手机；有输入必须返回 true |
| ESC | 统一关机，不可拦截；页面内"返回"用导航栏 onBack |
| App id 命名空间用 `mcphone` 或撞车 | 后登记者被静默丢弃；必须用自己 modid |
| HTTP 线程直接改界面/存储 | `ConcurrentModificationException` 出现在渲染方法里、堆栈不提网络；用 Turn 模式隔离 |
| I18n 在非渲染线程调用 | 只存翻译键，渲染时再 translatable |
| `thenAccept` 落到 ForkJoinPool.commonPool | 用 `thenAcceptAsync(..., 自己的池)` |
| 常量内联 | `MCphoneApi.VERSION` 静态块赋值，附属读的是运行时值 |

### 外部 MC GUI 库调查（均判定不适用，仅存档）

| 库 | Star | 不适用理由 |
|---|---|---|
| SpruceUI | ~186 | Fabric 系，本项目 NeoForge |
| Sunscreen | ~228 | Fabric 系 |
| ModernUI-MC | ~210 | 需整个前置模组，重型（含文本排版引擎），与手机内 120×200 场景不匹配 |
| Elementa | — | 主要面向老版本（1.8–1.20 早期），1.21.1 适配与依赖风险高 |

> 备注：主模组 javadoc 提到的 `PhoneMultiLineEditBox`（VERSION 2 计划项）**不在当前 api 包源码中**，属未来版本，暂不可用。

---

## 4. 围棋 App 已确认决策（供实施时对照）

- **AI 对接**：自定义 API 地址接口优先；预设提供者顺序：GPT → 通义千问 → 深度求索 → 智谱清言（均 OpenAI 兼容协议，套用 §2.3 的 Client/Turn 模式）。
- **对弈仲裁**：服务端权威（主模组本身客户端+服务端都装，具备条件）。
- **棋盘规则**：19 路，中国规则（数子法、禁自杀、打劫）。
- **版本目标**：仅 1.21.1 NeoForge（Forge 1.20.1 等用户提起再做）。
