# 项目记录文档（实时更新）

> 记录本项目所做的修改与重大更新，按时间倒序追加。每次重大改动后必须更新本文件。
> 项目目标：在 MCphone 模组的手机中添加围棋 App（双人 GUI 对弈 + AI API 对弈），仅 1.21.1 NeoForge。

---

## 2026-09-14

### ✅ Phase 3 客户端 UI 完成 + 两轮送审通过（`build` 全绿，74 单测无回归）
- **全屏棋盘 `client/ui/GoBoardScreen`（原版 Screen）**：对局一开始由大厅页 `setScreen` 打开（手机内容区仅约 120×200，19 路每格约 5 像素点不准，故落子放全屏——实施计划锁定决策）。**手动分层渲染**：`renderBackground` 一次 → 棋盘 → 面板/终局覆盖 → 逐个 `button.render` → toast，**刻意不调 `super.render`**（否则背景二次压暗盖棋盘 / 控件被自绘层盖住）。原版 `Button` 承载 pass/resign/返回/认输二次确认；棋盘本体（木底+网格+九星位+黑白子+劫争禁着点+最后一手红点+落点预览）用 `GoUi` 自绘。坐标 cell=span/20（18 间隔+两侧各 1 格木边），命中与视觉同映射。
- **最后一手标记 = 客户端 diff 推断（用户选定，不改已审的 Phase 2 协议）**：`RoomState` 不含最后落点字段，故存 `prevRoom`，每收新快照且 `moveNumber==prev+1` 时 `mover=turn.opponent()`（落子后 turn 已翻），扫「prev 空 && 现为 mover」的唯一交叉点（提子只让对手子实→空、不产生第二个空→实，故唯一）；首份快照/换房/手数跳变 → -1（不显示，纯显示用途，不影响服务端权威，用户已接受降级）。
- **大厅页 `LobbyPage` 落地为真 UI**：创建（执色二选一）/凭 6 位房号加入/房主邀请在线玩家/响应邀请横幅/离开；即时模式（每帧重建 `List<ClickTarget>`，mouseClicked 遍历命中）；配色全取自 `PhoneStyle`（跟随手机主题）。对局开始后 `renderInGame` 给「进入全屏棋盘」按钮。
- **数据流与清理钩子**：两类只读 `GoClientCache`、只经 `GoNetwork.sendToServer` 发包，客户端不持对局真值、不自行判胜负；`GoGameClient` 挂 `ClientPlayerNetworkEvent.LoggingOut → GoClientCache.clear()`（登出清缓存，兑现 GoClientCache javadoc 的 Phase 3 承诺）。
- **共享文案 `client/ui/GoText`（新建）**：`colorName/describeResult/trimNumber` 下沉共用一份（此前两类各留一份、改文案易漏改）；结果整句括号交给带参 i18n 键 `gogame.result.line`(en `%s (%s)`/zh `%s（%s）`)与 `line_with_margin`，英文环境不再硬编码全角括号。
- **i18n**：双语补全约 45 个 UI 键（app/lobby/board/color/result/error），删死键 lobby.wip/lobby.phase。
- **两轮送审 code-reviewer**：第一轮报 W1-W6 + O1-O9，分级修复——W1/W2（阻断级：确认问句 Y 与按钮行同源于新字段 `passRowY`；隐藏按钮 active 一律绑 visible 杜绝抢点击致卡死）+ W3(ghost 排除劫点)/W5(WAITING 按座位取行)/W6(cell 下限 4 + boardPx 夹回 span)/O6(updateLastMove 加 roomId 守卫)/W4-O3(GoText 抽取)/O1-O2-O7；跳过 O4/O5/O8/O9（微观性能/吹毛求疵）。**第二轮复审结论「通过」：W1/W2 已真正解决，无新严重/警告级缺陷，铁律（API 边界/dist 隔离/客户端不持真值/渲染分层）全守住。**
- **复审后小修（已修 Wa1+Oa1，`build` 全绿）**：**Wa1** 终局后 `confirmingResign` 未清会吞一次 ESC → `updateWidgets` 首行加 `if (finished) confirmingResign = false;`（终局即退出确认态，与按钮可见性同源）；**Oa1** 认输确认问句补 `GoUi.truncate(…, pw)`，与同面板 turnLine/suspended/玩家名口径一致、防未来长翻译溢出。**Oa2–Oa6 评估后跳过**：Oa2 WAITING 行 captured 语义（该阶段两值恒 0、显示“提子 0”无可见错误）、Oa3 koIndex 边界检查冗余（`ko>=0` 已蕴含 `!=-1`、非 bug）、Oa4 `tr` 两类各留一份（局部便利方法、下沉反降可读性）、Oa5 mouseClicked 重算 layout（纯计算、微观性能）、Oa6 极矮窗口文案重叠（默认窗高≥240 不触发、原有边缘问题）——与第一轮跳过 O4/O5/O8/O9 同标准。
- **验证**：`./gradlew build` BUILD SUCCESSFUL；74 单测无回归。
- **待收尾**：端到端运行时验收（双 runClient 实例：开手机 App→建房→加入/邀请→全屏棋盘落子→pass/resign→终局数子出结果→掉线宽限判负），一并触发 Phase 2 遗留的运行时验收——需启动游戏，本次关机前未做。

