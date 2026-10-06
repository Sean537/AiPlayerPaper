# AI 玩家插件 使用说明书（AiPlayerPaper）

> 适用：Paper / Purpur 1.21.x 服务端（在 1.21.11 上开发验证），插件 jar：`AiPlayerPaper-1.3.0.jar`（jar 名与版本号在构建时自动对齐）
> 本版重点：**它有了自己的"人生"**。不再是你找它才动：
> - 自主大脑：没人搭话时它自己**有目的地探索地图**（走过的地方记进世界记忆，不重复瞎逛）；
> - 玩家档案：记住**每个玩家的名字、习惯、说过什么、建了什么作品**，行为基于记忆产生；
> - 实时监管全局聊天，觉得该插话就插话；**点名/私聊 100% 必回**（队列制，永不丢弃）；
> - 自己安家：在出生点附近找平地**盖房、放床，以后从床上出生**（死亡/重进都回家）；
> - 管理员角色：内置一批白名单管理命令，温和操作（查在线、治疗、变天气）自动执行，
>   重操作（踢出、封禁）必须管理员 `/aiplayer confirm`。
> 上一版能力保留：回复必看得见（送达回执 + 硬送达 + 动作栏）、私聊必答、进服自动登录命令。

---

## 1. 一页看懂：它是怎么工作的

```
你的服务器(Paper)
   │  普通插件只能造"假玩家"，很多服会拒绝
   ▼
本插件在 127.0.0.1 向服务器自己开了一个真实 TCP 连接（"协议自连"）
   │  按 Java 版 1.21.x 的网络协议走完 握手→登录→配置→游戏
   ▼
服务器眼里：一个真玩家从本机进来了
   ├─ 进 tab 列表、有皮肤、能走动、能被 /tp、能被其它插件当玩家对待
   ├─ 能执行 /login 这类进服命令（auth 服可用）
   └─ 收到 /msg 时，消息会进它的"聊天窗口"——插件的"耳朵"认读这个窗口
              │
              ▼
        调用 AI API（OpenAI / Claude / Gemini / DeepSeek / Ollama…）
              │
              ▼
        AI 生成的回复，以玩家身份发到聊天里（或直接回给你）
              │
              ▼
        插件的"嘴巴"要回执：这条到底进了服务器聊天管线没有？
              没进去 → 立刻硬送达（逐人发包 + 动作栏），保证你聊天框一定看得到
```

**为什么以前"发消息不理你、也不烧 token"**：`/msg` 私聊在服务器端不会产生任何
Bukkit 聊天事件，消息只送到 AI 客户端的聊天窗口。旧版插件没有去读那个窗口，
所以根本没调用 AI。现在加了"耳朵"（WhisperHook）专门认读收到的消息：
**凡是发给它的，一定回复，一定消耗 API 请求。**

**本版再加"大脑"（AgentBrain）**：以前没人找它，它只会原地随机晃（不消耗 token）。
现在每 20 秒它会拿着自己的地图记忆、玩家档案、刚看到的全局聊天，向 API 要一个
"下一步行动"——探索、采矿、盖家、串门、插话、执行管理命令都是这么来的，
所以即使服务器空无一人，你也能看到 `[大脑]` 日志和 token 消耗在动。

---

## 2. 安装 / 升级

1. 停服，把 `AiPlayerPaper-1.3.0.jar` 放进服务器的 `plugins/` 文件夹（同名覆盖旧版）。
2. 启动服务器。控制台出现 `AiPlayerPaper 已启动` 与
   `AI 玩家「AiPlayer」已通过协议自连以真实玩家身份进入世界` 即成功。
3. 旧配置自动升级：新增的 `chat:` 段、`persona.chat-as-player` 等键会自动补上；
   旧的"公开回复概率 35%"默认值会一次性升到 100%（日志里会说明，可再改回去）。
4. 每次开插件，数据目录里都会刷新一份本说明书：`plugins/AiPlayerPaper/使用说明书.md`。

**前提**：`server.properties` 里 `online-mode=false`（离线模式）。正版验证服上
自连客户端拿不到 Mojang 凭证，会退回"内核假玩家/盔甲架"模式。

---

## 3. 五分钟把它接上 AI（最重要）

没有可用的 API 源时，AI 收得到消息但回复不了，控制台会出现
`[AI对话] … 想说话，但没有任何启用了密钥的 API 源`，插件也会反复提醒管理员。

