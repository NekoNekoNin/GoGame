# 项目记录文档（实时更新）

> 记录本项目所做的修改与重大更新，按时间倒序追加。每次重大改动后必须更新本文件。
> 项目目标：在 MCphone 模组的手机中添加围棋 App（双人 GUI 对弈 + AI API 对弈），仅 1.21.1 NeoForge。

---

## 2026-09-15

### 🐛 验收修复：围棋图标严格居中 + AI 空回复红框及其根因（推理模型额度饥饿）
- **症状一**（用户真机截图 + 要求）：图标改为正方形棋盘、中间对齐、与四个角距离一致；旧绘制在部分尺寸下四边留白差 1px、内线不镜像，整块棋盘看着歪
- **修复一**：`GoIcon.render` 重写——棋盘墨迹边长 `S = size - 2*gap`、四边留白严格相等；内线镜像构造（`a=(S+1)/4`、`b=S-1-a`、`c` 中线）、白子外框 = 黑子墨迹的精确镜像（`s1 = S-d-s0`）；`S < 9` 早退（S=5/7 两子外框重叠发糊）。注释如实写明栅格化物理限制：S 为偶数时中线只能偏半像素（不影响四角距离严格相等）
- **症状二**（用户要求「去掉 AI 回复内容那个红色的框」）：PVE 每手弹红 toast「AI 回复里没有内容。」，AI 实际每手下随机着
- **根因二**（curl 实测确认，双层叠加）：① `deepseek-v4-pro`/`deepseek-flash` 是推理模型，思考 token 也计入 `max_tokens`——旧闸 128 全被推理吃光（实测 `reasoning_tokens:128 == completion_tokens:128`）→ `content` 空串 + `finish_reason=length`；② 提到 4096 后游戏内真盘推理链更长，响应体（`reasoning_content` 与 `content` 都占字节）冲破 `MAX_REPLY_BODY=32KB` 被 `readNBytes` 静默截断 → 解析失败 → 又误判空回复（审查预判的 W2 链，用户测到的中间构建正踩中）
- **修复二**：`MAX_TOKENS` 128→4096（上限只是天花板：非推理模型实际只用几十字符，花费不涨）；`MAX_REPLY_BODY` 32KB→128KB 联动（注释互写估算），`consume()` 读满上限时 warn「可能被截断」留证；空回复不再每手弹红框（用户要求），改 `silentEmptyStreak` 累计——连续静默满 5 手汇总弹一次兜底提示，start/成功/非 empty 错误三路复位；错误键提常量 `GoAiClient.ERROR_KEY_EMPTY` 防两处字面量漂移
- **送审两轮**：第一轮 W1（偶数 S 中线——采纳备选：保四边等距、注释改如实表述）/ W2（响应体联动）/ W3（静默改汇总提示）+ O1（S<9）/ O2（常量）/ O3（联动注释）；第二轮复审「通过、无阻塞」。O-新1 不采纳：把复位收敛进 `fallback()` 会让 empty 路径每次清零、汇总提示永不触发，违背 W3 本意
- **状态**：build 全绿 101 单测，客户端以完整构建重启；待用户游戏内验收图标居中 + PVE 正常落子无红框

### ✨ Model 行改为动态模型选择列表（不再手打模型名）
- **决策**（AskUserQuestion 确认）：清单来源 = **动态拉取 `GET {baseUrl}/models`**（该 Key 真实可用的模型清单，自定义地址也能用）；Model 行**完全换成选择列表**，不再打字
- **新文件 `client/ai/ModelListTurn`**：照 `AiTurn` 的 volatile 线程安全载体（LOADING/DONE/ERROR、结局只写一次），装模型 id 清单；HTTP 线程写 / 主线程读
- **`GoAiClient.fetchModels()`**：复用现成守护线程池 / 超时 / 错误分类，Bearer 鉴权，解析 `data[].id`，上限 200 个模型 / 256KB 响应体；抽出 `statusKey()` 让「一手棋」与「拉清单」两条路径共用错误映射
- **`LobbyPage`**：Model 行由点击打字改为点击进新 `View.AI_MODELS` 滚动选择页（刷新/返回 + 加载/错误/空清单各有提示）；移除 `Editing.MODEL`；选中写回 `AiConfig.setModel` 并回设置页
- **i18n**：双语 4 键 `gogame.ai.model.select/refresh/loading/empty`