### ✅ Phase 2 送审 code-reviewer + 按报告修复（`build` 全绿，74 单测：新增 GameRoomTest 32）
- **审查结论**：“修改后通过”，报 1 严重 + 5 警告 + 6 优化。已全部落地修复（除 #11 挂钟超时——评估为可接受，改加注释说明）。
- **严重 #1（必修，已修）**：`onPlayerLoggedIn` 对 FINISHED 房间只补发 `GameEnded`、不解绑 `playerRoom` → 玩家“掉线超时判负后重连”会**永久卡在‘已在房间’、无法开新局**。这是掉线判负核心流程的必然产物。
  - **修复策略改为“终局即拆房”**：新增 `finishAndNotify`（先 `broadcastEnd` 给在线成员，再 `disband`）；`disband` 无条件解绑双方 + 清 `names` + 清指向房主的遗留邀请。房间不再靠“双方都离线才惰性回收”，从根本上杜绝映射残留。`onPlayMove`/`tick`/`onLeaveRoom`(认输) 三路终局全走 `finishAndNotify`。
- **警告修复**：#2 `join` 拆分 phase 判定（PLAYING/满员→`ERR_ROOM_FULL`，仅 FINISHED→`ERR_FINISHED`）；#3 `disband` 清 `pendingInvites` 中指向该房主的邀请；#4 `names` 只保留“在局玩家”（登入不再无条件缓存、`unmapAndForget` 离场即删）杜绝长跑内存泄漏；#5 双语 lang 补全 16 个 `gogame.error.*` 键（GameRoom 8 + Manager 5 + GoRules 3）；#6 `reconnect` 硬校验 `nowMs`，超宽限期拒绝恢复交给 `tick` 判负。
- **优化修复**：#7 `tick` 用 `isExpired` 助手 + 双方同时到期时“取截止更早者、相等判房主负”确定性 tie-break（不再由代码顺序偶然定胜负）；#8 `onLeaveRoom` 认输路只发一次结果包（去冗余 syncRoom）；#9 `onInvitePlayer` 邀请 last-write-wins 加注释；#10 `Seat` 三个可变字段收敛为包级私有。
- **#12 补测**：新建 `GameRoomTest`（32 例）覆盖建房/定色(HOST_PICKS+RANDOM+EMPTY 回退)、加入三拒绝、落子仲裁(轮次/越界/占点/非本房)、终局三路(数子/认输/resignBy)、掉线宽限(挂起阻断/期内重连/超时拒绝)、tick 判负(房主/客人/双到期 tie-break)、历史与查询。**#11 挂钟超时评估为可接受**（分钟级粗粒度、对时钟回拨不敏感），加注释说明，暂不改 tick 计数。
- **验证**：`./gradlew build` BUILD SUCCESSFUL；测试 74 绿（GameRoomTest 32 + Board 14 + GoRules 14 + Move 9 + Scoring 5）零失败。
- **待收尾**：运行时验收（双实例建房/加入/落子/数子/掉线判负）需 Phase 3 客户端 UI 才能触发——已就是否现在做隔离测试征询用户。