```
# ① 登记一个源（顺序：id 类型 模型名 接口地址）
/aiplayer source add myapi openai_compatible gpt-4o-mini https://api.openai.com/v1

# ② 存密钥（自动 AES-GCM 加密写进配置，并启用该源）
/aiplayer source key myapi sk-xxxxxxxx

# ③ 连通性自检（真正发一次请求，确认会烧 token、能收到回复）
/aiplayer source test 你好

# ④ 看状态：应显示 真玩家·协议自连（在线）+ 可用 API 源 myapi
/aiplayer status
```

常见服务商参数速查（类型都是 `openai_compatible` 或专用类型）：

| 服务商 | 类型 | 接口地址 | 模型名示例 |
|---|---|---|---|
| OpenAI | `openai` | `https://api.openai.com/v1` | `gpt-4o-mini` |
| Claude | `anthropic` | `https://api.anthropic.com` | `claude-3-5-haiku-20241022` |
| Gemini | `gemini` | `https://generativelanguage.googleapis.com` | `gemini-1.5-flash` |
| DeepSeek | `openai_compatible` | `https://api.deepseek.com/v1` | `deepseek-chat` |
| 阿里通义 | `openai_compatible` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus` |
| 本地 Ollama | `ollama` | `http://127.0.0.1:11434` | `qwen2.5:7b` |
| 其它中转/兼容站 | `openai_compatible` | 站方给的 base-url（通常以 `/v1` 结尾） | 站方列出的模型名 |

> 三个以上频道可分别指定源：`/aiplayer channel <public|private|mention> <源id>`。

---

## 4. 和它说话：三种方式

| 方式 | 例子 | 回复保证 | 回复出现在 |
|---|---|---|---|
| **私聊**（推荐） | `/msg AiPlayer 陪我聊聊` `/w /t /tell /pm 同理` | **一定回**（本版新能力） | 只有你看得见：`[「小助手」对你说] …` 或它的真实 /msg |
| **公共聊天里 @ 它** | `小助手：帮我看看这个基地` / `@AiPlayer 你好` | 一定回（名字或称呼命中即可） | 公共聊天（以玩家身份发言） |
| **普通公共聊天** | 随便聊一句 | 按 `public-reply-chance` 概率（新版默认 100%，逢聊必答） | 公共聊天 |

也可以用管理员命令行直连：`/aiplayer tell <消息>`。

小技巧：
- 有人说话时，AI 会**停止闲逛、转头看着你**（`persona.listen-pause-seconds`，默认 20 秒）。
- 同一个人 8 秒内连发多条会提示"AI 正在冷却"——想更快响应就调小 `persona cooldown 3`。
- 它叫"小助手"是**称呼**（`persona.nickname`），tab 列表里的**玩家名**是 `npc.name`（默认 AiPlayer）。
  两种方式点它名字都算 @ 到。

### 让回复更像真人（可选）

```
/aiplayer persona asplayer on     # 公共回复以真实玩家聊天形式发出（默认开），走服务器聊天管线，
                                  # 前缀插件、聊天格式都生效
/aiplayer persona wreply msg      # 回私聊也用 /msg（默认 direct=直接私发给你，
                                  # 服务器没有 msg 命令时用 direct）
```

### 送达保障：为什么"后台有回复、聊天框看不到"（本版新增）

AI 生成的文字要走过服务器聊天管线才会出现在玩家屏幕上。这条路上任何插件都能把它吞掉：
**auth 登录插件**（AI 还没 `/login` 成功就会被静音）、**聊天频道/格式化插件**（前缀插件、
分组聊天）、**广告过滤 / 反刷屏**。吞掉时 Bukkit 不会报错，所以旧版只在控制台打了一行
`[AI对话] → …`，你游戏里什么也看不见。

现在插件每发一条都会**要回执**：发出后 ~0.8 秒内，若聊天事件里没有这条、AI 客户端也没收到
它的回显，就判定"这条路不通"，立刻改用**硬送达**重发一遍。

**硬送达**不是简单调一次 `Bukkit.broadcastMessage()` 就完事——那个调用发完就不知道结果：
不统计收件人、不捕获单个玩家的异常，也不会告诉你到底送到了几个人手里。再叠一个会吞系统
消息的插件，兜底同样会静默消失，而日志照样打"已发出"（这正是"已用广播兜底发出"却仍然
什么都看不到的原因）。所以硬送达会：

- 逐个在线玩家直接发包，**统计真实收件人数**，逐个捕获异常；
- 默认**再叠一条动作栏**通道。动作栏是独立封包，聊天插件几乎拦不到它；而且它显示在屏幕
  正中，不会像聊天栏那样被"消息刷过去了根本没注意"这种人类原因藏起来。

日志长这样（`[AI嘴巴]` 就是它的"嘴"）：

