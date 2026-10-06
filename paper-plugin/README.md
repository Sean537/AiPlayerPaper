# AiPlayerPaper

这是 Minecraft 1.21.11 的 Paper 服务端插件。Paper 是服务器端的插件底座，玩家客户端不用额外安装东西。

> 仓库总览、构建方式、贡献指南见仓库根目录的 [README](../README.md)。

## 安装

1. 把 `AiPlayerPaper-<版本号>.jar` 放进服务器的 `plugins` 文件夹。
   （版本号取自 `src/main/resources/plugin.yml` 的 `version:` 字段，构建时自动对上。）
2. 启动一次服务器，让插件生成 `plugins/AiPlayerPaper/config.yml`。
3. 默认会在服务器启动后自动让 AI 玩家进入世界，不需要手动输入生成命令。
4. 用 OP 执行 `/aiplayer status` 查看它是否在线，用 `/aiplayer help` 查看全部命令。

## 构建

需要 JDK 21 和 Gradle 8+：

```sh
gradle build          # 产物：build/libs/AiPlayerPaper-<版本号>.jar
gradle printVersion   # 只打印版本号
```

`build` 会顺带校验仓库里的两份使用说明书是否一致（见 `verifyManualInSync`），
避免打进 jar 的说明书和你在 GitHub 上看到的那份悄悄跑偏。

没有 Gradle 也可以 `./build.sh --raw`，但需要自备 paper-api 及其依赖放进 `libs/`。

## 现在的 AI 玩家形态

默认 `npc.join-method: auto`，插件会依次尝试三种进服方式：

1. **协议自连（首选）**：插件在本机向服务器自己的端口发起一次真实的 TCP 连接，按 Java 版 1.21.11 的网络协议走完"握手→登录→配置→游戏"，服务器眼中就是一个从 127.0.0.1 连进来的真实玩家。
2. **内核假玩家（回退）**：直接调用 Paper 服务端内部的玩家创建流程，同样有玩家名、UUID、皮肤和 tab 列表。
3. **盔甲架（兜底）**：前两种都不被允许时使用，没有玩家身份。

真玩家形态下，AI 会：

- 出现在 tab 玩家列表里，有独立玩家名和 UUID；
- 使用玩家皮肤，而不是漂浮的名字或单独一个头颅；
- 被服务器当作在线玩家处理，能触发普通的进服/退服事件；
- 自动回复服务端保活包，避免因为没有真实客户端而被 Paper 踢出；
- 以小步移动的方式走动、转向、挥手，支持漫游、采集和按蓝图建造；
- 支持进服后自动执行命令（登录服场景），见下文。

少数带强校验的第三方插件可能只接受真实网络连接。如果某个服务端环境不允许内部假玩家创建，插件会自动退回 `armorstand` 形态，并在控制台说明原因，不会让整个插件失效。

> 历史问题说明：早期版本在 Paper 1.21.11 上会打印
> `本服无法启用协议自连（IllegalStateException: 无法从服务端注册表解析关键包 ID）` 与
> `内核反射方式不可用（NoSuchMethodException: PlayerList.remove(...))`。
> 1.21.11 把包 ID 表挪到了"协议模板"上、把玩家移除接口换成了 Adventure 组件参数，
> 现在两处都按实际服务端自动识别，不再需要改服务端。

## 自定义名称、皮肤和出生点

直接执行命令即可：

```text
/aiplayer npc name 小蓝
/aiplayer npc skin player:Notch
/aiplayer npc skin url:https://textures.minecraft.net/texture/纹理哈希
/aiplayer npc skin base64:纹理属性值
/aiplayer npc spawn
/aiplayer npc move
/aiplayer npc remove
```

说明：

- `player:玩家名` 会异步读取正版玩家皮肤；服务器需要能访问 Mojang 皮肤服务。
- `url:` 最稳妥的是 `textures.minecraft.net` 地址。普通图片网址不一定会被 Minecraft 客户端接受。
- `base64:` 填的是完整的 Mojang `textures` 属性值，不是 PNG 文件的 base64。
- 改名或换皮肤后，插件会让 AI 在原位置重新进入世界，让新资料立即生效。
- 名称最多 16 个字符。为了兼容旧插件，建议使用英文、数字和下划线；中文名称也会保留并尝试显示。

也可以直接编辑 `plugins/AiPlayerPaper/config.yml`：

```yaml
npc:
  type: player       # player=真玩家级分身；armorstand=兼容回退形态
  name: AiPlayer
  skin: ''           # 留空使用默认 Steve/Alex 外观
  slim: false
  world: world
  x: 0.5
  y: 65.0
  z: 0.5
  yaw: 0.0
  pitch: 0.0
  gamemode: survival
  auto-join: true
  wander: true
  wander-radius: 8
  move-speed: 0.1
```

改完配置后执行：

```text
/aiplayer reload
```

## 采集

```text
/aiplayer gather start
/aiplayer gather stop
```

采集范围和白名单方块在 `config.yml` 的 `behavior.gather` 里设置。AI 会走到目标方块附近、面向方块、挥手、播放破坏音效，并把掉落物生成在原地。

## 建造

```text
/aiplayer build start house
/aiplayer build stop
```

蓝图位于 `plugins/AiPlayerPaper/blueprints/*.yml`，每个方块写相对坐标和 `material`。首次启动会自动生成一个 3x3 小屋示例。

## API 配置

```text
/aiplayer source add default openai gpt-4o-mini https://api.openai.com/v1
/aiplayer source key default 你的密钥
/aiplayer channel mention default
```

支持类型：`openai`、`openai_compatible`、`anthropic`、`gemini`、`deepseek`、`ollama`。密钥会用本机生成的 AES-GCM 主密钥加密保存。

## 常见排查

- 控制台出现 `本服无法启用协议自连（...无法从服务端注册表解析关键包 ID...）`：属于旧版本插件与 Paper 1.21.11 的兼容问题，升级到本 jar 后会消失；若仍出现，说明服务端与官方 Paper 差异过大，插件会自动走内核/盔甲架回退。
- 控制台出现 `内核反射方式不可用（...PlayerList.remove...）`：同上，本版本已改为按服务端实际方法自动匹配。
- AI 一直显示"登录流程超时"：检查服务器 `online-mode`（正版验证下自连无法通过）以及 `server-ip` 是否限制了本机回环连接。
- `/aiplayer status` 显示"盔甲架"：说明当前 Paper 环境拒绝了内部假玩家创建；看控制台中的兼容性原因，或把 `npc.join-method` 设为 `self-client` 后重载再试。
- AI 生成在地下或虚空：把 `npc.world/x/y/z` 改到安全地面，或者站在目标位置执行 `/aiplayer npc spawn`。
- 换皮肤没有马上变化：`player:` 皮肤需要联网查询，等几秒后插件会自动重新登录；也可以再次执行 `/aiplayer npc spawn`。
