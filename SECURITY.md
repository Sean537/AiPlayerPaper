# 安全政策

## 支持范围

| 版本 | 状态 |
| --- | --- |
| `1.3.x`（当前） | 接收安全修复 |
| 更早的版本 | 仅建议升级 |

版本号见 `paper-plugin/src/main/resources/plugin.yml`。

## 报告漏洞

**请不要用公开 issue 报告安全问题。**

请通过 GitHub 的 [私密漏洞报告](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
功能提交，或直接联系维护者。

请尽量提供：

- 受影响的文件或代码位置
- 复现步骤
- 影响范围与严重程度

我们会在 **7 个工作日**内确认收到。

## 这个项目怎么对待"密钥"

这一节说明的是**设计上的注意事项**，而不是需要报告的漏洞：

- AI API 密钥通过 `/aiplayer source key` 录入，用 **AES-GCM** 加密后存入 `config.yml`，
  主密钥单独放在 `plugins/AiPlayerPaper/secret.key`。
- **不要把真实密钥提交进仓库。** `config.yml` 模板里 `encrypted-key` 一律留空。
- 进服自动执行的登录命令引用密钥时用 `${secret:名字}` 占位，不要把明文密码写进 `join-commands.commands`。
  该段配置支持在日志和 `/aiplayer autocmd list` 里隐藏登录类命令的参数（`join-commands.hide-password`）。

## 权限模型

| 权限 | 默认 | 能做什么 |
| --- | --- | --- |
| `aiplayer.use` | 所有人 | 用 `/aiplayer` 及别名 `/ai` |
| `aiplayer.admin` | OP | 配置 API 源、人格、NPC、大脑、技能、处罚、reload |

以下子命令**不需要** `aiplayer.admin`，因为它们是给被影响的普通玩家用的：
`help`、`tell`、`status`、`delivery`（含 `diagnose` / `report`）。

`delivery diagnose` 会向玩家连发 6 条测试消息。请只在排查"聊天框看不到 AI 说话"时使用。