```
[AI嘴巴] 你好呀，Sean537！… → 以玩家身份发言（服务器聊天管线）已确认送达（612 ms）   ← 最好情况
[AI嘴巴] 以玩家身份发言不通（聊天事件被其他插件取消），120 秒内的回复直接走硬送达…    ← 被拦了
[AI嘴巴] 硬送达（样式 bracket）：送达 7/8 人（通道：聊天栏+动作栏）｜原因：被其他插件取消…  ← 照样送到，还告诉你送到了几个人
[AI嘴巴] 硬送达（样式 bracket）：送达 0/8 人…                                          ← 一个都没到，该 diagnose 了
```

### 聊天框就是看不到？先跑诊断

服务器端看不到客户端到底画没画出来，所以要问玩家本人。一次问清哪条通道是通的：

```
/aiplayer delivery diagnose          # 向你发 6 条不同通道的探针（聊天栏/动作栏/标题/全服广播/玩家聊天事件/双通道）
/aiplayer delivery report 1,3,5      # 回报你看到了哪几条，插件据此直接告诉你该改什么
```

结论示例：

| 你看到的 | 结论 | 怎么修 |
| --- | --- | --- |
| 只有 1（聊天栏） | 玩家聊天事件被拦 | 查 auth / 频道格式 / 反刷屏插件；临时用 `delivery safe` |
| 1、2 都有 | 兜底是通的 | 什么都不用改 |
| 只有 2（动作栏） | 聊天栏被吞 | `/aiplayer persona fallback actionbar` |
| 3（标题）也能看到 | 终极兜底可用 | `/aiplayer persona fallback multi` |
| 一条都看不到 | **不是本插件的问题** | 检查客户端聊天设置（聊天选项 / F3+H），或是否有插件在 `hideChat` |

想怎么调都行：

```
/aiplayer say 测试发言                  # 一句话自检：让它说这句，并回报到底走的哪条路、送到了几个人
/aiplayer persona delivery auto        # 默认：先试玩家频道，不通自动硬送达（推荐）
/aiplayer persona delivery safe        # 永不走玩家频道，直接逐人硬送达。聊天插件拦不到它，最稳
/aiplayer persona delivery broadcast   # 固定用硬送达样式发，零延迟
/aiplayer persona delivery asplayer    # 只用玩家聊天频道，绝不兜底（聊天框最干净，可能被吞）
/aiplayer persona delivery retry       # 清掉"这条路不通"的冷静期，立刻重新尝试
/aiplayer persona fallback bracket     # 硬送达样式：蓝色 [「小助手」] 内容（默认）
/aiplayer persona fallback vanilla     # 仿原版“AiPlayer: 内容”
/aiplayer persona fallback actionbar   # 只走屏幕中央的动作栏（聊天栏被吞时的首选）
/aiplayer persona fallback title       # 动作栏 + 标题，双保险
/aiplayer persona fallback multi       # 聊天栏 + 动作栏 + 标题，全都来一遍
/aiplayer persona fallback none        # 不往公共频道发，只单独私聊回提问者
/aiplayer persona hardbar on|off       # 硬送达时是否附带动作栏（默认开）
```

> 想让 AI 真正以玩家身份说话（带前缀、进聊天格式插件），把它当成一个普通玩家放行即可：
> auth 服用 §9 的 `autocmd` 让它自动 `/login`；聊天插件的黑名单/频道里给 `AiPlayer` 开白名单。

---

## 5. 怎么确认它真的在消耗 API

看服务器控制台日志（这一版全部打了埋点）：

```
[AI耳朵] 收到私聊：Tester → 陪我聊聊            ← 耳朵听到了
[AI对话] private ← Tester：陪我聊聊（源 myapi / 模型 gpt-4o-mini）  ← 已发起请求（这一步开始烧 token）
[AI对话] → Tester（1234 ms）：好呀，聊什么？      ← AI 返回了内容
[AI嘴巴] 好呀，聊什么？ → 以玩家身份发言（服务器聊天管线）已确认送达（1350 ms）  ← 真的送到聊天框了
```

- 只出现 `[AI耳朵]` 没有 `[AI对话]`：源没配置好（看有没有"没有可用的 API 源"警告）。
- `[AI对话] 请求失败（…）：HTTP 401…`：密钥或 base-url/模型名不对 → `/aiplayer source test` 复现。
- 都没有：你发的不是"对它说的话"——用 `/msg 它的名字 …` 测，或者看 §7 排查表第 1 行。
- 有 `[AI对话] →` 但没有 `[AI嘴巴] …已确认送达`：说明它的话被服务器聊天环节吞了，
  本版会自动改用硬送达（日志里能看到真实收件人数）；`/aiplayer delivery diagnose` 可当场定位。