### ✅ Phase 2 网络层 + 服务端对局管理器代码完成（`compileJava`+`build` 全绿，待运行时验收）
- **7 个新文件**（编译通过、42 规则单测无回归、jar 组装成功）：
  - `common/game/GameResult`：终局结果值对象 + `EndReason`（DOUBLE_PASS/RESIGN/DISCONNECT）+ `byScore/byResign/byForfeit` 工厂；仅数子终局带 `Scoring.Result` 明细。
  - `common/game/GameRoom`：服务端权威状态机（纯 Java 无 MC 依赖，可脱机单测）——`Phase`(WAITING/PLAYING/FINISHED)、`ColorMode`(HOST_PICKS/RANDOM)、`Seat`(颜色+连接状态+掉线截止)；`applyMove` **颜色取自 turn、忽略客户端传色（防作弊）**，调 Phase 1 引擎仲裁，pass 计数达 2 数子终局；`join/resignBy/disconnect/reconnect/tick`；`RECONNECT_GRACE_MS=60_000`。
  - `common/game/GameServerManager`：单例随服务端起停；`rooms/playerRoom/pendingInvites/names` 四表（仅主线程访问无同步）；建房生成 6 位号（碰撞重试）、凭号加入、在线邀请双通道、落子仲裁、离场=认输、掉线挂起、重连恢复、tick 超时判负 + 回收空房；`snapshot` 打全量快照下发。
  - `common/net/GoCodecs`：枚举 codec（VAR_INT 存 ordinal + **越界退回兜底值**，仿本体 Relation）、`BOARD`（361 字节定点，畸形字节当空）、`SCORING_RESULT`、`GAME_RESULT`。
  - `common/net/GoPayloads`：整条协议表——C2S(CreateRoom/JoinRoom/RequestOnlinePlayers/InvitePlayer/RespondInvite/**PlayMove 无颜色字段**/LeaveRoom) + S2C(RoomState 全量含可空 guest 的 PlayerView/GameEnded/InviteNotify/OnlinePlayers/GoError)；单字段用 `.map`、2–3 字段 `composite`、空包 `unit`、List 用 `.apply(list)`——全对齐本体已编译写法。
  - `common/net/GoClientCache`：纯数据缓存（room/invite/error/result/onlinePlayers），**零客户端 import**，S2C handler 只写它、Phase 3 界面读它。
  - `common/net/GoNetwork`：`register`(modEventBus) + `toServer/toClient` 收编 `enqueueWork`+`instanceof ServerPlayer` 样板 + `sendToServer/sendToPlayer` 门面（全仓唯一出现 `PacketDistributor` 处）。
- **`GoGameMod` 挂钩**：`modEventBus.addListener(GoNetwork::register)`（注册 payload）；`NeoForge.EVENT_BUS` 挂 `ServerStarting/ServerStopping`（管理器起停）、`ServerTickEvent.Post`（掉线超时+回收）、`PlayerEvent.PlayerLoggedIn/LoggedOut`（重连恢复/掉线挂起）。
- **两个用户决策落地**：①掉线=**重连宽限期（60s）后判负**；②执色=**建房时二选一**（HOST_PICKS 房主自选黑/白，RANDOM 人齐随机）。
- **dist 隔离**：`GoNetwork`/`GoClientCache`/`GameServerManager` 全程不碰 `net.minecraft.client.*`（只用 ServerPlayer/MinecraftServer 等两端 jar 都在的服务端类），专用服务器加载即安全。
- **待收尾**（下次继续）：送审 code-reviewer + **运行时验收**（双 runClient 实例：建房/凭号加入/在线邀请/互相落子/非法着被服务端拒/终局数子出结果/掉线宽限判负）——需启动游戏，本次关机前未做。

### ✅ Phase 1 围棋规则引擎完成（纯 Java + 42 单测全绿 + 已送审）
- **6 个规则类**（`common.rules`，零 MC 依赖，双端共用）：
  - `Stone`（枚举 + opponent/isStone/isEmpty）、`Group`（record 连通块 + 去重气集）
  - `Move`（record 一手棋 + GTP↔坐标、SGF 坐标；紧凑构造器校验）
  - `Board`（19×19 一维数组 + BFS 气/连通块 + copy + ASCII 点阵渲染 + 九星位查表）
  - `GoRules`（落子/提子/禁自杀/单劫禁着，纯静态函数 + legalMoves 枚举；非法返回翻译键）
  - `Scoring`（中国规则数子法：子+围空、单官 dame 归属、黑贴 3¾ 子 → margin=(黑area−白area)/2−3.75）