### 🐛 验收修复：围棋图标统一圆角美化 + 滚动列表溢出白线
- **症状**（用户真机截图）：① 围棋图标是无圆角方木图、网格贴边，与 mcphone 原生 App 的圆角方图标风格突兀 ② 模型选择页右侧一条白色横线戳出手机右缘到世界背景
- **根因**：① `GoApp.renderIcon` 的 Phase 0 占位绘制整方填充 + `fill` 画**方**子 ② `renderAiModels`/`renderOnline` 误用 `GoUi.hLine`（第 4 参是宽度）传了列表高度 → 横线戳出手机（详见 PITFALLS E12）
- **修复**：① 绘制移入新 `GoIcon.render`：真圆角方木底（角弧用圆方程算、半径=边长/4，比例对齐原生图标；`GoUi.roundRect` 的 45° 切角在该半径会切出八边形）+ 内缩 5×5 线棋盘（不贴边）+ `GoUi.circle` 圆子（对角一黑一白）；`GoApp.renderIcon` 只委托 ② 新增 `LobbyPage.scrollBar`：仅 `maxScroll > 0` 时右侧画 1px 竖轨道 + 按滚动比例定位的拇指；模型列表 / 在线玩家 / 难度三处滚动列表统一改走它
- **送审**：code-reviewer「修改后通过、无阻塞」——采纳 **W1**（难度列表漏 scrollBar，补）+ **O1**（thumbH 钳 viewH 防极小视口拇指溢出）；O2（角弧公式偏紧 1px、视觉可忽略）/ O3（partialTick 未用）记录不改
- **状态**：compileJava 通过、客户端曾以新代码进世界；待用户游戏内确认

### 🐛 验收修复：零合法着死局不判胜 + AiConfig 按服务方各记一套 Key
- **症状一**（用户真机截图，PVE 第 594 手全盘近黑）：白子已无子可下却没判定胜利，对局卡死
- **根因一**：终局原本只有认输 / 掉线判负 / 连续两 pass 三条路；某方「零合法着」（空点全是自杀点或被劫禁）时唯一合法着是 pass，但没人按 pass → 永久死局。PVE 侧 AI 无子时还白发多次 LLM 请求才兜底 pass
- **修复一**：`GoRules` 新增 `hasLegalMove(board,color,koIndex)`（早退扫描，比 `legalMoves` 全量枚举便宜）；**PVP 服务端 + PVE 客户端双侧**在轮次方零合法着时自动代虚着（带 `gogame.autopass.black/white` toast），复用既有「连续两 pass → 数子终局」。服务端 `GameRoom.autoPassIfStuck()` 由 `GameServerManager.tick` 每 tick 调（代虚着后 syncRoom，达终局再 finishAndNotify）；PVE `LocalAiGame.autoPassStuck()` 由 `advance()` 每手后调（顺带修掉 AI 无子白发 LLM——AI 自己零合法着时直接代虚着）
- **症状二**（用户决策 AskUserQuestion）：API Key 原为单套、切服务方时被共用/重置，用户选「按服务方各记一套」
- **修复二**：`AiConfig` 改 `Map<AiProvider,Slot>`（EnumMap，枚举名作 JSON key），每槽各存 Key/地址/模型；`switchProvider` 只切 provider + 懒建目标槽（不再重置），切回来还在、无需重粘；旧版平铺 apiKey/baseUrl/model 保留字段仅供 `sanitized()` 一次性迁移进「当时 provider」槽后置 null（Gson 不写 null 自然消失）；难度档保持全局。`sanitized()` 末尾重建 EnumMap 保证落盘按服务方声明序
- **送审两轮**：第一轮报 **S1 严重**（初版 PVE 修复引入新死局——`autoPassStuck` 经 `apply` 递归 → `advance` 重复 requestAi，且 `tick` 尾部 `aiTurn=null` 抹掉新请求 → 轮次停在 AI 却无请求，永久卡死）+ W1（toast 覆盖）/ W2（apply 终局前翻 turn 与服务端不一致）/ W3（递归冗余）/ O1-O3。采纳 **W3**（`autoPassStuck` 改直接推进状态、不经 apply，消除递归）+ **S1①**（`tick` 先清 `aiTurn` 再 apply）+ **S1②**（`advance` 加 `aiTurn==null` 幂等守卫）+ **W2**（终局前不翻 turn）+ O1/O2（AiConfig）。**第二轮复审「通过、无阻塞」**，逐步骤确认闭环、fallback 自洽、retries 计数无回归、与服务端 autoPassIfStuck 逐项对齐
- **测试**：`GoRulesTest` +4（hasLegalMove：空盘/满盘/黑两眼白死/唯一空点被劫禁）、`GameRoomTest` +6（autoPassIfStuck：代一手/连锁终局/有着noop/挂起noop/WAITING noop/已终局noop）。**build 全绿 101 单测**（原 91 + 10）
- **遗留（非阻塞，reviewer W-a）**：`LocalAiGame` 的 tick→advance→autoPassStuck 链因 `feed()` 直调 `Minecraft.getInstance()` 无法纯 JVM 单测，只能真机验收；将来可把 feed 抽成注入点或拆出不依赖 MC 的 core 再补测
- **状态**：编译通过、客户端以新代码重启；待用户游戏内验收死局自动终局 + 切服务方 Key 各自记忆