**本版起，即使没人发消息它也在烧 token**：控制台会周期性出现 `[大脑] 决定：…`
（每 20 秒一次决策）和 `[记忆沉淀] …`（把玩家动态浓缩成画像）。这就是"不用吝啬 token"
的部分；嫌多可以 `brain off` 或调大 `brain.tick-seconds`。

---

## 5.5 自主大脑（本版核心）

### 它怎么"有智能地探索"，而不是原地瞎逛

- 世界被切成 32×32 的格子（`brain.cell-size` 可调），每格一个"到访记录"；
- 大脑每次决策要探索时，挑**没去过的、离自己最近的**格子中心，用接近跑步的速度走过去；
- 走到后"环视一圈"：地表、高度、生物群系、天气、附近玩家/生物数量、有无村庄——
  记进 `world-memory.json`，还会花一次 API 让它给这片地方写句"印象"；
- 被地形卡住会标记"过不去"，下次不再撞同一面墙；全部探完自动改为采集/串门；
- 日志：`[大脑] 决定：ACTION: explore` → `[大脑] 出发探索 world (192,-64)`。

### 它怎么"记住每个玩家"

每个玩家一份档案（`memory/` 目录），插件自动收集：

| 记录内容 | 来源 |
|---|---|
| 说过什么 | 每条群聊/私聊都进对话记忆（哪怕它没接话） |
| 上线/下线 | 进出服事件 |
| 挖了什么矿、多久挖一次 | 破坏方块事件（按分钟聚合） |
| **作品**：在哪儿建了东西、累计多少块、常用材料 | 放置方块事件，每 12 块记一条并把坐标登记为"工地"地标 |
| 死了几次、被谁打死 | 死亡事件 |
| **画像**：习惯、爱好、和它的关系 | 每 45 分钟（`memory.consolidate-minutes`）由 AI 把上面的动态浓缩成 ≤90 字 |

这些档案会直接拼进它每次说话/决策的提示词——所以它会说"你上次那个红石机器建好了吗"
这种话。`/aiplayer memory show <玩家>` 你能看到它笔下这个人。想纠正它对你的认知：
`/aiplayer memory note <玩家> <内容>`。

### 全局聊天监管 & 100% 必回

- 每条公共聊天都进"监管流水"，大脑决策时能看到"自上次以来新增的聊天"，
  觉得有值得接的话就用 `ACTION: chat …` 插一句（有分寸，不是每条都接）；
- **点名（提到它的名字/称呼）或私聊：进"必答队列"，冷却拦不住、永不丢弃**，
  一条处理完紧接着处理下一条；`/aiplayer status` 里能看到队列积压条数；
- 玩家上线时它会翻档案主动打招呼（`brain.greet-on-join`）。

### 自动安家

- AI 进服约 25 秒后自动开始：在出生点 96 格内（`home.max-distance`）找一块
  平整、实心、不淹水、头顶无遮挡的地；
- 走过去（或传送过去），盖一间 7×5×3 的小木屋：橡木地板墙体屋顶、南面门、两侧玻璃窗、
  西北角**红床**、工作台、箱子、熔炉、屋顶嵌海晶灯；
- **床的位置设为它的重生点**：死亡、重进服都从家里醒来（配置 `npc.x/y/z` 也同步改成家门口）；
- 日志：`[安家] 选中宅基地 …，走过去开工…` → `[安家] 完成：家在 world(...)`；
- 不满意位置：`/aiplayer home set <x y z>` 到你想让它住的地方重盖，或 `home rebuild` 重新选址。

---

## 6. 命令速查表（全部以 `/aiplayer`（别名 `/ai`）开头，子命令需 aiplayer.admin 权限，help/status/tell 除外）

