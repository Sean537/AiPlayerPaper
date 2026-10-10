# 贡献指南

感谢你愿意动手。这个仓库里的东西曾经只有一个手写的 `build.sh`，很多坑（见下面的"容易踩的雷"）
都是后来才补上的，所以任何让构建可复现、让行为可验证的改动都很受欢迎。

## 开发环境

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| JDK | **21** | 插件目标就是 21，语言级别也锁死 `--release 21` |
| Gradle | **8+** | 只用来构建 Paper 插件 |

## 构建

```sh
cd paper-plugin
gradle build
# 产物：paper-plugin/build/libs/AiPlayerPaper-<版本号>.jar
```

版本号取自 `paper-plugin/src/main/resources/plugin.yml` 的 `version:` 字段 —— **这是唯一的版本来源**。
改版本请改那里，不要去动 jar 名。

日常开发时 `./gradlew` 不存在是正常的（仓库没带 wrapper）。想要可复现的 wrapper：

```sh
cd paper-plugin && gradle wrapper --gradle-version 8.10
```

## 仓库结构

- `paper-plugin/src/main/java/cn/blockforge/aiplayer/` — 插件主体
- `paper-plugin/src/main/resources/config.yml` — 配置模板（**每一项都要有中文注释**）
- `paper-plugin/src/main/resources/manual.md` — 使用说明书，构建时原样拷进 jar
-

## 提交约定

- 一个提交只做一件事，标题用祈使句（`修复…` / `新增…` / `把…改成…`）。
- 涉及行为的改动，同步更新 `config.yml` 注释、使用说明书、以及 `/aiplayer help`。
- 新增配置项必须走 `AiPlayerPlugin.ensureChatDefaults()` / `ensureBrainDefaults()` 之类的
  补默认逻辑，老用户的 `config.yml` 里没有这个键时要能自动补上，不能直接读到 `null`。

## 测试

`paper-plugin/` 目前没有单元测试，这是一个明确的已知缺口。如果你要动
`ChatDelivery` 里的判定逻辑，请至少补一个针对 `ChatDelivery.matches()` 的测试 ——
它是纯函数、依赖最少，是最容易也最该被锁住的部分。

## 容易踩的雷

这几条都真实发生过，改代码前请先读一遍：

1. **编码必须锁 UTF-8。** 源码、配置、说明书全是中文。`JavaCompile` 里已经有
   `options.encoding = 'UTF-8'`；**不要删**。没有它的话，在 Windows GBK 或 `locale=C` 的
   容器上会编出一堆替换字符，而 `javac` 照样报 BUILD SUCCESSFUL —— 上了服务器才发现聊天内容全是乱码。

2. **`processResources` 刻意不做 `expand()`。** `manual.md` 有近 3 万字中文，
   任何 `$` 或 `\` 插值都可能把它弄坏。它也不需要注入版本号。

3. **插件里调 `Player#chat()` 等于把消息交给一整条第三方管线。** 任何插件都能取消它，
   而且取消时不抛异常。所以 AI 说话必须走 `ChatDelivery` 的回执 + 硬送达路径，
   不要图省事直接 `Bukkit.broadcastMessage()`（它发完就不知道结果了）。

4. **服务端看不到客户端到底画没画出来。** 任何"我看不到消息"的排查都必须靠
   `/aiplayer delivery diagnose` + `/aiplayer delivery report` 让玩家回报，不能靠猜。

5. **调度器任务被取消 ≠ 事情没发生。** 插件重载时待确认的回执任务会被作废，
   必须调 `ChatDelivery.flushPending()` 补发，否则那几句话会静默丢失。

## 提交前自查

- [ ] `gradle build` 通过
- [ ] 新增/改名的配置项在 `config.yml` 和 `/aiplayer help` 都有说明
- [ ] 涉及发言路径的改动，手动跑过 `/aiplayer say` 和 `/aiplayer delivery diagnose`
- [ ] 没有把密钥、令牌、服务器地址写进代码或配置模板

## 行为准则

参与即表示同意 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)。