---

## 2026-09-14

### 🐛 验收修复：手机编辑框无法 Ctrl+V 粘贴
- **症状**（用户报告）：围棋 App 人机设置页的 API Key / 地址 / 模型编辑框按 Ctrl+V 无反应
- **根因**：`IPhonePage` 不是原版 `Screen`，拿不到 `EditBox` 自带粘贴；`LobbyPage.keyPressed` 只处理 Backspace/Enter/Escape，`charTyped` 只收逐字符
- **修复**：`keyPressed` 识别 Ctrl+V（`GLFW_KEY_V` + `GLFW_MOD_CONTROL`）读 `Minecraft.getInstance().keyboardHandler.getClipboard()`（1.21.1 Mojmap 源码核实：`KeyboardHandler.getClipboard()` 返回 String、须主线程调）；粘贴净化为可见 ASCII（0x20–0x7E）——剥掉网页复制常带的换行/控制符（会导致鉴权失败）；房间号框只粘数字；`charTyped` 加 Ctrl 守卫防组合键多插杂字符
- **状态**：编译通过并重启客户端；待用户游戏内确认

### ✅ Phase 4 AI 人机对弈（PVE）代码完成 + 送审修复（`build` 全绿，91 单测：新增 17 例 AI 层）
- **8 个新文件（`client.ai` 包，均只在客户端加载）**：
  - `AiProvider`：5 服务方预设枚举（CUSTOM 自填 / GPT / QWEN 通义千问 / DEEPSEEK / ZHIPU 智谱），全走 OpenAI 兼容 `/chat/completions`；默认模型挑各家「快而便宜」档（一手棋只要回几十字符）。
  - `AiDifficulty`：**11 档难度（用户指定）**——菜鸟 NOVICE / 普通人 AVERAGE / 初段-九段 DAN1..DAN9。三旋钮（用户选定「人设+温度+失误扰动」）：英文 persona（写死不跟客户端语言走）、temperature（1.0→0.50 逐档递减）、blunderRate（菜鸟 0.50 / 普通人 0.18 / **段位档恒 0**，棋力全交模型）。档位是「可感知的强度阶梯」，不承诺真实等级分（LLM 棋力远达不到段位含义）。
  - `AiConfig`：明文 JSON 配置（`FMLPaths.CONFIGDIR/gogame/config.json`，Gson pretty），抄 mcphone-deepseek 的 `DeepSeekConfig`——**手机内当场改当场生效**（setter 即时 tmp+ATOMIC_MOVE 落盘），非 ModConfigSpec；`maskedApiKey` 只露末四位（固定六点、不按真实长度补）；`switchProvider` 切非 CUSTOM 预设时重置地址/模型为预设默认。
  - `AiTurn`：一次请求状态载体（CONNECTING/DONE/ERROR），volatile 字段，HTTP 线程写、主线程读，complete/fail 仅 CONNECTING 生效（收尾后噪声异常改不了结果）。抄 deepseek `Turn` 并简化（非流式，一个 volatile String 够）。
  - `GoAiClient`：**非流式** HTTP（围棋一手回复就几十字符，一次取回整份 JSON 比 SSE 逐字更稳），cached **守护**线程池 + 懒建 HttpClient + `thenAcceptAsync(…, POOL)`（避开 ForkJoinPool 公共池被阻塞读体拖累，PITFALLS E6）；CONNECT 20s / REQUEST 60s 超时（对局不能像聊天等几分钟）；MAX_TOKENS 128 省钱闸；`URI.create` 单独 try/catch（畸形地址抛 IAE 跑不进 exceptionally，PITFALLS E7）；错误分类到 **16 个无参翻译键**（toast 通道只传键、细节进日志，PITFALLS E5）。
  - `MoveParser`：LLM 回复**三层宽容解析**——① JSON `{"move":…}` ② 文本 GTP 正则（**只认大写**，免散文 at/be 误中）③ 独立 pass 词（`\bpass\b` 词边界，免 surpass/compass 误触）。**AI 不允许认输**：resign 当解析失败返回 null 交重试/兜底。
  - `GoPromptBuilder`：拼 prompt（**纯文本、绝不截图给多模态**，锁定决策见下条 2026-09-14）——system=英文人设+规则要点+严格 JSON 格式+禁 resign；user=局面说明+SGF 序列+ASCII 19×19 点阵（列头跳 I、行 19→1）；retry=附非法反馈，**翻译键映射成英文原因**（规则层仍只回键、prompt 层转英文，两者不耦合）。
  - `LocalAiGame`：**核心** PVE 本地对局控制器。客户端主线程跑与服务端 PVP **同一套** `GoRules` 引擎，每手结果**本地馈送**进 `GoClientCache`（用户锁定「本地馈送复用现有界面」——大厅/棋盘照常从缓存读，一行渲染代码都不为 PVE 另写）。tick() 收 AiTurn：解析失败/非法着带反馈**重试至多 3 次**→随机合法着兜底（`legalMoves` 空则 pass），任何 AI 故障棋局都不卡死；弱档按 blunderRate 概率扰动改随机着。roomId="PVE" 与联机房间互斥。