| 命令 | 作用 |
|---|---|
| `help` / `status` | 帮助 / 体检报告（进服方式、源、冷却、概率、私聊必答开关） |
| `tell <消息>` | 直接问 AI 一句话（不走聊天频道） |
| `say <文字>` | **送达自检**：让它说这句话，回报走的是哪条路、有没有真送到聊天框 |
| **`brain on\|off\|now\|cellsize <格>\|radius <格数>`** | **自主大脑开关；`brain now` 立刻强制思考一次（看控制台 `[大脑]`）** |
| **`home show / rebuild / goto / set <x y z> [世界]`** | **它的家：查看 / 重新选址盖房 / 传送回家 / 指定位置盖房放床** |
| `source add <id> <类型> <模型名> <接口地址>` | 登记 API 源 |
| `source key <id> <密钥>` | 保存密钥（加密）并启用该源 |
| `source list / enable / disable / remove <id>` | 管理源 |
| `source test [一句话]` | **API 连通自检**（真发一次请求并计时） |
| `channel <public\|private\|mention> <源id>` | 给三类场景分别指定源 |
| `persona nickname/tone/traits <文字>` | 称呼 / 语气 / 性格（会进系统提示） |
| `persona cooldown <秒>` | 群聊接话冷却（点名/私聊不受影响，100% 必回） |
| `persona chance <0-100>` | 公共聊天回复概率（0=只回 @ 和私聊） |
| `persona asplayer on\|off` | 公共回复是否以真实玩家身份发言 |
| `persona delivery auto\|asplayer\|broadcast\|safe\|retry` | 送达方式（auto=自动确认+硬送达，推荐；safe=永不试玩家频道，最稳） |
| `persona fallback bracket\|vanilla\|actionbar\|title\|multi\|none` | 硬送达显示样式 |
| `persona hardbar on\|off` | 硬送达时是否附带动作栏（默认开） |
| **`delivery diagnose [玩家名]`** | **聊天框看不到时先跑这个：发 6 条不同通道的探针** |
| **`delivery report <编号>`** | **回报 diagnose 里看到了哪几条，插件据此给结论** |
| `delivery retry` | 清掉冷静期，立刻重新尝试以玩家身份发言 |
| `persona verify <tick>` | 送达确认等待时长（20=1 秒） |
| `persona wreply direct\|msg` | 私聊回复方式 |
| `persona banned <词>` | AI 输出禁词过滤 |
| `npc name <名称>` | 改玩家名（自动保持位置重进服） |
| `npc skin player:名字 \| url:图片地址 \| base64:…` | 换皮肤（正版名自动取皮肤） |
| `npc spawn / move / remove` | 进服 / 传送到我这里 / 下线 |
| `npc method auto\|self-client\|kernel\|armorstand` | 进服方式（默认 auto：自连→内核→盔甲架逐级回退） |
| `secret set <名字> <内容> / list / remove` | 加密保存登录密码等敏感值 |
| `autocmd add <命令>` | 进服自动执行（`login ${secret:auth}` 这种写法） |
| `autocmd prompt add <关键词1,关键词2> <命令>` | 收到含关键词的服务器消息就回一条命令 |
| `autocmd list / remove / run / exec / on / off` | 管理与手动触发 |
| `gather start / stop` | 开始/停止在附近自动采集（白名单方块） |
| `build start <蓝图> [x y z 世界] / stop` | 按蓝图自动建造（`blueprints/*.yml`） |
| `memory show <玩家>` | **查看它跟某人的对话 + 他的画像/动态档案** |
| `memory note <玩家> <事>` | **手动塞一条关于某玩家的记忆（AI 下次说话就能引用）** |
| `memory clear <玩家>` | 清空某人的记忆 |
| `skill list / add <名> <权限0-4> <冷却秒> <命令模板> / remove` | 白名单技能（AI 提议、管理员确认才执行） |
| `confirm` / `deny` | 批准 / 取消 AI 想执行的命令 |
| `punish <玩家> mute\|unmute\|kick\|ban <分钟> <原因>` | 手动秩序处罚 |
| `reload` | 重载配置并重启 AI 进服流程 |

---

## 7. 排查表：发消息没反应怎么办（按顺序查）

| # | 检查 | 现象/命令 | 处理 |
|---|---|---|---|
| 1 | AI 是否在线 | `/aiplayer status` 显示"真玩家·协议自连（在线）" | 不在线：`npc spawn`；看 §8 警告 |
| 2 | 有没有可用源 | status 里"可用 API 源：无" | 按 §3 配 `source key` |
| 3 | API 是否通 | `/aiplayer source test 你好` | 401=密钥错；连不上=地址/网络；`openai_compatible` 的地址要带 `/v1` |
| 4 | 耳朵是否开启 | 日志有无 `[AI耳朵]` | config.yml `chat.listen-client-messages: true` |
| 5 | 你们的 msg 格式是否特殊 | AI 收到了但日志无动静 | 控制台会打出收到的原文（开 `chat.sniff-signed-chat`），把实际格式加进 `chat.whisper-patterns`（照抄一条改中文关键词，必须有 `<player>` `<text>` 两个命名组） |
| 6 | 是不是被秩序系统禁言了 | 玩家说话提示"你正处于禁言中" | `/aiplayer punish <玩家> unmute`（发广告/刷屏会被自动禁） |
| 7 | 群聊接话太频繁/太少 | 它接不接普通群聊 | `persona cooldown <秒>`、`persona chance <0-100>`；点名/私聊必回不走冷却 |
| 8 | 服务器没 /msg 命令 | 私聊测试根本发不出去 | 用公共聊天 @ 它的名字，或装 EssentialsX |
| 9 | 回复没显示给你 | 日志有 `[AI对话] →` 但你没看到 | 跑 `/aiplayer delivery diagnose`，看是客户端屏蔽还是插件在拦 |
| 10 | 聊天框里它说话没有前缀/格式 | 日志显示"硬送达" | 有插件吞了它的发言（auth 未登录 / 聊天频道插件 / 广告过滤）。要么给它放行，要么 `/aiplayer persona delivery safe` |
| 11 | 想确认最近一次到底送到没有 | `/aiplayer status` 的"最近一次发言结果" | 显示 ✔ 已确认 / △ 硬送达 N/M 人 / ✘ 未送达 |
| 12 | 广播也完全看不到 | 日志显示"送达 0/M 人" | 逐人发送全部失败，跑 `/aiplayer delivery diagnose` 定位 |