- **坐标约定锁死**：x=列∈[0,18] 左→右、y=行∈[0,18] 上→下（y=0 顶行）；index=y*19+x；GTP A1=(0,18)、T19=(18,0)、列跳 I；SGF (0,0)→aa。
- **42 个 JUnit 单测全绿**（Board 14 / GoRules 14 / Move 9 / Scoring 5）：提单子/提整块/一手提两块独立单子、禁自杀、自杀因提子转合法、打劫完整周期（禁即提→别处一手→解禁回提）、数子归属+dame+komi 阈值（黑185胜¾子/184负¼子）、GTP/SGF 往返+畸形输入、角/边气数、越界读写抛异常。
- **已送审 code-reviewer**：无阻断性问题，核心规则经独立推演全部正确。采纳 4 项改进——B1 Board (x,y) 越界检查（防静默绕回污染棋盘）、C3 星位改 boolean[] 查表（免装箱）、B2 Outcome.board() 契约文档精确化、C2 fromGtp 列字母校验（Phase 4 解析 LLM 输出友好报错）。延后 B3(SGF 反解析)/B4-5(性能)/B6(终局断言) 到对应 Phase。

### ✅ Phase 4 关键设计确认：AI 不看图，用文本化棋盘
- **用户主张（已采纳锁死）**：AI 对弈不走“截图给多模态 LLM 视觉识别”的路。棋盘状态本就是规则引擎维护的权威数据，直接编码成文本喂 LLM 即可——AI 看得懂“点阵上的围棋信息”，不需要看懂 GUI 长什么样。
- **理由**：零识别误差（文本 100% 精确，视觉会误读子色/落点）；不绑多模态模型（四预设的纯文本模型都能跑，成本/可用性更好）；省 token 低延迟；契合“校验+重试+随机兜底”文本闭环；SGF/ASCII 是 LLM 围棋语料主流格式，更能唤起棋感。
- **棋盘文本格式（用户选定）**：**ASCII 点阵 + SGF 双表示**——19×19 字符棋盘（·/●/○ 配 A–T、1–19 坐标）+ SGF 局面串，两者互校最大化 LLM 理解。
- **落地点**：Phase 4 的 `GoAiClient` system prompt 附此文本棋盘；`MoveParser` 解析 AI 回的坐标 → `GoRules` 校验。**全程无图像、无多模态依赖。**

### ✅ Phase 0 完全验收通过（runClient 实测 + 用户肉眼确认）
- **构建**：`./gradlew build` 全绿零警告。
- **运行**：`runClient` 成功进主菜单，日志确证——`Mod List: GoGame 1.0.0`、`Found 0 mod requirements missing`、`[MCphone] App 已登记: gogame:go v1.0.0`、`[GoGame] 客户端 setup 完成`、`Reloading ResourceManager: ... mod/gogame`。
- **用户肉眼确认**：手机主屏「围棋」图标显示正常、可点击打开占位大厅页。**附属被本体 SPI 发现这条核心链路打通。**
- **途中修复两坑**（详见 PITFALLS C7/C8）：mods.toml 的 versionRange 二次包方括号致 `[[21.1.200,),)` 非法、mod loading 崩；模板注释里字面占位符致 generateModMetadata 解析失败。

## 2026-09-13

