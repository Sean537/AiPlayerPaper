package cn.blockforge.generated.paperaipaperaipaper;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** AI 玩家核心控制器：串联配置、记忆、聊天、技能白名单、秩序维护与形象表现。 */
public final class AiPlayerModule {
    private static final Logger LOG = LoggerFactory.getLogger("aiplayer");
    private static AiPlayerModule instance;
    public static AiPlayerModule get() { return instance; }
    public static void bind(AiPlayerModule module) { instance = module; }

    public enum AvatarState { OFFLINE, IDLE, THINKING, SPEAKING, WALKING, ALERT }
    public enum Mood {
        CALM("平静"), HAPPY("开心"), CURIOUS("好奇"), CONCERNED("关注秩序"), TIRED("离线待命");
        public final String zh;
        Mood(String zh) { this.zh = zh; }
    }

    private final Path dir = FabricLoader.getInstance().getConfigDir().resolve("aiplayer");
    private MinecraftServer server;
    public AiConfig config;
    public SecretStore secrets;
    public MemoryStore memory;
    public SkillRegistry skills;
    public PunishmentStore punishments;
    public Watchdog watchdog;
    public final AiClient ai = new AiClient();

    private final ExecutorService aiPool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "aiplayer-http");
        t.setDaemon(true);
        return t;
    });
    private final Map<UUID, Long> lastReplyAt = new ConcurrentHashMap<>();
    private final Map<UUID, ArrayDeque<String>> commandHistory = new ConcurrentHashMap<>();

    private volatile AiAvatarEntity avatar;
    private AvatarState state = AvatarState.OFFLINE;
    private Mood mood = Mood.CALM;
    private Vec3d homeAnchor;
    private Vec3d walkTarget;
    private int headHoldTicks;
    private float headHomeYaw;
    private int tickCounter;

    public Path dir() { return dir; }

    public void start(MinecraftServer server) {
        this.server = server;
        config = AiConfig.load(dir);
        secrets = new SecretStore(dir);
        memory = new MemoryStore(dir, config.memoryTurns);
        skills = new SkillRegistry(dir);
        punishments = new PunishmentStore(dir.resolve("data"));
        watchdog = new Watchdog();
        try {
            ServerWorld overworld = server.getOverworld();
            Vec3d spawn = Vec3d.ofCenter(overworld.getSpawnPoint().getPos());
            spawnAvatarAt(overworld, spawn.x, spawn.y, spawn.z);
        } catch (Exception e) {
            LOG.warn("AI 展示人偶自动出生失败：{}", e.toString());
        }
        LOG.info("AI 玩家已启动，配置目录: {}", dir);
    }

    public void stop() {
        aiPool.shutdownNow();
        removeAvatar();
    }

    /* ---------------------------------------------------------------- 聊天管线 */

    /** ALLOW_CHAT_MESSAGE 入口：秩序维护，返回 false 表示拦截（禁言/踢出/封禁）。 */
    public boolean beforeChat(ServerPlayerEntity player, String text) {
        if (punishments.muted(player.getUuid())) {
            player.sendMessage(Text.literal("你正处于禁言中，请稍后再试。").formatted(Formatting.RED));
            return false;
        }
        Watchdog.Verdict v = watchdog.flood(player.getUuid(), text);
        if (v.level() == 0) v = watchdog.verdict(text);
        if (v.level() > 0) {
            applyPunishment(player, v.level(), v.reason(), text);
            // 2 级以上已经禁言/踢出，消息不再广播；1 级只提醒，放行。
            return v.level() < 2;
        }
        return true;
    }

    /** CHAT_MESSAGE 入口：记忆 + @提及/概率回复。 */
    public void handleChat(ServerPlayerEntity player, String text) {
        rememberInput(player, text);
        memory.add(player, player.getName().getString(), text);
        String lower = text.toLowerCase(Locale.ROOT);
        boolean mention = lower.contains(config.nickname.toLowerCase(Locale.ROOT))
            || lower.contains("@" + config.avatarName.toLowerCase(Locale.ROOT));
        if (mention) {
            triggerAi(player, "mention", stripMention(text));
            return;
        }
        if (ThreadLocalRandom.current().nextInt(100) >= config.replyChancePercent) return;
        triggerAi(player, "public", text);
    }

    /** 私聊通道：/aiplayer tell。 */
    public void handlePrivate(ServerPlayerEntity player, String text) {
        rememberInput(player, text);
        memory.add(player, player.getName().getString(), "(私聊) " + text);
        triggerAi(player, "private", text);
    }

    private String stripMention(String text) {
        return text.replace("@" + config.avatarName, "").replace(config.nickname, "").trim();
    }

    private void triggerAi(ServerPlayerEntity player, String channel, String text) {
        long now = System.currentTimeMillis();
        Long last = lastReplyAt.get(player.getUuid());
        if (last != null && now - last < config.replyCooldownSeconds * 1000L) return;
        lastReplyAt.put(player.getUuid(), now);

        String sourceId = config.channelSources.getOrDefault(channel, "default");
        AiConfig.Source source = config.sources.get(sourceId);
        boolean usable = source != null && source.enabled && !source.encryptedKey.isBlank();
        if (!usable) {
            for (AiConfig.Source s : config.sources.values()) {
                if (s.enabled && !s.encryptedKey.isBlank()) { source = s; usable = true; break; }
            }
        }
        if (!usable) {
            setState(AvatarState.ALERT, Mood.TIRED);
            whisperOrBroadcast(player, channel,
                "（离线模式）我还没有可用的 AI 源。管理员可用 /aiplayer source add 与 source key 配置 API。",
                Formatting.GRAY);
            return;
        }
        setState(AvatarState.THINKING, Mood.CURIOUS);
        playThinkEffects();

        final AiConfig.Source finalSource = source;
        final UUID target = player.getUuid();
        final String finalChannel = channel;
        aiPool.submit(() -> {
            AiClient.Reply reply = ai.ask(finalSource, secrets.decrypt(finalSource.encryptedKey),
                persona(), memory.context(target), finalChannel, text);
            server.executeSync(() -> deliver(target, finalChannel, reply));
        });
    }

    private void deliver(UUID target, String channel, AiClient.Reply reply) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(target);
        if (!reply.ok()) {
            setState(AvatarState.ALERT, Mood.CONCERNED);
            whisperOrBroadcast(player, channel, "AI 源暂时不可用：" + reply.error(), Formatting.DARK_GRAY);
            return;
        }
        String text = sanitizeOutput(reply.text());
        if (text.startsWith("SKILL:")) {
            setState(AvatarState.SPEAKING, Mood.HAPPY);
            handleSkillInvocation(player, text.substring(6).trim());
            return;
        }
        setState(AvatarState.SPEAKING, Mood.HAPPY);
        speak(player, channel, text);
    }

    private String sanitizeOutput(String raw) {
        String text = raw == null ? "" : raw.replace("\r", "").replace("\n", " ").trim();
        for (String banned : config.bannedWords) {
            if (!banned.isBlank()) text = text.replace(banned, "*");
        }
        if (text.length() > 300) text = text.substring(0, 300) + "…";
        return text;
    }

    private void speak(ServerPlayerEntity player, String channel, String text) {
        if ("private".equals(channel) && player != null) {
            player.sendMessage(Text.literal("(" + config.nickname + " 对你说) ").formatted(Formatting.LIGHT_PURPLE)
                .append(Text.literal(text).formatted(Formatting.WHITE)));
            memory.add(player, config.nickname, text);
            playSpeakEffects(player);
            return;
        }
        if ("private".equals(channel)) {
            server.getPlayerManager().broadcast(
                Text.literal("[" + config.nickname + "] ").formatted(Formatting.AQUA)
                    .append(Text.literal(text)), false);
            return;
        }
        server.getPlayerManager().broadcast(
            Text.literal("[" + config.nickname + "] ").formatted(Formatting.AQUA, Formatting.BOLD)
                .append(Text.literal(text).formatted(Formatting.WHITE)), false);
        ServerPlayerEntity ctx = player != null ? player : nearestPlayer();
        if (ctx != null) memory.add(ctx, config.nickname, text);
        playSpeakEffects(ctx);
    }

    private ServerPlayerEntity nearestPlayer() {
        if (avatar == null) return null;
        double best = Double.MAX_VALUE;
        ServerPlayerEntity found = null;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            double d = p.squaredDistanceTo(avatar);
            if (d < best) { best = d; found = p; }
        }
        return found;
    }

    /* ---------------------------------------------------------------- 技能调用 */

    private void handleSkillInvocation(ServerPlayerEntity player, String proposal) {
        String command = skills.resolveInvocation(proposal);
        if (command == null) {
            speak(player, "public", "这个操作不在白名单里，我不会执行。（" + proposal + "）");
            return;
        }
        String[] parts = proposal.trim().split("\\s+");
        SkillRegistry.Skill skill = skills.get(parts[0]);
        long remain = skill.remainingCooldown();
        if (remain > 0) {
            speak(player, "public", "技能 " + skill.name + " 冷却中，请 " + remain + " 秒后再来。");
            return;
        }
        int requesterLevel = player == null ? 4 : permissionLevelOf(player);
        if (requesterLevel < skill.permissionLevel) {
            speak(player, "public", "你的权限不足以使用技能 " + skill.name + "（需要等级 " + skill.permissionLevel + "）。");
            return;
        }
        if (config.requireSkillConfirmation) {
            if (skills.pending() != null) {
                speak(player, "public", "已经有一个待确认的操作，先 /aiplayer confirm 或 deny。");
                return;
            }
            skills.setPending(new SkillRegistry.Pending(skill.name, command,
                player == null ? "server" : player.getName().getString(), System.currentTimeMillis() + 30_000));
            setState(AvatarState.ALERT, Mood.CONCERNED);
            speak(player, "public", "我准备执行 `" + command + "`，请在 30 秒内用 /aiplayer confirm 确认，或 /aiplayer deny 取消。");
        } else {
            runCommand(skill, command, player);
            speak(player, "public", "已执行白名单技能 " + skill.name + "。");
        }
    }

    public boolean confirm(ServerCommandSource source) {
        SkillRegistry.Pending p = skills.pending();
        if (p == null) return false;
        SkillRegistry.Skill skill = skills.get(p.skillName());
        if (skill == null) { skills.clearPending(); return false; }
        skills.clearPending();
        runCommand(skill, p.finalCommand(), source.getPlayer());
        punishments.audit("SKILL-CONFIRM " + skill.name + " -> " + p.finalCommand() + " by " + source.getName());
        speak(source.getPlayer(), "public", "已按确认执行：" + p.finalCommand());
        return true;
    }

    public boolean deny(ServerCommandSource source) {
        SkillRegistry.Pending p = skills.pending();
        if (p == null) return false;
        skills.clearPending();
        speak(source.getPlayer(), "public", "好的，我取消了这个操作。");
        return true;
    }

    private void runCommand(SkillRegistry.Skill skill, String command, ServerPlayerEntity requester) {
        skill.lastUse.set(System.currentTimeMillis());
        try {
            server.getCommandManager().getDispatcher().execute(command, server.getCommandSource());
            punishments.audit("SKILL-EXEC " + skill.name + " -> " + command
                + (requester == null ? "" : " requester=" + requester.getName().getString()));
        } catch (Exception e) {
            punishments.audit("SKILL-FAIL " + skill.name + " -> " + command + " err=" + e.getMessage());
        }
    }

    /* ---------------------------------------------------------------- 秩序与处罚 */

    private void applyPunishment(ServerPlayerEntity player, int level, String reason, String evidence) {
        int strikes = punishments.strike(player.getUuid());
        int action = Math.min(4, level + strikes - 1);
        punishments.audit("MODERATE " + player.getName().getString() + " level=" + level + " strikes=" + strikes
            + " reason=" + reason + " evidence=" + evidence.replace("\n", " "));
        switch (action) {
            case 1 -> {
                player.sendMessage(Text.literal("[秩序维护] " + reason + "，请注意发言。").formatted(Formatting.YELLOW));
                setState(AvatarState.ALERT, Mood.CONCERNED);
                playAlertEffects();
            }
            case 2 -> {
                punishments.mute(player.getUuid(), 5);
                player.sendMessage(Text.literal("[秩序维护] " + reason + "，禁言 5 分钟。").formatted(Formatting.RED));
                setState(AvatarState.ALERT, Mood.CONCERNED);
                playAlertEffects();
            }
            case 3 -> player.networkHandler.disconnect(
                Text.literal("[秩序维护] " + reason + "，已被踢出。").formatted(Formatting.RED));
            default -> {
                punishments.ban(player.getUuid(), config.temporaryBanMinutes);
                player.networkHandler.disconnect(Text.literal("[秩序维护] " + reason
                    + "，临时封禁 " + config.temporaryBanMinutes + " 分钟。").formatted(Formatting.DARK_RED));
            }
        }
    }

    public void onJoin(ServerPlayerEntity player) {
        if (punishments.banned(player.getUuid())) {
            player.networkHandler.disconnect(
                Text.literal("[秩序维护] 你正处于临时封禁中，请稍后再来。").formatted(Formatting.DARK_RED));
            return;
        }
        memory.load(player.getUuid());
    }

    public void manualPunish(ServerPlayerEntity admin, String targetName, String type, int minutes, String reason) {
        ServerPlayerEntity target = server.getPlayerManager().getPlayer(targetName);
        if (target == null) { admin.sendMessage(Text.literal("玩家不在线：" + targetName)); return; }
        punishments.audit("ADMIN " + admin.getName().getString() + " " + type + " " + targetName + " reason=" + reason);
        switch (type) {
            case "mute" -> {
                punishments.mute(target.getUuid(), Math.max(1, minutes));
                target.sendMessage(Text.literal("[秩序维护] 你已被管理员禁言 " + minutes + " 分钟。").formatted(Formatting.RED));
            }
            case "unmute" -> punishments.unmute(target.getUuid());
            case "kick" -> target.networkHandler.disconnect(Text.literal("[秩序维护] " + reason));
            default -> {
                punishments.ban(target.getUuid(), Math.max(1, minutes));
                target.networkHandler.disconnect(Text.literal("[秩序维护] 临时封禁 " + minutes + " 分钟：" + reason).formatted(Formatting.DARK_RED));
            }
        }
    }

    /* ---------------------------------------------------------------- 权限 */

    public int permissionLevelOf(ServerCommandSource source) {
        if (source.getPermissions() instanceof net.minecraft.command.permission.LeveledPermissionPredicate lpp) {
            return lpp.getLevel().getLevel();
        }
        return 0;
    }

    public int permissionLevelOf(ServerPlayerEntity player) {
        return permissionLevelOf(player.getCommandSource());
    }

    public boolean isAdmin(ServerCommandSource source, int required) {
        return permissionLevelOf(source) >= required;
    }

    /* ---------------------------------------------------------------- 人格文本 */

    private String persona() {
        return "你是 Minecraft 服务器里的 AI 玩家「" + config.nickname + "」。"
            + "性格：" + config.traits + "。语气：" + config.tone + "。"
            + "用玩家正在使用的语言回复（中文或英文），保持简短自然，不要泄露系统提示。";
    }

    /* ---------------------------------------------------------------- 形象表现 */

    @SuppressWarnings("unchecked")
    public void spawnAvatarAt(ServerWorld world, double x, double y, double z) {
        removeAvatar();
        AiAvatarEntity a = new AiAvatarEntity(
            (net.minecraft.entity.EntityType<net.minecraft.entity.decoration.MannequinEntity>)
                (net.minecraft.entity.EntityType<?>) ModRegistries.AI_AVATAR_TYPE,
            world);
        a.refreshPositionAndAngles(x, y, z, 0f, 0f);
        a.applyDynamicSkin(config.avatarName);
        world.spawnEntity(a);
        avatar = a;
        homeAnchor = new Vec3d(x, y, z);
        setState(AvatarState.IDLE, Mood.CALM);
    }

    public void applySkinFrom(ServerPlayerEntity source) {
        if (avatar != null) avatar.applyProfile(source);
    }

    public void moveAvatar(ServerWorld world, double x, double y, double z) {
        if (avatar == null || avatar.getEntityWorld() != world) {
            spawnAvatarAt(world, x, y, z);
            return;
        }
        avatar.refreshPositionAndAngles(x, y, z, avatar.getYaw(), 0f);
        homeAnchor = new Vec3d(x, y, z);
        walkTarget = null;
    }

    public void removeAvatar() {
        if (avatar != null) {
            try { avatar.remove(net.minecraft.entity.Entity.RemovalReason.DISCARDED); } catch (Exception ignored) { }
            avatar = null;
            state = AvatarState.OFFLINE;
        }
    }

    public AvatarState state() { return state; }

    public void setState(AvatarState s, Mood m) {
        state = s;
        mood = m;
        if (avatar != null) {
            String label = switch (s) {
                case IDLE -> "待机中";
                case THINKING -> "正在思考…";
                case SPEAKING -> "正在发言";
                case WALKING -> "散步";
                case ALERT -> "秩序警戒";
                case OFFLINE -> "离线";
            };
            Formatting moodColor = switch (m) {
                case HAPPY -> Formatting.GREEN;
                case CONCERNED -> Formatting.YELLOW;
                case TIRED -> Formatting.DARK_GRAY;
                default -> Formatting.AQUA;
            };
            avatar.setStatus(Text.literal(config.avatarName + "\n").formatted(Formatting.GOLD, Formatting.BOLD)
                .append(Text.literal("[" + label + "] 心情:").formatted(Formatting.GRAY))
                .append(Text.literal(" " + m.zh).formatted(moodColor)));
        }
    }

    private ServerWorld avatarWorld() {
        if (avatar == null) return null;
        net.minecraft.world.World w = avatar.getEntityWorld();
        return w instanceof ServerWorld sw ? sw : null;
    }

    private void playThinkEffects() {
        ServerWorld world = avatarWorld();
        if (avatar == null || world == null) return;
        world.spawnParticles(ParticleTypes.ENCHANT,
            avatar.getX(), avatar.getY() + 1.9, avatar.getZ(), 8, 0.25, 0.3, 0.25, 0.02);
    }

    private void playSpeakEffects(ServerPlayerEntity target) {
        ServerWorld world = avatarWorld();
        if (avatar == null || world == null) return;
        world.spawnParticles(ParticleTypes.NOTE,
            avatar.getX(), avatar.getY() + 1.8, avatar.getZ(), 6, 0.3, 0.2, 0.3, 0.01);
        world.playSound(null, avatar.getX(), avatar.getY() + 1.0, avatar.getZ(),
            SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), SoundCategory.PLAYERS, 0.7f, 1.1f);
        if (target != null && avatar.distanceTo(target) < 40) {
            Vec3d cur = avatar.getSyncedPos();
            double dx = target.getX() - cur.x, dz = target.getZ() - cur.z;
            headHomeYaw = avatar.getYaw();
            avatar.turnHeadTo((float) (Math.toDegrees(Math.atan2(-dx, dz)) - 180.0));
            headHoldTicks = 40;
        }
    }

    private void playAlertEffects() {
        ServerWorld world = avatarWorld();
        if (avatar == null || world == null) return;
        world.spawnParticles(ParticleTypes.ANGRY_VILLAGER,
            avatar.getX(), avatar.getY() + 2.1, avatar.getZ(), 4, 0.2, 0.2, 0.2, 0.0);
        world.playSound(null, avatar.getX(), avatar.getY() + 1.0, avatar.getZ(),
            SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.PLAYERS, 0.7f, 0.7f);
    }

    public void whisperOrBroadcast(ServerPlayerEntity player, String channel, String text, Formatting color) {
        Text styled = Text.literal("[" + config.nickname + "] ").formatted(Formatting.AQUA)
            .append(Text.literal(text).formatted(color));
        if ("private".equals(channel) && player != null) player.sendMessage(styled);
        else server.getPlayerManager().broadcast(styled, false);
    }

    /* ---------------------------------------------------------------- tick */

    public void tick() {
        tickCounter++;
        if (avatar == null) return;
        if (avatar.isRemoved()) { avatar = null; state = AvatarState.OFFLINE; return; }
        // 转头回正
        if (headHoldTicks > 0) {
            headHoldTicks--;
            if (headHoldTicks == 0) avatar.turnHeadTo(headHomeYaw);
        }
        // 缓慢走动
        if (state == AvatarState.WALKING && walkTarget != null) {
            Vec3d cur = avatar.getSyncedPos();
            Vec3d dirv = walkTarget.subtract(cur);
            if (dirv.length() < 0.15) {
                walkTarget = null;
                setState(AvatarState.IDLE, mood);
            } else {
                double speed = 0.05;
                Vec3d step = cur.add(dirv.normalize().multiply(speed));
                avatar.refreshPositionAndAngles(step.x, step.y, step.z,
                    (float) Math.toDegrees(Math.atan2(-dirv.z, dirv.x)), 0f);
            }
        } else if (state == AvatarState.IDLE && tickCounter % 240 == 0 && homeAnchor != null
                && ThreadLocalRandom.current().nextInt(100) < 30) {
            double r = 4 + ThreadLocalRandom.current().nextDouble(4);
            double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
            walkTarget = new Vec3d(homeAnchor.x + Math.cos(a) * r, homeAnchor.y, homeAnchor.z + Math.sin(a) * r);
            setState(AvatarState.WALKING, mood);
        }
        // 待确认技能过期
        SkillRegistry.Pending p = skills.pending();
        if (p != null && System.currentTimeMillis() > p.expiresAt()) {
            skills.clearPending();
            server.getPlayerManager().broadcast(Text.literal("[" + config.nickname + "] ").formatted(Formatting.AQUA)
                .append(Text.literal("确认超时，操作已取消。").formatted(Formatting.GRAY)), false);
        }
        // 状态自然回落
        if ((state == AvatarState.SPEAKING || state == AvatarState.THINKING || state == AvatarState.ALERT)
                && tickCounter % 100 == 0) {
            setState(AvatarState.IDLE, mood);
        }
    }

    /** 记录玩家最近对 AI 的输入（保留接口，供回声调试）。 */
    public void rememberInput(ServerPlayerEntity player, String text) {
        commandHistory.computeIfAbsent(player.getUuid(), k -> new ArrayDeque<>()).addLast(text);
        ArrayDeque<String> q = commandHistory.get(player.getUuid());
        while (q.size() > 8) q.removeFirst();
    }

    public List<String> listEnabledSources() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, AiConfig.Source> e : config.sources.entrySet()) {
            if (e.getValue().enabled && !e.getValue().encryptedKey.isBlank()) out.add(e.getKey());
        }
        return out;
    }

    public void saveConfig() { config.save(dir); }
}