**给高级用户**：`whisper-patterns` 支持任意正则，收到文本命中即按私聊必答。
默认已覆盖 EssentialsX 中英格式（"×× 对你说："、"×× whispers to you:"、
"from ×× to you:"、箭头格式）。

---

## 8. 进服方式与相关日志

| 日志 | 含义 | 结果 |
|---|---|---|
| `正在以 1.21.x 协议自连 127.0.0.1 …` | 正常流程 | 几秒后应出现"已通过协议自连…进入世界" |
| `AI 玩家「…」已通过协议自连以真实玩家身份进入世界（会显示在 tab 列表）` | 最理想 | 无需处理，能进 tab、能被 /msg、能自动执行登录命令 |
| `登录过程中连接中断（当时处于 LOGIN 阶段，共收到 0 个包…）` | 服务器收到登录包后直接断链 | 本版已修正 1.21.9+ 登录包新增的 UUID 字段；若仍失败，开 `self-client.debug-log: true` 看握手全过程并反馈 |
| `服务器 online-mode=true …` | 正版验证服会拦自连客户端 | 改 `online-mode=false` 重启，或接受回退模式 |
| `本服无法启用协议自连（…）` | 服务端注册表解析失败 | 自动回退内核/盔甲架；若再出现请反馈 |
| `AI 玩家「…」已以真玩家身份进入世界（内核内部方式）` | 回退成功 | 也是真玩家，但依赖服务端内部类，版本更新后可能失效 |
| 形态显示"盔甲架" | 两种玩家方式都不通 | AI 只能当装饰，不能用 /msg 和进服命令；`/aiplayer npc method auto` 重试 |

想看得更细：`config.yml` 里 `self-client.debug-log: true` → 控制台会打印自连客户端
每个收到/发出的包 ID 与握手阶段，反馈问题时把这个日志发回来即可。

---

## 9. 需要登录的服务器（auth 服）

```
/aiplayer secret set auth 你的账号密码          # 加密保存
/aiplayer autocmd add login ${secret:auth}      # 每次进服自动发 /login 你的账号密码
```

更稳的是"提示响应"：等服务器先提示再回答，防密码错死循环——
```
/aiplayer autocmd prompt add 请输入指令登录,请使用 /login login ${secret:auth}
```
`autocmd list` 查看全部；`hide-password: true`（默认）保证日志里不泄露密码。
注册类服务器第一条用 `/register`，第二条用 `/login`，按顺序 `autocmd add` 两条即可。

---

## 10. 采集、建造与"它的家"

- 大脑会自己安排采集：`ACTION: gather [分钟]` → 在 `behavior.gather.materials` 白名单里
  找最近的方块，走过去、转头、挥拳破坏。手动指令 `/aiplayer gather start|stop` 仍然可用。
- 手动蓝图建造：`build start house` 按 `plugins/AiPlayerPaper/blueprints/house.yml` 逐块放置。
  蓝图格式：`blocks: [ {x:0, y:0, z:0, material:OAK_PLANKS}, ... ]`（相对起点的坐标，
  可选 `facing: SOUTH` 处理门/床/箱子朝向）。
- 采集/建造是任务态：跟它聊天只会让它停下看你，聊完任务继续。
- 它的家由"安家"模块自动建造（见 §5.5），床即重生点；`/aiplayer home goto` 把它叫回家。

---

## 11. 白名单技能：它的"管理员权限"

内置白名单（每次启动自动补齐，删了会再加回来，改了以你的为准）：

