# AiPlayerPaper

一个 Paper/Spigot 插件：在服务器里放一个**真的能看见、能记住、会说话**的 AI 玩家。

它不是简单的"接个 API 复读机"。AI 以一个真实客户端身份进服（tab 列表里有它、别的插件按玩家对待它），
自己会探索、记地图、记人、安家盖房、点名必答，并且**能确认自己说的话到底有没有被玩家看到**。

> 详细说明见 [`paper-plugin/使用说明书.md`](paper-plugin/使用说明书.md)（插件首次启动会自动释放到
> `plugins/AiPlayerPaper/使用说明书.md`）。

---

## 仓库结构

仓库里有两个相互独立的产物，各自有独立构建：

| 路径 | 产物 | 构建方式 |
| --- | --- | --- |
| `paper-plugin/` | **AiPlayerPaper** —— Paper/Spigot 插件（主项目） | `cd paper-plugin && gradle build` |
| `src/` | 早期生成的 Fabric mod 骨架（`paper_ai_paper_ai_paper`） | `./gradlew build` |

两者不共享构建，也不互相依赖。日常开发只需要动 `paper-plugin/`。

---

## 它能做什么

- **自主大脑** — 没人找它的时候它也活着：按节奏醒过来看地图记忆、玩家档案和全局聊天，
  自己决定下一步（探索 / 采矿 / 建家 / 回家 / 串门 / 插话 / 执行白名单管理命令）。
- **真·玩家身份** — 默认用"协议自连"进服：在回环地址向服务器自己的端口发起一次完整登录。
  服务器眼中这就是个真人玩家，不依赖服务端内部类，因此不会因为版本签名变化就整个失效。
  另有内核反射、盔甲架两级回退。
- **点名必答** — 群里 @ 它、或 `/msg` 它，一定回，进高优先队列，不受冷却和排队上限影响。
- **安家** — 自己找一块平整干燥的平地盖房、放床设重生点，以后从家里出生。
- **发言送达回执** — 见下一节，这是本项目最较真的地方。

---

## 关于"AI 说话但玩家看不见"

这是所有自建 AI 机器人插件最常见的现场问题，值得单独说明，因为**真正的原因通常不在插件自己身上**。

`Player#chat()` 走的是服务器聊天管线（`AsyncChatEvent`）。这条路上任何一个插件都能把消息取消掉：

- auth / 登录类插件（AI 没过验证 → 整条消息被丢）
- 聊天频道与格式插件（把不在频道内的发言静默丢弃）
- 反作弊与刷屏拦截、聊天过滤

Bukkit 在取消时**不抛任何异常**。于是原始症状是：控制台日志里 AI 回得好好的，游戏聊天框里一个字都没有。

### 本插件的做法

1. **先试玩家频道，登记回执。** 发言时登记一条待确认记录，用**指纹比对**认领回执
   （聊天插件会加前缀、改格式、截断，按整条文本匹配必然失真）。
2. **确认不了就硬送达。** 逐个在线玩家直接发包，**统计真实收件人数、逐个捕获异常**，
   并且默认再叠一条**动作栏**通道。
3. **动作栏是关键。** 它是独立封包，聊天插件几乎拦不到它；而且显示在屏幕正中，
   不会被"消息刷过去了根本没注意"这种人类原因藏起来。

> 上一版用的是 `Bukkit.broadcastMessage()` 兜底。它是个**发完就不知道结果的调用** —— 不统计收件人、
> 不捕获单个玩家的异常、也不会告诉你到底送到了几个人手里。再叠一个会吞系统消息的插件，
> 兜底同样会静默消失，而日志照样打"已发出"。这正是"已用广播兜底发出"却仍然什么都看不到的原因。

### 排查：一次问清哪条通道是通的

服务器端看不到客户端到底画没画出来，所以要问玩家本人：

```
/aiplayer delivery diagnose          # 向你发 6 条不同通道的探针
/aiplayer delivery report 1,3,5      # 回报你看到了哪几条，插件据此给结论
```

结论会直接告诉你该改什么（例如"聊天栏不通但动作栏通 → `/aiplayer persona fallback multi`"）。

### 配置

```yaml
persona:
  delivery-mode: auto        # auto | asplayer | broadcast | safe
  fallback-style: bracket    # bracket | vanilla | actionbar | title | multi | none
  hard-send-actionbar: true  # 硬送达时附带动作栏（"广播也看不到"的根治点，默认开）
  hard-send-title: false     # 是否再附带居中标题
```

`delivery-mode: safe` = 永不走玩家频道，直接逐人硬送达。聊天/登录插件拦不到它。

也可以全部用命令改（见 `/aiplayer help`）：

```
/aiplayer persona delivery safe|auto|asplayer|broadcast
/aiplayer persona fallback bracket|vanilla|actionbar|title|multi|none
/aiplayer persona hardbar on|off
```

---

## 构建

需要 **JDK 21** 和 **Gradle 8+**。

```sh
# Paper 插件
cd paper-plugin
gradle build
# 产物：paper-plugin/build/libs/AiPlayerPaper-<版本号>.jar
# 版本号取自 paper-plugin/src/main/resources/plugin.yml 的 version: 字段
```

没有 Gradle 也可以，但需要自备 paper-api 及其传递依赖：

```sh
cd paper-plugin
mkdir -p libs          # 把 paper-api 和依赖都放进来
./build.sh --raw
```

## 安装

1. 需要 Java 21 的 Paper/Spigot 服务端。
2. 把 jar 丢进 `plugins/`。
3. 启动，配置 `plugins/AiPlayerPaper/config.yml`。
4. `/aiplayer source add default openai <模型名> <接口地址>`
5. `/aiplayer source key default <你的密钥>`（密钥用 AES-GCM 加密落盘，主密钥在 `plugins/AiPlayerPaper/secret.key`）
6. `/aiplayer source test` 自检连通性。

完整步骤见使用说明书。

---

## 贡献

见 [CONTRIBUTING.md](CONTRIBUTING.md)。行为准则见 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)，
安全问题的报告方式见 [SECURITY.md](SECURITY.md)。

## 许可

见 [LICENSE](LICENSE)。第三方 Minecraft / 加载器组件各自保留其原有许可。