- **接线（4 处改既有文件）**：`GoClientCache` 加 3 个 public 本地馈送方法（feedLocalRoom/feedLocalResult/localError，与 S2C 包写通道互斥、同为「主线程写读」、**零客户端 import** 保 common 纯净）；`GoGameClient` 挂 `ClientTickEvent.Post → LocalAiGame.tick()`、LoggingOut 加 `leave()`；`LobbyPage` View 加 AI/AI_SETTINGS（11 档滚动列表+执色三选+开始/设置；设置页服务方循环切+Key/地址/模型三行点击编辑+编辑态键盘捕获 PITFALLS E2；leaveRoom 加 PVE 分支；startAi 无 Key 拦下跳设置）；`GoBoardScreen` 落子/pass/resign 出口按 `LocalAiGame.active()` 分支（PVE 本地 / PVP 发服务端）+ AI thinking 提示行。
- **i18n**：双语补全约 39 个 `gogame.ai.*` 键（难度 11 + 服务方 5 + 设置/thinking + 16 error + 标题等），`lobby.ai` 去「（即将推出）」。
- **送审 code-reviewer → 修改后通过、无阻塞项**。9 条重点关注（线程安全/I18n 渲染线程/通道互斥/common 纯净/资源泄漏/Key 安全/边界兜底/即时模式 UI/lang 键）全通过。修复 4 警告：**W1** AI 自发认输（MoveParser 拒 resign + prompt 显式禁止）、**W2** pass 子串误判（改 `\bpass\b` 词边界正则）、**W3** 重试把翻译键原样喂 LLM（GoPromptBuilder 加键→英文映射）、**W4** `LobbyPage.aiColor` 字段名与 `LocalAiGame.aiColor()` 语义相反（重命名 `playerColorChoice`）。采纳优化 **O1**（配置路径改 `FMLPaths.CONFIGDIR` 防漂移）/**O3**（logout 重复清缓存加注释）/**O4**（`MAX_REPLY_BODY` 常量替魔数 `*4`）/**O7**（GoApp 注释更新）；**O2/O5/O6 评估后跳过**（O2 省一次调用但牺牲 thinking 表演、O5 用户自己失效 Key 的低风险回显、O6 滚动部分可见行点击越界属既有 renderOnline 同款微小问题、正确修需重构 choice() 牵动已审代码）。
- **测试**：新增 `MoveParserTest`（三层解析/resign 拒绝/pass 词边界）、`GoPromptBuilderTest`（system/user/retry 关键成分 + ASCII/SGF 坐标换算）、`AiDifficultyTest`（11 档阶梯不变量：温度单调不增/失误率只给弱档/键唯一）；`build.gradle` 加 `testImplementation gson`（主源码从 MC 拿 Gson，裸测试类路径没有）。**`./gradlew build` BUILD SUCCESSFUL，91 单测零失败**（Phase 1-3 的 74 + AI 层 17）。
- **待收尾**：PVE 运行时验收（手机内开 AI 视图→填 Key→选难度/执色→开始→全屏棋盘与 AI 对弈→AI 非法/超时兜底→终局数子）——需真实 LLM API Key + 启动游戏，与 Phase 2/3 遗留的端到端验收合并做。

### 🐛 验收修复：手机菜单 hint 文字漏出 + git 建仓并推送 GitHub
- **症状**（用户真机截图）：大厅 MENU 页底部 hint「创建房间，或用 6 位房间号加入。」整行直画漏出手机右边界；联机链路本身无问题
- **根因**：`LobbyPage.renderMenu` 该行 `drawString` 未做 truncate/换行（内容区仅约 110 逻辑像素宽，zh ≈144px / en ≈236px）；同页其他文字都走 `GoUi.truncate`、唯独这行漏了；普查另发现 en 的 `waiting` / `online.empty` / 终局结果行同样超宽
- **修复**：lang 键值自然断点加 `\n`（zh 两行 / en 三行），代码侧 split `\n` 逐行绘制 + 每行 `GoUi.truncate` 兜底；另 3 处 en 可能超宽的正文行补 truncate 兜底。`join.invalid` 本就走 toast（已 truncate）不动
- **状态**：代码修复 + `build` 全绿；运行中的验收客户端仍是旧代码，**验收结束后重启客户端**一并验证新 UI（hint 只在无房菜单页显示，不干扰进行中的对局验收流程）
- **git 建仓**（用户指定 https://github.com/NekoNekoNin/GoGame，三项决策经 AskUserQuestion 确认）：仓库根 = 工作区根（含 `docs/` + `gogame-1.21.1/` + `mcphone-api-ui-reference.md`，根 `.gitignore` 排除主模组源码 `mcphone/` 与 `mcphone-deepseek/`）；提交身份 `NekoNekoNin <NekoNekoNin@users.noreply.github.com>` 仅提交时 `-c` 临时指定（不写 git config）；`libs/mcphone-1.9.3.jar` 入库（mcphone 不在 maven，clone 即构建的唯一依赖源）。初始提交 `ccd623e`（48 文件 / 6168 行）→ merge 整合 GitHub 建仓初始提交（LICENSE/README，无关历史）为 `a72c842` → 推送 `origin/main` 同步
- **途中事故**（详见 PITFALLS H1）：rebase 整合无关历史时需从工作区删除 jar、被运行中 MC 客户端 / Gradle 守护进程锁定而连环失败、工作区被清空；用「quit → 锁文件 add 进索引 → symbolic-ref → reset --hard」恢复，改 merge 整合

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
- [x] **Phase 2 网络层 + 服务端对局管理器（运行时验收通过 09-15 双客户端 e2e）**：7 新文件（GameResult/GameRoom/GameServerManager + GoCodecs/GoPayloads/GoClientCache/GoNetwork）+ GoGameMod 挂生命周期/tick/玩家事件。服务端权威仲裁（颜色由 turn 定防作弊）、6 位房号、凭号加入 + 在线邀请双通道、终局数子广播、掉线宽限 60s 判负、执色建房二选一。**已送审 code-reviewer 并按报告修复（1 严重 #1 重连解绑 + 5 警告 + 5 优化，见本日）；补 GameRoomTest 32 例。运行时验收 09-15 通过：双客户端 e2e 覆盖建房/凭号加入/落子/非法拒/数子/掉线判负（与 Phase 3 合并）。**
- [x] **Phase 3 客户端 UI（运行时验收通过 09-15 双客户端 e2e）**：全屏棋盘 GoBoardScreen（手动分层渲染 + 客户端 diff 推断最后一手）+ 大厅页 LobbyPage 真 UI（创建/凭号加入/在线邀请/响应邀请/离开）+ 共享文案 GoText + GoUi 自绘零件 + GoGameClient 挂 LoggingOut 清缓存 + 双语约 45 UI 键。第一轮审查 W1-W6/O1-O9 分级修复、第二轮复审「通过」（W1/W2 阻断级已解决）、Wa1/Oa1 已修（Oa2-Oa6 评估跳过）。**端到端运行时验收 09-15 通过（与 Phase 2 合并）：全屏棋盘/大厅/双路匹配/错误 toast/终局数子/商店贴图全部实测。**
- [x] **Phase 4 AI 对弈（PVE）（完全验收 09-15）**：`client.ai` 8 新文件（AiProvider/AiDifficulty/AiConfig/AiTurn/GoAiClient/MoveParser/GoPromptBuilder/LocalAiGame）+ 4 处接线（GoClientCache 本地馈送 / GoGameClient tick / LobbyPage AI 两视图 / GoBoardScreen 出口分支）+ 双语 39 键 + 3 测试类。**11 档难度（菜鸟/普通人/初段-九段）**，人设+温度+失误扰动三旋钮；ASCII 点阵+SGF 文本喂 LLM（不用视觉识图）；非流式请求+守护线程池+错误分类键；本地跑同一套 GoRules、馈送进 GoClientCache 复用现有界面；解析失败/非法着重试 3 次→随机兜底。送审「修改后通过、无阻塞」，修 W1-W4 + 采纳 O1/O3/O4/O7。**运行时验收通过（用户 09-15 确认）**：含验收修复批——零合法着死局自动代虚着终局、AiConfig 按服务方各记一套 Key、图标严格居中、推理模型额度/响应体联动（MAX_TOKENS 4096 / MAX_REPLY_BODY 128KB）、空回复静默汇总；PVE 完整对局 + AI 正常落子无红框
- [~] **验收修复批（与 Phase 4 运行时验收并行）**：编辑框 Ctrl+V 粘贴（09-14）；Model 行改动态 `/models` 选择列表、围棋图标统一圆角美化、滚动列表溢出白线 → `scrollBar`（09-15，送审采纳 W1/O1）；零合法着死局自动代虚着终局（PVP+PVE 双侧）、AiConfig 按服务方各记一套 Key（09-15，两轮送审：修 S1 新死局 + W2/W3 + O1/O2，build 全绿 101 单测）；围棋图标严格居中重写、推理模型额度/响应体联动 + 空回复静默汇总（09-15，两轮送审通过）
- [x] Phase 5：i18n 收尾 + 端到端测试（PVP+PVE 运行时验收合并）+ 文档更新（09-15 完成）
- [x] **Phase 5 完成（09-15）**：i18n 全量审计通过（代码用键 123 = zh_cn 123 = en_us 123，无缺键/死键/双语差异）+ 图标贴图 `go.png`（20×20，GoIcon.render(size=20) 逐像素复刻）；PVP 端到端双开发客户端（runClient=PlayerA / runClient2=PlayerB，A 内置服局域网路）全项通过：T1 房间号路+完整一局（房 618600）、第二局（房 365504）、T7 B 局中断连（13:23 挂起/判负分支）、T2/T3/T4/T6/T8 用户实测确认；全程无 GoGame 报错。**至此 Phase 0–5 全部完成**；家务收尾同日：验收进程停净、发布 jar 核查（mcphone 未打包，仅 SPI 文件名含 mcphone 字样；69 业务类 + 双语 lang + go.png + mods.toml 齐全）、README 去模板化项目化、全部改动一次提交