| 技能 | 模板 | 等级 | 说明 |
|---|---|---|---|
| `who_online` | `list` | 1 | 查在线 |
| `weather_clear` / `weather_rain` | `weather …` | 1 | 变天气 |
| `time_day` / `time_night` | `time set …` | 1 | 变时间 |
| `heal_player` | `effect give {player} regeneration 10 1` | 1 | 治疗 |
| `feed_player` | `effect give {player} saturation 1 4` | 1 | 喂饱 |
| `clear_effect` | `effect clear {player}` | 1 | 清状态 |
| `kick_player` | `kick {player} {reason}` | **2** | 踢出（需确认） |
| `ban_player` / `tempban_player` | `ban …` / `tempban … {duration} …` | **2** | 封禁（需确认） |
| `gamemode_survival` / `gamemode_adventure` | `gamemode … {player}` | **2** | 改模式（需确认） |
| `tp_to_ai` | `tp {player} {x} {y} {z}` | **2** | 传送（需确认） |

规则：
- 等级 ≤ `skills.autonomous-max-level`（默认 1）且 `skills.autonomous: true` 时，
  大脑可以直接自主执行（聊天里会报"[名字]（管理）我执行了：…"）；
- 等级 2 的命令只会**提议**：`[AI玩家] 我打算执行 xxx，请管理员 /aiplayer confirm 确认`；
- 自己加技能：`/aiplayer skill add <名> <等级> <冷却秒> <命令模板>`，模板里用 `{参数}` 占位，
  参数值只允许字母数字 `_:.-`（防注入，命令里不能带空格）；
- 全部执行记录进 `data/audit.log`（`SKILL-EXEC`）。

---

## 12. 配置项全解（config.yml）

| 键 | 默认 | 说明 |
|---|---|---|
| `persona.nickname` | 小助手 | 它自称的称呼，公共聊天出现即算 @ |
| `persona.cooldown-seconds` | 3 | 对同一玩家两次**群聊接话**最小间隔（点名/私聊必回，不看这个） |
| `persona.public-reply-chance` | **100** | 未 @ 它的群聊回复概率 |
| `persona.chat-as-player` | true | 以真实玩家聊天形式发言 |
| `persona.delivery-mode` | auto | auto=发完要回执、不通自动硬送达；asplayer=只用玩家频道；broadcast=只用硬送达；safe=永不试玩家频道（最稳） |
| `persona.fallback-style` | bracket | 硬送达样式：bracket / vanilla（仿原版气泡）/ actionbar / title / multi / none（只私聊回提问者） |
| `persona.hard-send-actionbar` | true | 硬送达时附带动作栏。动作栏是独立封包、聊天插件拦不到，且显示在屏幕正中 —— "广播也看不到"的根治点 |
| `persona.hard-send-title` | false | 硬送达时再附带一条居中标题（会短暂遮挡视野） |
| `persona.mention-direct-fallback` | true | `fallback-style=none` 时，被 @ 的回复仍单独私聊给提问者 |
| `persona.verify-delay-ticks` | 16 | 判定"以玩家身份发言"是否送达的等待（tick，20=1 秒） |
| `persona.asplayer-retry-cooldown-seconds` | 120 | 判定不通后，多少秒内不再白等（到点自动复测） |
| `persona.whisper-reply-mode` | direct | 回私聊方式：direct 直发 / msg 走 /msg |
| `persona.listen-pause-seconds` | 20 | 被搭话时停止闲逛的时长 |
| `chat.listen-client-messages` | true | 耳朵总开关（关掉私聊就不必答了） |
| `chat.sniff-signed-chat` | true | 普通聊天包也提取文字识别私聊格式 |
| `chat.whisper-patterns` | 5 条 | 私聊识别正则（命名组 `<player>` `<text>`） |
| `npc.join-method` | auto | auto / self-client / kernel / armorstand |
| `npc.wander` / `wander-radius` / `move-speed` | true/8/0.1 | 闲逛行为 |
| `self-client.address/port/tick-interval-ms/login-timeout-seconds/force` | 127.0.0.1/0/1000/25/false | 自连参数；port 0=自动探测 |
| `self-client.debug-log` | false | true=把自连握手收到/发出的每个包 ID 写进日志（排查进服失败用） |
| `join-commands.*` | 见文件 | 进服自动命令与提示响应 |
| `api.channels.*` / `api.sources.*` | default | 频道→源映射；源表（加密密钥） |
| **`brain.enabled`** | **true** | **自主大脑总开关（关了回到被动模式）** |
| **`brain.tick-seconds`** | **20** | **多少秒决策一次（token 消耗主开关）** |
| **`brain.greet-on-join` / `describe-areas`** | **true** | **上线打招呼 / 给新区域写"印象"** |
| **`brain.cell-size` / `explore-max-cells`** | **32 / 10** | **探索网格边长 / 单次探索半径（格）** |
| **`home.auto-build` / `max-distance`** | **true / 96** | **自动安家 / 找地范围** |
| **`behavior.explore.move-speed`** | **0.45** | **探索赶路速度（格/tick）** |
| **`skills.autonomous` / `autonomous-max-level`** | **true / 1** | **允许自主执行管理命令 / 到哪个等级** |
| **`memory.consolidate-minutes`** | **45** | **多久沉淀一份玩家画像** |
| `notify.api-key-reminder` | true | 没有密钥时定期提醒管理员 |

