# GoGame —— mcphone 手机里的围棋 App

Minecraft **1.21.1 NeoForge** 附属模组（[mcphone](https://github.com/november521/mcphone) 1.9.3+）：在手机里加一个「围棋」App。

## 功能
- **双人 PVP**：服务端权威仲裁；6 位房间号凭号加入 + 在线邀请双匹配；掉线宽限 60s 判负
- **人机 PVE**：接 OpenAI 兼容 LLM API（GPT / 通义千问 / DeepSeek / 智谱清言四预设 + 自定义地址），11 档难度（菜鸟 / 普通人 / 初段–九段）；AI 着法经规则引擎校验，非法带反馈重试、仍非法随机兜底
- **全屏 19 路棋盘**：手机大厅页 + 全屏对局 Screen 双层界面
- **中国规则**：数子法终局（黑贴 3¾ 子）、禁自杀、打劫禁着；零合法着死局自动代虚着终局

## 安装与构建
- 运行需求：Minecraft 1.21.1 + NeoForge `[21.1.200,)` + mcphone 1.9.3+（仅客户端需要 mcphone；服务端逻辑不依赖本体）
- 构建：`gradlew build` → `build/libs/gogame-<version>.jar`，放入 `mods/` 即可
- 单测：`gradlew test`（规则引擎 + 服务端对局 + AI 解析，纯 JVM）

## 开发期双人对局验收布局
`build.gradle` 预置三个 run 配置，可同机双客户端联机：
- `gradlew runClient` —— 玩家 A（`run/`，用户名 PlayerA）
- `gradlew runClient2` —— 玩家 B（`run-client2/`，用户名 PlayerB）
- `gradlew runServer` —— 离线仲裁专用服（`run-server/`，`online-mode=false`）

## AI 配置
游戏内手机「AI 设置」页或 `run/config/gogame/config.json`（Gson 明文）：按服务方各记一套 Key / Base URL / Model，当场改当场生效；界面只显示 Key 末四位。

## 文档
- `docs/PROJECT_LOG.md` —— 各 Phase 进度与验收记录
- `docs/PITFALLS.md` —— 踩坑录（症状→根因→修复→教训）
- `docs/TECH_STACK.md` —— 技术栈与仓库说明

## Mapping Names
本模组使用 Mojang 官方映射名，其许可见 https://github.com/NeoForged/NeoForm/blob/main/Mojang.md