### ✅ 围棋 App Phase 0：工程基线完成（build 全绿）
- **下载本体**：从 mcphone v1.9.3 Release 取 `mcphone-1.9.3.jar`（740KB）放入 `gogame-1.21.1/libs/`
- **版本对齐**：gradle.properties 加 `mcphone_version=1.9.3`、`neo_version_range=[21.1.200,)`、`mod_group_id=com.november.gogame`；`neo_version` 保持已缓存的 21.1.250（改 248 会触发 userdev.jar 重新下载、代理 TLS 中断，见 PITFALLS C5）
- **依赖方式**：build.gradle 用 `compileOnly + localRuntime files("libs/mcphone-1.9.3.jar")`（绝不 implementation）；补 `mavenCentral()` 让 JUnit5 可解析（见 PITFALLS C6）；JUnit5 + toolchain21
- **neoforge.mods.toml**：neoForge 依赖 versionRange 用 `[${neo_version_range},)`；新增 mcphone required 依赖（side=CLIENT）
- **包名重构**：`com.example.examplemod` → `com.november.gogame`；删 MDK 示例（旧 Config/GoGame/GoGameClient + EXAMPLE_BLOCK/ITEM/TAB）
- **双端骨架**：GoGameMod（双端 @Mod）+ GoGameClient（@Mod dist=CLIENT，对齐本体 MCphoneClient 模式，消除 @EventBusSubscriber bus() 的 removal 弃用警告）
- **App SPI 链路**：GoApp（IPhoneApp，id=gogame:go，程序化 renderIcon）+ LobbyPage（IPhonePage 占位大厅）+ GoIcon + META-INF/services 登记 + 双语 lang
- **验收**：`./gradlew build` BUILD SUCCESSFUL（零警告），主代码对 mcphone 1.9.3 API 签名全兼容；runClient 图标可见性待用户 GUI 环境确认

### ✅ 创建三大文档体系
- 新建 `docs/PROJECT_LOG.md`（本文件，实时项目记录）
- 新建 `docs/TECH_STACK.md`（技术文档 + 仓库链接）
- 新建 `docs/PITFALLS.md`（踩坑记录，供未来参考）

### ✅ 完成接口参考手册
- 新建 `mcphone-api-ui-reference.md`（工作区根目录）
- 内容：主模组 App SPI（IPhoneApp / IPhonePage / PhoneCanvas / PhoneStyle / MCphoneApi / RequiredMod / IAppSource / AppInfo / cost 包）、附属范本 mcphone-deepseek（入口 / App 注册 / AI 网络层 Client+Turn / UI 迷你框架 / 存储层）、现成 MC GUI 生成知识（三层方案 + 陷阱清单 + 外部库调查表）
- 核实：`PhoneMultiLineEditBox` 不在当前 api 包中（javadoc 预告的未来项），已在文档中注明

### ✅ 围棋 App 四项关键决策（经用户确认）
1. AI 对接：自定义 API 地址接口优先；预设顺序 GPT → 通义千问 → 深度求索 → 智谱清言
2. 对弈仲裁：服务端权威
3. 棋盘规则：19 路 + 中国规则（数子法、禁自杀、打劫）
4. 版本目标：仅 1.21.1 NeoForge（Forge 1.20.1 等用户提起再做）

### ✅ 自我代理配置（访问 GitHub 用）
- git per-URL 全局代理：`github.com` / `raw.githubusercontent.com` / `gist.githubusercontent.com` → `http://127.0.0.1:7897`
- 会话级环境变量 `http_proxy` / `https_proxy`（curl 等工具用）
- 用途：任务中缺少知识/技能时可去 GitHub 查找、克隆参考仓库

### ✅ MC GUI 知识选型调查
- 结论：不引入第三方 UI 引擎（SpruceUI/Sunscreen 为 Fabric 系、ModernUI-MC 重型前置、Elementa 老版本）
- 采用现成三层知识：mcphone 自有 UI API（PhoneCanvas/IPhonePage）+ 附属 UI 迷你框架（View/Theme/Ui）+ 原版 Screen 模式（opensInsidePhone=false 备用）

### ✅ 克隆两个参考仓库到工作区
- `f:\Qoder MOD Creater\mcphone`（主模组，--depth 1，经代理）
- `f:\Qoder MOD Creater\mcphone-deepseek`（附属范本，--depth 1，经代理）
- 已创建长期记忆记录两仓库用途

### ✅ 创建代码审查子智能体
- 新建 `gogame-1.21.1/.qoder/agents/code-reviewer.md`
- tools 仅 `Read, Grep, Glob`（物理只读，绝不改代码）；中文系统提示；三级输出格式（严重问题/警告/优化建议）
- 约定：主程序员写码后主动送审