---

## 13. 常见问题

**Q：它会自己乱跑影响服务器性能吗？**
不。移动是低频传送，探索速度约 9 格/秒；决策循环每 20 秒一次，只是一次 API 调用。
它就是"一个玩家 + 一条 HTTP"的量。想安静：`/aiplayer brain off`。

**Q：token 消耗大概什么量级？**
本版明显更舍得花（按你的要求）：每次决策、每次新区域点评、每份玩家画像沉淀、
每次上线打招呼都是独立请求，单次上限 300 输出 token。粗算：开着默认配置一小时
约 180~300 次请求。想省：调大 `brain.tick-seconds`、`brain.describe-areas: false`、
`brain.greet-on-join: false`，或干脆 `brain off` 只保留聊天必回。

**Q：它会不会把我的私聊当众说出来？**
不会。私聊内容不进全局监管流水，私聊回复也只回给提问者（找不到人就写日志不外发）。

**Q：能同时放两个 AI 玩家吗？**
一个 jar 管一个 NPC；复制 jar 改名（改 plugin.yml 的 name 与数据目录）可跑多个实例。

**Q：AI 会不会被反作弊踢？**
自连客户端按协议正常发包（保活、传送确认、输入包都回）。若被特定反作弊拦截，
`npc method armorstand` 可先降级，并把情况反馈回来。

**Q：它说的话别人能看到吗？**
公共/mention 频道：全员可见；私聊：只有对方可见。`speakEffects` 会发音符粒子和小铃声。
**Q：控制台明明打出回复了，玩家聊天框就是看不到？**

先跑诊断，别猜：

```
/aiplayer delivery diagnose
/aiplayer delivery report 1,3,5
```

它会发 6 条不同通道的探针，你回报看到了哪几条，它直接告诉你该改什么。常见结论：

- 一条都看不到 → **不是本插件的问题**。检查客户端聊天设置（聊天框里"聊天可见性"被设成了
  仅指令/隐藏，按 `T` 打开聊天框点"选项"改回"全部"；部分客户端是 `F3+H`），或是否有插件在 `hideChat`。
- 只有聊天栏（1）看得到、玩家聊天事件（5）看不到 → 就是它在拦 AI 的发言。查 auth 登录类、
  聊天频道与格式类、反作弊与刷屏拦截、聊天过滤这几类插件。
- 只有动作栏（2）看得到 → 聊天栏被吞了：`/aiplayer persona fallback actionbar`。
- `delivery diagnose` 里 1、2、6 都看不到 → `/aiplayer persona fallback multi` 试试（加标题），
  再不行说明有插件在全局屏蔽系统消息，得从那个插件入手。

想立刻恢复"一定能看见"，用 `/aiplayer persona delivery safe`：永不走玩家频道，
逐人直接发包 + 动作栏，聊天插件拦不到。

再顺手确认一下最近一次的情况：`/aiplayer say 测试一下`，看它回你哪一种：

- `✔ 以玩家身份发言已确认送达` → 聊天管线是通的
- `△ 送达方式：硬送达｜送达 N/M 人` → 已被拦并已硬送达，N/M 就是真实收件人数
- `✘ 未送达` → AI 掉线了（`/aiplayer npc spawn` 重新进服）或你把硬送达关了
---

## 14. 文件清单

```
plugins/AiPlayerPaper/
├── config.yml            # 全部配置（升级自动补新键）
├── secret.key            # 主密钥（丢了密钥解不开，请备份）
├── 使用说明书.md          # 本文档（每次启动自动刷新）
├── blueprints/house.yml  # 示例蓝图
├── world-memory.json     # 它的"地图记忆"：去过的格子、地标、家
├── memory/…              # 每个玩家一份档案（对话+动态+画像）+ self-memory.txt（它的经历）
└── data/…                # 禁言/封禁/审计日志、技能白名单
```

祝你用得开心。有 bug 或想要的行为（比如更多私聊格式、视觉动作），把控制台里
`[AI对话]` `[AI耳朵]` 相关日志发回来就行。
