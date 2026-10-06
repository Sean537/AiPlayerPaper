package cn.blockforge.generated.paperaipaperaipaper;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.nio.file.Path;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** /aiplayer（别名 /ai）命令树。配置类子命令仅权限等级 >= 2 的管理员可用。 */
public final class AiCommands {
    private AiCommands() { }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(root()));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> root() {
        var module = AiPlayerModule.get();
        return CommandManager.literal("aiplayer")
            .then(CommandManager.literal("help")
                .executes(ctx -> help(ctx.getSource())))
            .then(CommandManager.literal("status")
                .executes(ctx -> status(ctx.getSource())))
            .then(CommandManager.literal("tell")
                .then(CommandManager.argument("message", StringArgumentType.greedyString())
                    .executes(ctx -> tell(ctx))))
            .then(CommandManager.literal("confirm")
                .executes(ctx -> module.confirm(ctx.getSource()) ? 1 : fail(ctx.getSource(), "没有待确认的操作。")))
            .then(CommandManager.literal("deny")
                .executes(ctx -> module.deny(ctx.getSource()) ? 1 : fail(ctx.getSource(), "没有待取消的操作。")))
            .then(CommandManager.literal("source")
                .requires(src -> module.isAdmin(src, 2))
                .then(CommandManager.literal("list").executes(ctx -> sourceList(ctx)))
                .then(CommandManager.literal("add")
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .then(CommandManager.argument("type", StringArgumentType.word())
                            .then(CommandManager.argument("model", StringArgumentType.word())
                                .then(CommandManager.argument("baseUrl", StringArgumentType.greedyString())
                                    .executes(ctx -> sourceAdd(ctx)))))))
                .then(CommandManager.literal("key")
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .then(CommandManager.argument("key", StringArgumentType.greedyString())
                            .executes(ctx -> sourceKey(ctx)))))
                .then(CommandManager.literal("enable")
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .executes(ctx -> sourceToggle(ctx, true))))
                .then(CommandManager.literal("disable")
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .executes(ctx -> sourceToggle(ctx, false))))
                .then(CommandManager.literal("remove")
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .executes(ctx -> sourceRemove(ctx)))))
            .then(CommandManager.literal("channel")
                .requires(src -> module.isAdmin(src, 2))
                .then(CommandManager.argument("channel", StringArgumentType.word())
                    .then(CommandManager.argument("sourceId", StringArgumentType.word())
                        .executes(ctx -> channelSet(ctx)))))
            .then(CommandManager.literal("persona")
                .requires(src -> module.isAdmin(src, 2))
                .then(CommandManager.literal("nickname")
                    .then(CommandManager.argument("name", StringArgumentType.greedyString())
                        .executes(ctx -> personaStr(ctx, 1))))
                .then(CommandManager.literal("tone")
                    .then(CommandManager.argument("tone", StringArgumentType.greedyString())
                        .executes(ctx -> personaStr(ctx, 2))))
                .then(CommandManager.literal("traits")
                    .then(CommandManager.argument("traits", StringArgumentType.greedyString())
                        .executes(ctx -> personaStr(ctx, 3))))
                .then(CommandManager.literal("cooldown")
                    .then(CommandManager.argument("seconds", IntegerArgumentType.integer(0, 3600))
                        .executes(ctx -> personaCooldown(ctx))))
                .then(CommandManager.literal("chance")
                    .then(CommandManager.argument("percent", IntegerArgumentType.integer(0, 100))
                        .executes(ctx -> personaChance(ctx))))
                .then(CommandManager.literal("banned")
                    .then(CommandManager.argument("word", StringArgumentType.greedyString())
                        .executes(ctx -> personaBanned(ctx)))))
            .then(CommandManager.literal("memory")
                .requires(src -> module.isAdmin(src, 2))
                .then(CommandManager.literal("show")
                    .then(CommandManager.argument("player", StringArgumentType.word())
                        .executes(ctx -> memoryShow(ctx))))
                .then(CommandManager.literal("clear")
                    .then(CommandManager.argument("player", StringArgumentType.word())
                        .executes(ctx -> memoryClear(ctx))))
                .then(CommandManager.literal("export")
                    .then(CommandManager.argument("player", StringArgumentType.word())
                        .executes(ctx -> memoryExport(ctx)))))
            .then(CommandManager.literal("skill")
                .requires(src -> module.isAdmin(src, 2))
                .then(CommandManager.literal("list").executes(ctx -> skillList(ctx)))
                .then(CommandManager.literal("add")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .then(CommandManager.argument("level", IntegerArgumentType.integer(0, 4))
                            .then(CommandManager.argument("cooldown", IntegerArgumentType.integer(0, 86400))
                                .then(CommandManager.argument("template", StringArgumentType.greedyString())
                                    .executes(ctx -> skillAdd(ctx)))))))
                .then(CommandManager.literal("remove")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .executes(ctx -> skillRemove(ctx)))))
            .then(CommandManager.literal("punish")
                .requires(src -> module.isAdmin(src, 2))
                .then(CommandManager.argument("target", StringArgumentType.word())
                    .then(CommandManager.literal("mute")
                        .then(CommandManager.argument("minutes", IntegerArgumentType.integer(1, 1440))
                            .then(CommandManager.argument("reason", StringArgumentType.greedyString())
                                .executes(ctx -> punish(ctx, "mute")))))
                    .then(CommandManager.literal("unmute")
                        .executes(ctx -> punish(ctx, "unmute")))
                    .then(CommandManager.literal("kick")
                        .then(CommandManager.argument("reason", StringArgumentType.greedyString())
                            .executes(ctx -> punish(ctx, "kick"))))
                    .then(CommandManager.literal("ban")
                        .then(CommandManager.argument("minutes", IntegerArgumentType.integer(1, 43200))
                            .then(CommandManager.argument("reason", StringArgumentType.greedyString())
                                .executes(ctx -> punish(ctx, "ban")))))))
            .then(CommandManager.literal("avatar")
                .requires(src -> module.isAdmin(src, 2))
                .then(CommandManager.literal("spawn").executes(ctx -> avatarSpawn(ctx)))
                .then(CommandManager.literal("remove").executes(ctx -> avatarRemove(ctx)))
                .then(CommandManager.literal("skin")
                    .then(CommandManager.argument("player", StringArgumentType.word())
                        .executes(ctx -> avatarSkin(ctx))))
                .then(CommandManager.literal("move")
                    .then(CommandManager.argument("x", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg())
                        .then(CommandManager.argument("y", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg())
                            .then(CommandManager.argument("z", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg())
                                .executes(ctx -> avatarMove(ctx)))))))
            .then(CommandManager.literal("reload")                .requires(src -> module.isAdmin(src, 2))
                .executes(ctx -> reload(ctx)));
    }

    /* ------------------------------------------------ 子命令实现 */

    private static int help(ServerCommandSource src) {
        src.sendFeedback(() -> Text.literal("AI 玩家命令：")
            .append(line("/aiplayer status · tell <消息> · confirm / deny"))
            .append(line("/aiplayer source add|key|enable|disable|remove|list（管理员）"))
            .append(line("/aiplayer channel <public|private|mention> <源id>"))
            .append(line("/aiplayer persona nickname|tone|traits|cooldown|chance|banned"))
            .append(line("/aiplayer memory show|clear|export <玩家>"))
            .append(line("/aiplayer skill list|add|remove（白名单技能）"))
            .append(line("/aiplayer punish <玩家> mute|unmute|kick|ban（分级处罚）"))
            .append(line("/aiplayer avatar spawn|remove|skin|move（形象）"))
            .append(line("/aiplayer reload")), false);
        return 1;
    }

    private static Text line(String s) {
        return Text.literal("\n" + s).formatted(Formatting.GRAY);
    }

    private static int status(ServerCommandSource src) {
        var m = AiPlayerModule.get();
        StringBuilder b = new StringBuilder();
        b.append("AI 玩家「").append(m.config.nickname).append("」状态：").append(m.state()).append("\n");
        b.append("已登记 API 源 ").append(m.config.sources.size()).append(" 个，可用源：")
            .append(m.listEnabledSources().isEmpty() ? "无（离线模式）" : String.join(", ", m.listEnabledSources())).append("\n");
        b.append("密钥以 AES-GCM 加密存放于 config/aiplayer/config.json；主密钥 config/aiplayer/secret.key。\n");
        b.append("回复冷却 ").append(m.config.replyCooldownSeconds).append(" 秒，概率 ")
            .append(m.config.replyChancePercent).append("%，记忆最近 ").append(m.config.memoryTurns).append(" 轮。\n");
        b.append("白名单技能 ").append(m.skills.all().size()).append(" 个，处罚审计日志 config/aiplayer/data/audit.log。");
        src.sendFeedback(() -> Text.literal(b.toString()).formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int tell(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) return fail(ctx.getSource(), "该命令需要玩家执行。");
        String msg = StringArgumentType.getString(ctx, "message");
        AiPlayerModule.get().handlePrivate(player, msg);
        return 1;
    }

    private static int sourceList(CommandContext<ServerCommandSource> ctx) {
        var m = AiPlayerModule.get();
        if (m.config.sources.isEmpty()) return fail(ctx.getSource(), "尚未登记任何 API 源。");
        StringBuilder b = new StringBuilder("API 源：\n");
        m.config.sources.forEach((id, s) -> b.append("  ").append(id).append(" [").append(s.type)
            .append("] ").append(s.baseUrl).append(" model=").append(s.model)
            .append(s.enabled ? "（启用）" : "（停用）").append("\n"));
        ctx.getSource().sendFeedback(() -> Text.literal(b.toString()).formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int sourceAdd(CommandContext<ServerCommandSource> ctx) {
        var m = AiPlayerModule.get();
        String id = StringArgumentType.getString(ctx, "id");
        String type = StringArgumentType.getString(ctx, "type").toLowerCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("openai", "openai_compatible", "anthropic", "gemini", "deepseek", "ollama").contains(type)) {
            return fail(ctx.getSource(), "类型必须是 openai/openai_compatible/anthropic/gemini/deepseek/ollama。");
        }
        String baseUrl = StringArgumentType.getString(ctx, "baseUrl");
        String model = StringArgumentType.getString(ctx, "model");
        m.config.sources.put(id, new AiConfig.Source(type, baseUrl, model));
        m.saveConfig();
        m.punishments.audit("CONFIG source add " + id + " " + type + " " + baseUrl + " " + model + " by " + ctx.getSource().getName());
        ctx.getSource().sendFeedback(() -> Text.literal("已登记源 " + id + "，请用 /aiplayer source key " + id
            + " <key> 设置密钥（密钥将加密保存，聊天历史中的输入记录请注意清理）。").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int sourceKey(CommandContext<ServerCommandSource> ctx) {
        var m = AiPlayerModule.get();
        String id = StringArgumentType.getString(ctx, "id");
        var s = m.config.sources.get(id);
        if (s == null) return fail(ctx.getSource(), "源不存在：" + id);
        String key = StringArgumentType.getString(ctx, "key");
        s.encryptedKey = m.secrets.encrypt(key);
        s.enabled = true;
        m.saveConfig();
        m.punishments.audit("CONFIG source key set " + id + " by " + ctx.getSource().getName());
        ctx.getSource().sendFeedback(() -> Text.literal("密钥已加密保存并启用源 " + id + "。").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int sourceToggle(CommandContext<ServerCommandSource> ctx, boolean enable) {
        var m = AiPlayerModule.get();
        String id = StringArgumentType.getString(ctx, "id");
        var s = m.config.sources.get(id);
        if (s == null) return fail(ctx.getSource(), "源不存在：" + id);
        if (enable && s.encryptedKey.isBlank()) return fail(ctx.getSource(), "请先设置密钥。");
        s.enabled = enable;
        m.saveConfig();
        ctx.getSource().sendFeedback(() -> Text.literal("源 " + id + " 已" + (enable ? "启用" : "停用") + "。"), false);
        return 1;
    }

    private static int sourceRemove(CommandContext<ServerCommandSource> ctx) {
        var m = AiPlayerModule.get();
        String id = StringArgumentType.getString(ctx, "id");
        if (m.config.sources.remove(id) == null) return fail(ctx.getSource(), "源不存在：" + id);
        m.saveConfig();
        ctx.getSource().sendFeedback(() -> Text.literal("已删除源 " + id + "。"), false);
        return 1;
    }

    private static int channelSet(CommandContext<ServerCommandSource> ctx) {
        var m = AiPlayerModule.get();
        String channel = StringArgumentType.getString(ctx, "channel").toLowerCase(java.util.Locale.ROOT);
        String sourceId = StringArgumentType.getString(ctx, "sourceId");
        if (!java.util.Set.of("public", "private", "mention").contains(channel)) {
            return fail(ctx.getSource(), "频道只能是 public/private/mention。");
        }
        if (!m.config.sources.containsKey(sourceId)) return fail(ctx.getSource(), "源不存在：" + sourceId);
        m.config.channelSources.put(channel, sourceId);
        m.saveConfig();
        ctx.getSource().sendFeedback(() -> Text.literal("频道 " + channel + " 现在使用模型源 " + sourceId + "。").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int personaStr(CommandContext<ServerCommandSource> ctx, int which) {
        var m = AiPlayerModule.get();
        String v = StringArgumentType.getString(ctx, which == 1 ? "name" : which == 2 ? "tone" : "traits");
        switch (which) {
            case 1 -> m.config.nickname = v;
            case 2 -> m.config.tone = v;
            default -> m.config.traits = v;
        }
        m.saveConfig();
        ctx.getSource().sendFeedback(() -> Text.literal("人格已更新。"), false);
        return 1;
    }

    private static int personaCooldown(CommandContext<ServerCommandSource> ctx) {
        var m = AiPlayerModule.get();
        m.config.replyCooldownSeconds = IntegerArgumentType.getInteger(ctx, "seconds");
        m.saveConfig();
        ctx.getSource().sendFeedback(() -> Text.literal("回复冷却已设为 " + m.config.replyCooldownSeconds + " 秒。"), false);
        return 1;
    }

    private static int personaChance(CommandContext<ServerCommandSource> ctx) {
        var m = AiPlayerModule.get();
        m.config.replyChancePercent = IntegerArgumentType.getInteger(ctx, "percent");
        m.saveConfig();
        ctx.getSource().sendFeedback(() -> Text.literal("随机回复概率已设为 " + m.config.replyChancePercent + "%。"), false);
        return 1;
    }

    private static int personaBanned(CommandContext<ServerCommandSource> ctx) {
        var m = AiPlayerModule.get();
        String w = StringArgumentType.getString(ctx, "word");
        m.config.bannedWords.add(w);
        m.saveConfig();
        ctx.getSource().sendFeedback(() -> Text.literal("禁词「" + w + "」已加入 AI 输出过滤。"), false);
        return 1;
    }

    private static ServerPlayerEntity findPlayer(CommandContext<ServerCommandSource> ctx, String name) {
        return ctx.getSource().getServer().getPlayerManager().getPlayer(name);
    }

    private static int memoryShow(CommandContext<ServerCommandSource> ctx) {
        var m = AiPlayerModule.get();
        ServerPlayerEntity p = findPlayer(ctx, "player");
        if (p == null) return fail(ctx.getSource(), "玩家不在线。");
        var prof = m.memory.get(p.getUuid());
        StringBuilder b = new StringBuilder();
        b.append("长期摘要：").append(prof.summary.isEmpty() ? "（暂无，可用 AI 定期生成）" : prof.summary).append("\n");
        b.append("标签：").append(prof.tags.isEmpty() ? "（无）" : String.join(", ", prof.tags)).append("\n");
        b.append("关键事件 ").append(prof.important.size()).append(" 条；最近对话：\n");
        int i = 0;
        for (var t : prof.recent) {
            if (i++ >= 6) { b.append("……"); break; }
            b.append("  ").append(t.speaker()).append(": ").append(t.text()).append("\n");
        }
        ctx.getSource().sendFeedback(() -> Text.literal(b.toString()).formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int memoryClear(CommandContext<ServerCommandSource> ctx) {
        var m = AiPlayerModule.get();
        ServerPlayerEntity p = findPlayer(ctx, "player");
        if (p == null) return fail(ctx.getSource(), "玩家不在线。");
        m.memory.clear(p.getUuid());
        m.punishments.audit("MEMORY clear " + p.getName().getString() + " by " + ctx.getSource().getName());
        ctx.getSource().sendFeedback(() -> Text.literal("已清除 " + p.getName().getString() + " 的记忆。").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int memoryExport(CommandContext<ServerCommandSource> ctx) {
        var m = AiPlayerModule.get();
        ServerPlayerEntity p = findPlayer(ctx, "player");
        if (p == null) return fail(ctx.getSource(), "玩家不在线。");
        Path out = m.memory.export(p.getUuid(), p.getName().getString());
        if (out == null) return fail(ctx.getSource(), "导出失败。");
        ctx.getSource().sendFeedback(() -> Text.literal("记忆已导出：" + out).formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int skillList(CommandContext<ServerCommandSource> ctx) {
        var m = AiPlayerModule.get();
        if (m.skills.all().isEmpty()) return fail(ctx.getSource(), "白名单为空。");
        StringBuilder b = new StringBuilder("白名单技能：\n");
        m.skills.all().forEach((name, s) -> b.append("  ").append(name).append(" → ").append(s.commandTemplate)
            .append("（需要权限 ").append(s.permissionLevel).append("，冷却 ").append(s.cooldownSeconds).append("s）\n"));
        ctx.getSource().sendFeedback(() -> Text.literal(b.toString()).formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int skillAdd(CommandContext<ServerCommandSource> ctx) {
        var m = AiPlayerModule.get();
        String name = StringArgumentType.getString(ctx, "name");
        int level = IntegerArgumentType.getInteger(ctx, "level");
        int cooldown = IntegerArgumentType.getInteger(ctx, "cooldown");
        String template = StringArgumentType.getString(ctx, "template");
        if (name.length() > 32) return fail(ctx.getSource(), "技能名过长。");
        m.skills.add(name, template, level, cooldown);
        m.punishments.audit("SKILL add " + name + " " + template + " by " + ctx.getSource().getName());
        ctx.getSource().sendFeedback(() -> Text.literal("技能 " + name + " 已登记：" + template).formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int skillRemove(CommandContext<ServerCommandSource> ctx) {
        var m = AiPlayerModule.get();
        if (!m.skills.remove(StringArgumentType.getString(ctx, "name"))) return fail(ctx.getSource(), "技能不存在。");
        ctx.getSource().sendFeedback(() -> Text.literal("技能已移除。"), false);
        return 1;
    }

    private static int punish(CommandContext<ServerCommandSource> ctx, String type) {
        var m = AiPlayerModule.get();
        ServerPlayerEntity admin = ctx.getSource().getPlayer();
        if (admin == null) return fail(ctx.getSource(), "需要玩家执行。");
        String target = StringArgumentType.getString(ctx, "target");
        int minutes = 0;
        try { minutes = IntegerArgumentType.getInteger(ctx, "minutes"); } catch (IllegalArgumentException ignored) { }
        String reason = "管理员处罚";
        try { reason = StringArgumentType.getString(ctx, "reason"); } catch (IllegalArgumentException ignored) { }
        m.manualPunish(admin, target, type, minutes == 0 ? 30 : minutes, reason);
        ctx.getSource().sendFeedback(() -> Text.literal("已对 " + target + " 执行 " + type + "。").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int avatarSpawn(CommandContext<ServerCommandSource> ctx) {
        var m = AiPlayerModule.get();
        ServerPlayerEntity p = ctx.getSource().getPlayer();
        if (p == null) return fail(ctx.getSource(), "需要玩家执行。");
        m.spawnAvatarAt(p.getEntityWorld() instanceof ServerWorld w ? w : ctx.getSource().getServer().getOverworld(),
            p.getX() + 2, p.getY(), p.getZ());
        ctx.getSource().sendFeedback(() -> Text.literal("AI 形象已在身边生成。").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int avatarRemove(CommandContext<ServerCommandSource> ctx) {
        AiPlayerModule.get().removeAvatar();
        ctx.getSource().sendFeedback(() -> Text.literal("AI 形象已移除。"), false);
        return 1;
    }

    private static int avatarSkin(CommandContext<ServerCommandSource> ctx) {
        var m = AiPlayerModule.get();
        ServerPlayerEntity p = findPlayer(ctx, "player");
        if (p == null) return fail(ctx.getSource(), "玩家不在线，无法取皮肤。");
        m.applySkinFrom(p);
        ctx.getSource().sendFeedback(() -> Text.literal("AI 形象已换成 " + p.getName().getString() + " 的皮肤。").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int avatarMove(CommandContext<ServerCommandSource> ctx) {
        var m = AiPlayerModule.get();
        double x = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "x");
        double y = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "y");
        double z = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "z");
        m.moveAvatar(ctx.getSource().getServer().getOverworld(), x, y, z);
        ctx.getSource().sendFeedback(() -> Text.literal("AI 形象已移动到 " + x + ", " + y + ", " + z + "。"), false);
        return 1;
    }

    private static int reload(CommandContext<ServerCommandSource> ctx) {
        var m = AiPlayerModule.get();
        m.config = AiConfig.load(m.dir());
        ctx.getSource().sendFeedback(() -> Text.literal("配置已重新加载。").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int fail(ServerCommandSource src, String msg) {
        src.sendError(Text.literal(msg));
        return 0;
    }

    /** 占位：DoubleArgumentType 直接用官方类，此壳仅为可读性。 */
    private static final class DoubleArgumentTypeWrapper {
        static com.mojang.brigadier.arguments.DoubleArgumentType doubleArg() {
            return com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg();
        }
    }
}