### ✅ 构建失败修复（JVM 版本钉死）
- 症状：终端构建报 "Gradle requires JVM 17 or later to run. Your build is currently configured to use JVM 13."
- 根因：旧终端继承旧 JAVA_HOME（JDK13）
- 修复：`C:\Users\29079\.gradle\gradle.properties` 追加 `org.gradle.java.home=F:/JAVA/Java25`（正斜杠防转义），旧环境下验证 BUILD SUCCESSFUL

### ✅ JDK 版本问题解决
- 实测 `F:\JAVA\Java25`（JDK 25.0.3 LTS）可运行 Gradle 9.2.1
- 用户级 `JAVA_HOME=F:\JAVA\Java25`、用户级 Path 追加 bin、广播 WM_SETTINGCHANGE
- 编译 toolchain 21 由 foojay 自动下载至 `~/.gradle/jdks`

### ✅ Clash 代理配置（Gradle 项目）
- 决定性实验证明：Gradle 9.2.1 Wrapper 分发下载阶段也读 gradle.properties 代理（闭端口实验 → Connection refused 出自 org.gradle.wrapper.Install）
- 生效配置写入用户目录 `C:\Users\29079\.gradle\gradle.properties`（systemProp.http/https.proxyHost=127.0.0.1, proxyPort=7897, nonProxyHosts）
- 项目内 `gogame-1.21.1/gradle.properties` 只加注释模板（防破坏 GitHub Actions CI）
- 端到端验证：jar 构建成功

---

## 当前进度断点（下次继续从这里接）

**实施计划**：`围棋App实施计划_task-d1b.md`（已批准，Phase 0→5 串行，勿改计划文件本身）

- [x] **Phase 0 工程基线（完全验收）**：build 全绿 + runClient 成功——mcphone SPI 登记 `gogame:go`，用户确认手机图标显示正常、可点开大厅页。双端骨架 + App SPI 链路 + 双语 lang 全通。
- [x] **Phase 1 围棋规则引擎（完成 + 送审）**：6 类（Stone/Group/Move/Board/GoRules/Scoring）纯 Java 零 MC 依赖；42 单测全绿；code-reviewer 无阻断问题，采纳 B1/B2/C2/C3 四项防御性改进。
- [~] **Phase 2 网络层 + 服务端对局管理器（代码 + 送审修复完成，build 全绿 74 单测，待运行时验收）**：7 新文件（GameResult/GameRoom/GameServerManager + GoCodecs/GoPayloads/GoClientCache/GoNetwork）+ GoGameMod 挂生命周期/tick/玩家事件。服务端权威仲裁（颜色由 turn 定防作弊）、6 位房号、凭号加入 + 在线邀请双通道、终局数子广播、掉线宽限 60s 判负、执色建房二选一。**已送审 code-reviewer 并按报告修复（1 严重 #1 重连解绑 + 5 警告 + 5 优化，见本日）；补 GameRoomTest 32 例。剩余：运行时验收（双实例建房/加入/落子/非法拒/数子/掉线判负）——需 Phase 3 客户端 UI 触发。**
- [~] **Phase 3 客户端 UI（代码 + 两轮送审 + Wa1/Oa1 小修完成，build 全绿 74 单测，待端到端运行时验收）**：全屏棋盘 GoBoardScreen（手动分层渲染 + 客户端 diff 推断最后一手）+ 大厅页 LobbyPage 真 UI（创建/凭号加入/在线邀请/响应邀请/离开）+ 共享文案 GoText + GoUi 自绘零件 + GoGameClient 挂 LoggingOut 清缓存 + 双语约 45 UI 键。第一轮审查 W1-W6/O1-O9 分级修复、第二轮复审「通过」（W1/W2 阻断级已解决）、Wa1/Oa1 已修（Oa2-Oa6 评估跳过）。**剩余：端到端运行时验收（双实例建房/加入/落子/pass/resign/终局数子/掉线判负）——需启动游戏，与 Phase 2 运行时验收合并做。**
- [ ] Phase 4：AI 对弈（四预设 + 自定义，校验重试兜底）；**棋盘用 ASCII 点阵 + SGF 文本，不用视觉识图**（见 2026-09-14）
- [ ] Phase 5：i18n + 送审 + 端到端测试 + 文档更新
