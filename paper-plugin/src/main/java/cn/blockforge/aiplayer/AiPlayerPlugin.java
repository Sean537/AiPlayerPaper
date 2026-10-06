package cn.blockforge.aiplayer;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class AiPlayerPlugin extends JavaPlugin implements Listener {
    public record ApiSource(String id, String type, String baseUrl, String model, String encryptedKey, boolean enabled) {}

    /** 一条待处理的对话请求。priority=true（点名/私聊）永不丢弃，冷却也不拦。 */
    private record Ask(CommandSender sender, String text, String channel, boolean priority) {}

    private SecretStore secrets;
    private MemoryStore memory;
    private WorldMemory worldMemory;
    private NpcManager npcs;
    private BehaviorEngine behavior;
    private HomeBase homeBase;
    private AgentBrain brain;
    private PlayerObserver observer;
    private AiClient ai;
    private Watchdog watchdog;
    private Punishments punishments;
    private SkillRegistry skills;
    private JoinCommandRunner autoCommands;
    private WhisperHook whispers;
    private ChatDelivery delivery;   // "嘴巴"：带送达回执的发言链路（跨 reload 保持同一个实例）

    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<String, Long> recentAsks = new HashMap<>(); // 事件+封包双路去重
    private final Deque<Ask> hotQueue = new ArrayDeque<>();       // 点名/私聊：排队必答
    private final Deque<Ask> coldQueue = new ArrayDeque<>();      // 普通群聊接话
    private boolean askInFlight;
    private long askInFlightSince;
    private BukkitTask queueTask, flushTask, apiReminderTask;
    private final Deque<String> feed = new ArrayDeque<>();        // 全局聊天流水（大脑的"监工耳朵"）
    private long feedSeq;
    private long lastNoSourceWarnMs, lastFailWarnMs;
    private Player autoCmdKickoff; // 已触发过"进服命令"的那个 AI 客户端实例（防重复）
    private long autoHomeCheckMs;

    @Override public void onEnable() {
        saveDefaultConfig();
        saveResource("blueprints/house.yml", false);
        ensureApiDefaults();
        ensureChatDefaults();
        ensureBrainDefaults();
        // 跨 reload 保持单例的监听器
        if (delivery == null) delivery = new ChatDelivery(this);
        if (homeBase == null) homeBase = new HomeBase(this);
        if (observer == null) observer = new PlayerObserver(this);
        initialize();
        extractBundledManual();
        AiCommands commands = new AiCommands(this);
        Objects.requireNonNull(getCommand("aiplayer")).setExecutor(commands);
        getCommand("aiplayer").setTabCompleter(commands);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new ChatListener(this), this);
        getServer().getPluginManager().registerEvents(delivery, this);
        getServer().getPluginManager().registerEvents(homeBase, this);
        getServer().getPluginManager().registerEvents(observer, this);
        getLogger().info("AiPlayerPaper 已启动。使用 /aiplayer help 查看命令。");
    }

    @Override public void onDisable() {
        if (autoCommands != null) autoCommands.cancelAll();
        if (behavior != null) behavior.stop();
        if (brain != null) brain.stop();
        if (queueTask != null) queueTask.cancel();
        if (flushTask != null) flushTask.cancel();
        // 调度器一停，"等回执"的发言就永远等不到确认了 —— 先把它们补发出去再关，否则那几句话会静默丢失
        if (delivery != null) delivery.flushPending();
        if (memory != null) memory.flushDirty();
        if (worldMemory != null) worldMemory.flush();
        if (npcs != null) npcs.remove();
    }

    private void initialize() {
        try { Files.createDirectories(getDataFolder().toPath()); } catch (Exception ignored) {}
        Path data = getDataFolder().toPath();
        secrets = new SecretStore(data);
        memory = new MemoryStore(new File(getDataFolder(), "memory").toPath(), getConfig().getInt("memory.max-turns", 12));
        worldMemory = new WorldMemory(data);
        worldMemory.setCellSize(getConfig().getInt("brain.cell-size", 32));
        npcs = new NpcManager(this);
        npcs.loadFromConfig();
        behavior = new BehaviorEngine(this);
        behavior.start();
        ai = new AiClient();
        watchdog = new Watchdog();
        punishments = new Punishments(new File(getDataFolder(), "data").toPath());
        skills = new SkillRegistry(data);
        autoCommands = new JoinCommandRunner(this);
        whispers = new WhisperHook(this);
        brain = new AgentBrain(this);
        brain.start();
        startApiKeyReminder();
        startQueueDrainer();
        startMemoryFlusher();
        // 启动后自动让 AI 玩家"登录"进世界（延迟片刻，等地表和玩家列表完全就绪）
        if (getConfig().getBoolean("npc.auto-join", getConfig().getBoolean("npc.auto-spawn", true))) {
            getServer().getScheduler().runTaskLater(this, npcs::spawn, 40L);
        }
    }

    /* ---------------- 模块访问 ---------------- */

    public NpcManager npcs() { return npcs; }
    public BehaviorEngine behavior() { return behavior; }
    public MemoryStore memory() { return memory; }
    public WorldMemory worldMemory() { return worldMemory; }
    public HomeBase homeBase() { return homeBase; }
    public AgentBrain brain() { return brain; }
    public AiClient ai() { return ai; }
    public Punishments punishments() { return punishments; }
    public SkillRegistry skills() { return skills; }
    public JoinCommandRunner autoCommands() { return autoCommands; }
    public WhisperHook whispers() { return whispers; }
    public ChatDelivery delivery() { return delivery; }
    public UUID selfUuid() { UUID u = npcs == null ? null : npcs.uuid(); return u == null ? new UUID(0, 0) : u; }
    public boolean chatBusy() { return askInFlight; }
    public boolean brainEnabledForBehavior() { return brain != null && brain.enabled(); }
    public String decryptKey(ApiSource source) { return secrets.decrypt(source.encryptedKey()); }
    public ApiSource pickSource(String channel) { return resolveSource(channel); }

    public void reloadPlugin() {
        if (autoCommands != null) autoCommands.cancelAll();
        delivery.flushPending();   // 重载会作废待确认的回执，先补发（要在 npcs.remove 之前，播完音效才正常）
        behavior.stop(); brain.stop(); npcs.remove();
        if (queueTask != null) { queueTask.cancel(); queueTask = null; }
        reloadConfig(); ensureApiDefaults(); ensureChatDefaults(); ensureBrainDefaults(); initialize();
    }

    /* ---------------- 全局聊天流水（大脑监管用） ---------------- */

    public void noteFeed(String name, String text) {
        String line = name + "：" + (text.length() > 100 ? text.substring(0, 100) + "…" : text);
        synchronized (feed) {
            feed.addLast(line);
            while (feed.size() > 60) feed.removeFirst();
        }
        feedSeq++;
    }
    public long feedSeq() { return feedSeq; }
    public List<String> feedSnapshot(int last) {
        synchronized (feed) {
            List<String> all = new ArrayList<>(feed);
            return all.subList(Math.max(0, all.size() - last), all.size());
        }
    }

    /* ---------------- 密钥/来源/配置（沿用） ---------------- */

    public void setSecret(String name, String value) { getConfig().set("secrets." + name, secrets.encrypt(value)); saveConfig(); }
    public void removeSecret(String name) { getConfig().set("secrets." + name, null); saveConfig(); }
    public Set<String> secretNames() {
        ConfigurationSection section = getConfig().getConfigurationSection("secrets");
        return section == null ? Set.of() : new TreeSet<>(section.getKeys(false));
    }
    public String secretValue(String name) {
        String stored = getConfig().getString("secrets." + name);
        return stored == null ? null : secrets.decrypt(stored);
    }
    public Map<String, ApiSource> apiSources() {
        Map<String, ApiSource> out = new LinkedHashMap<>();
        ConfigurationSection section = getConfig().getConfigurationSection("api.sources");
        if (section == null) return out;
        for (String id : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(id); if (s == null) continue;
            out.put(id, new ApiSource(id, s.getString("type", "openai_compatible"), s.getString("base-url", "https://api.openai.com/v1"),
                s.getString("model", "gpt-4o-mini"), s.getString("encrypted-key", ""), s.getBoolean("enabled", false)));
        }
        return out;
    }
    public List<String> enabledSources() {
        List<String> out = new ArrayList<>();
        apiSources().forEach((id, s) -> { if (s.enabled() && !s.encryptedKey().isBlank()) out.add(id); });
        return out;
    }
    public String channelSourceId(String channel) { return getConfig().getString("api.channels." + channel, "default"); }
    public void setChannelSource(String channel, String id) { getConfig().set("api.channels." + channel, id); saveConfig(); }
    public void setSource(String id, String type, String baseUrl, String model) {
        ConfigurationSection s = getConfig().createSection("api.sources." + id);
        s.set("type", type); s.set("base-url", baseUrl); s.set("model", model);
        if (s.getString("encrypted-key", "").isBlank()) s.set("encrypted-key", "");
        s.set("enabled", false); saveConfig();
    }
    public void setSourceKey(String id, String key) {
        getConfig().set("api.sources." + id + ".encrypted-key", secrets.encrypt(key));
        getConfig().set("api.sources." + id + ".enabled", true); saveConfig();
    }
    public void setSourceEnabled(String id, boolean enabled) {
        ApiSource source = apiSources().get(id); if (source == null) return;
        getConfig().set("api.sources." + id + ".enabled", enabled && !source.encryptedKey().isBlank()); saveConfig();
    }
    public void removeSource(String id) { getConfig().set("api.sources." + id, null); saveConfig(); }

    private ApiSource resolveSource(String channel) {
        ApiSource source = apiSources().get(channelSourceId(channel));
        if (source != null && source.enabled() && !source.encryptedKey().isBlank()) return source;
        for (ApiSource s : apiSources().values()) if (s.enabled() && !s.encryptedKey().isBlank()) return s;
        return null;
    }

    /* ---------------- 配置补全 ---------------- */

    private void ensureApiDefaults() {
        if (getConfig().isConfigurationSection("api.sources")) return;
        String type = getConfig().getString("api.type", "openai_compatible");
        String url = getConfig().getString("api.base-url", "https://api.openai.com/v1");
        String model = getConfig().getString("api.model", "gpt-4o-mini");
        String key = getConfig().getString("api.encrypted-key", "");
        boolean enabled = getConfig().getBoolean("api.enabled", false);
        ConfigurationSection section = getConfig().createSection("api.sources.default");
        section.set("type", type); section.set("base-url", url); section.set("model", model);
        section.set("encrypted-key", key); section.set("enabled", enabled);
        getConfig().set("api.channels.public", "default");
        getConfig().set("api.channels.private", "default");
        getConfig().set("api.channels.mention", "default");
        getConfig().set("api.type", null); getConfig().set("api.base-url", null);
        getConfig().set("api.model", null); getConfig().set("api.encrypted-key", null); getConfig().set("api.enabled", null);
        saveConfig();
    }

    private void ensureChatDefaults() {
        boolean changed = false;
        if (!getConfig().isConfigurationSection("chat")) {
            if (getConfig().getInt("persona.public-reply-chance", 100) == 35) {
                getConfig().set("persona.public-reply-chance", 100);
                getLogger().info("[升级] 公开聊天回复概率已从旧默认 35% 调整为 100%（AI 逢聊必答）。"
                    + "想省 API 用量可改：/aiplayer persona chance 30");
            }
            getConfig().set("chat.listen-client-messages", true);
            getConfig().set("chat.sniff-signed-chat", true);
            getConfig().set("chat.whisper-patterns", List.of(
                "(?<player>\\S{1,16})\\s*(?:悄悄对你说|低声对你说|对你说|私聊对你说|私聊给你|私聊悄悄话)[：:]\\s*(?<text>.+)",
                "(?<player>\\S{1,16})\\s+whispers?\\s+to\\s+you[：:]?\\s*(?<text>.+)",
                "from\\s+(?<player>\\S{1,16})\\s+to\\s+you[：:]?\\s*(?<text>.+)",
                "(?<player>\\S{1,16})\\s*(?:→|➤)\\s*(?:你|You|you)\\s*[：:]?\\s*(?<text>.+)",
                "(?:你|You|you)\\s*(?:←|⇐)\\s*(?<player>\\S{1,16})\\b[：:]?\\s*(?<text>.+)"));
            changed = true;
        }
        if (!getConfig().contains("persona.chat-as-player")) { getConfig().set("persona.chat-as-player", true); changed = true; }
        if (!getConfig().contains("persona.whisper-reply-mode")) { getConfig().set("persona.whisper-reply-mode", "direct"); changed = true; }
        if (!getConfig().contains("persona.listen-pause-seconds")) { getConfig().set("persona.listen-pause-seconds", 20); changed = true; }
        if (!getConfig().contains("persona.delivery-mode")) { getConfig().set("persona.delivery-mode", "auto"); changed = true; }
        if (!getConfig().contains("persona.fallback-style")) { getConfig().set("persona.fallback-style", "bracket"); changed = true; }
        if (!getConfig().contains("persona.mention-direct-fallback")) { getConfig().set("persona.mention-direct-fallback", true); changed = true; }
        if (!getConfig().contains("persona.verify-delay-ticks")) { getConfig().set("persona.verify-delay-ticks", 16); changed = true; }
        if (!getConfig().contains("persona.asplayer-retry-cooldown-seconds")) { getConfig().set("persona.asplayer-retry-cooldown-seconds", 120); changed = true; }
        // 硬送达的附加通道：动作栏是独立封包，聊天插件几乎拦不到它，
        // 而且显示在屏幕正中，不会像聊天栏那样被刷过去看不见 —— 默认打开是"广播也看不到"的根治点。
        if (!getConfig().contains("persona.hard-send-actionbar")) { getConfig().set("persona.hard-send-actionbar", true); changed = true; }
        if (!getConfig().contains("persona.hard-send-title")) { getConfig().set("persona.hard-send-title", false); changed = true; }
        if (!getConfig().isConfigurationSection("self-client")) {
            getConfig().set("self-client.address", "127.0.0.1");
            getConfig().set("self-client.port", 0);
            getConfig().set("self-client.login-timeout-seconds", 25);
            getConfig().set("self-client.tick-interval-ms", 1000);
            getConfig().set("self-client.force", false);
        }
        if (!getConfig().contains("self-client.debug-log")) { getConfig().set("self-client.debug-log", false); changed = true; }
        if (changed) saveConfig();
    }

    /** 自主大脑/安家/玩家档案的新配置键：老配置文件缺段时自动补默认值。 */
    private void ensureBrainDefaults() {
        boolean changed = false;
        if (!getConfig().contains("brain.enabled")) { getConfig().set("brain.enabled", true); changed = true; }
        if (!getConfig().contains("brain.tick-seconds")) { getConfig().set("brain.tick-seconds", 20); changed = true; }
        if (!getConfig().contains("brain.greet-on-join")) { getConfig().set("brain.greet-on-join", true); changed = true; }
        if (!getConfig().contains("brain.describe-areas")) { getConfig().set("brain.describe-areas", true); changed = true; }
        if (!getConfig().contains("brain.explore-max-cells")) { getConfig().set("brain.explore-max-cells", 10); changed = true; }
        if (!getConfig().contains("brain.cell-size")) { getConfig().set("brain.cell-size", 32); changed = true; }
        if (!getConfig().contains("home.auto-build")) { getConfig().set("home.auto-build", true); changed = true; }
        if (!getConfig().contains("home.max-distance")) { getConfig().set("home.max-distance", 96); changed = true; }
        if (!getConfig().contains("skills.autonomous")) { getConfig().set("skills.autonomous", true); changed = true; }
        if (!getConfig().contains("skills.autonomous-max-level")) { getConfig().set("skills.autonomous-max-level", 1); changed = true; }
        if (!getConfig().contains("memory.consolidate-minutes")) { getConfig().set("memory.consolidate-minutes", 45); changed = true; }
        if (!getConfig().contains("behavior.explore.move-speed")) { getConfig().set("behavior.explore.move-speed", 0.45); changed = true; }
        if (changed) {
            saveConfig();
            getLogger().info("[升级] 已启用自主大脑（brain）：它现在会自己探索、记地图、记玩家、监管聊天；"
                + "到家任务（home.auto-build）会自动找个合适位置盖房放床，以后从家里出生。");
        }
    }

    private void extractBundledManual() {
        try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream("manual.md")) {
            if (is == null) return;
            Files.copy(is, getDataFolder().toPath().resolve("使用说明书.md"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {}
    }

    /* ---------------- 后台提醒 ---------------- */

    private void startApiKeyReminder() {
        if (apiReminderTask != null) { apiReminderTask.cancel(); apiReminderTask = null; }
        if (!getConfig().getBoolean("notify.api-key-reminder", true)) return;
        long minutes = Math.max(1, getConfig().getLong("notify.api-key-interval-minutes", 10));
        apiReminderTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (!enabledSources().isEmpty()) return;
            getLogger().warning("[提醒] 还没有任何已配置密钥的 AI API 源，AI 玩家处于离线模式（玩家说话它回复不了）。"
                + "配置方法：/aiplayer source add <id> <openai|anthropic|gemini|deepseek|ollama|openai_compatible> <模型名> <接口地址>，"
                + "再 /aiplayer source key <id> <密钥>（密钥会 AES-GCM 加密保存），最后 /aiplayer source test 自检。");
            getServer().getScheduler().runTask(this, () -> {
                for (Player p : Bukkit.getOnlinePlayers())
                    if (p.hasPermission("aiplayer.admin"))
                        p.sendMessage("§e[AI玩家] 当前没有配置 API Key，回复走离线模板。管理员可用 /aiplayer source key <id> <密钥> 配置。");
            });
        }, 120L, minutes * 60L * 20L);
    }

    /* ---------------- 对话队列：点名/私聊 100% 必答 ---------------- */

    private void startQueueDrainer() {
        if (queueTask != null) queueTask.cancel();
        queueTask = getServer().getScheduler().runTaskTimer(this, this::drainQueue, 10L, 10L);
    }

    private void drainQueue() {
        if (askInFlight) {
            if (System.currentTimeMillis() - askInFlightSince > 90_000L) {
                getLogger().warning("[对话队列] 上一个请求超过 90 秒没回音，强制放行下一条。");
                askInFlight = false;
            }
            return;
        }
        Ask ask = hotQueue.pollFirst();
        if (ask == null) ask = coldQueue.pollFirst();
        if (ask == null) return;
        startAsk(ask);
    }

    private void startAsk(Ask ask) {
        UUID who = ask.sender() instanceof Player p ? p.getUniqueId() : new UUID(0, 0);
        String senderName = ask.sender() == null ? "世界" : ask.sender().getName();
        ApiSource source = resolveSource(ask.channel());
        if (source == null) {
            getLogger().warning("[AI对话] " + senderName + "（" + ask.channel() + "）想说话，但没有任何启用了密钥的 API 源，无法请求 AI。");
            if (ask.sender() != null) ask.sender().sendMessage("§7[「" + npcs.name() + "」] 我还没连上 AI 大脑（没有可用的 API 源）。"
                + "管理员配置方法：/aiplayer source add <id> <类型> <模型> <接口地址>，再 /aiplayer source key <id> <密钥>");
            notifyAdminsRateLimited("§c[AI玩家] 有玩家（" + senderName + "）在跟它说话，但没有可用的 API 源，它回复不了。"
                + "请用 /aiplayer source key <id> <密钥> 配置，或 /aiplayer source test 自检。", lastNoSourceWarnMs, v -> lastNoSourceWarnMs = v);
            return;
        }
        String shortText = ask.text().length() > 60 ? ask.text().substring(0, 60) + "…" : ask.text();
        getLogger().info("[AI对话] " + ask.channel() + " ← " + senderName + "：" + shortText
            + "（源 " + source.id() + " / 模型 " + source.model() + "，队列 " + (hotQueue.size() + coldQueue.size()) + "）");
        String nickname = getConfig().getString("persona.nickname", npcs.name());
        String system = "你是 Minecraft 服务器里的 AI 玩家「" + nickname + "」。性格：" + getConfig().getString("persona.traits", "耐心") +
            "。语气：" + getConfig().getString("persona.tone", "简洁") + "。玩家的话就在当前这条消息里，你必须要回应他：" +
            "用玩家使用的语言，用一两句话接住话题，别沉默、别说自己是语言模型。" +
            "【频道】" + ask.channel() + "（private=玩家对你说的悄悄话，只回给这个人；mention=玩家在群里点了你的名字；public=普通群聊）" +
            "【技能规则】如需执行服务器命令，只输出一行 SKILL: <技能名> [参数=值]，且只能使用白名单技能。" +
            "\n【我对这个玩家的记忆】\n" + memory.brief(who, senderName) +
            "\n【对话上下文】\n" + memory.context(who) +
            "\n【世界记忆】\n" + clip(worldMemory.brief(npcs.location()), 600) +
            "\n【我自己最近在做的事】\n" + clip(memory.selfFacts(), 300);
        askInFlight = true;
        askInFlightSince = System.currentTimeMillis();
        long startMs = System.currentTimeMillis();
        ai.askAsync(new AiClient.Source(source.type(), source.baseUrl(), source.model(), secrets.decrypt(source.encryptedKey())),
            system, ask.text(), reply -> getServer().getScheduler().runTask(this, () -> {
                askInFlight = false;
                deliver(ask.sender(), ask.channel(), reply, startMs);
            }));
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    /** 后台 API 连通性自检：不走队列与记忆。 */
    public void testApi(CommandSender sender, String probe) {
        ApiSource source = resolveSource("private");
        if (source == null) {
            sender.sendMessage("§c还没有\"启用且带密钥\"的 API 源。步骤：/aiplayer source add myapi openai_compatible <模型> <接口地址> → /aiplayer source key myapi <密钥>");
            return;
        }
        sender.sendMessage("§7正在向源「" + source.id() + "」（" + source.type() + " / " + source.model() + "）发测试请求…");
        long startMs = System.currentTimeMillis();
        ai.askAsync(new AiClient.Source(source.type(), source.baseUrl(), source.model(), secrets.decrypt(source.encryptedKey())),
            "你是一个测试助手，无论收到什么都回复：连通正常。", probe,
            reply -> getServer().getScheduler().runTask(this, () -> {
                long ms = System.currentTimeMillis() - startMs;
                if (reply.ok()) sender.sendMessage("§a✔ API 连通正常（" + ms + " ms），模型回复：§f" + sanitize(reply.text()));
                else sender.sendMessage("§c✘ API 调用失败（" + ms + " ms）：" + reply.error()
                    + "\n§7排查：base-url 是否以 /v1 结尾（openai 类）、模型名是否存在、密钥是否有效、服务器能否访问该站点。");
            }));
    }

    private void deliver(CommandSender sender, String channel, AiClient.Reply reply, long startMs) {
        String senderName = sender == null ? "世界" : sender.getName();
        long ms = System.currentTimeMillis() - startMs;
        if (!reply.ok()) {
            getLogger().warning("[AI对话] 请求失败（" + ms + " ms）：" + reply.error());
            if (sender != null) sender.sendMessage("§c[AI] 请求失败：" + reply.error());
            notifyAdminsRateLimited("§c[AI玩家] API 请求失败：" + reply.error(), lastFailWarnMs, v -> lastFailWarnMs = v);
            return;
        }
        String answer = sanitize(reply.text());
        if (answer.isEmpty()) return;
        UUID who = sender instanceof Player p ? p.getUniqueId() : new UUID(0, 0);
        if (answer.regionMatches(true, 0, "SKILL:", 0, 6)) { handleSkill(sender, answer.substring(6).trim()); return; }
        memory.add(who, npcs.name(), answer);
        memory.mark(who);
        getLogger().info("[AI对话] → " + senderName + "（" + ms + " ms）：" + (answer.length() > 60 ? answer.substring(0, 60) + "…" : answer));

        String nickname = getConfig().getString("persona.nickname", npcs.name());
        if (channel.equals("private")) {
            if (sender instanceof Player target) delivery.sayPrivate(answer, nickname, target, null);
            else getLogger().warning("[AI嘴巴] 私聊回复找不到收件人（对方已下线？），不外泄到公共频道。");
            return;
        }
        delivery.sayPublic(answer, nickname, sender instanceof Player p ? p : null, null);
    }

    /**
     * 对话统一入口。sender 允许为 null（跨服/桥接广播里 @ 它、但认不出说话人时用世界频道）。
     * 事件路径与封包嗅探路径可能先后送同一条消息，用 recentAsks 做 1.5 秒去重。
     * 点名（mention）与私聊（private）= 必回：进高优先队列，永不因冷却被丢弃。
     */
    public void askAi(CommandSender sender, String text, String channel) { askAi(sender, text, channel, false); }

    /** 聊天事件路径专用：档案已由 PlayerObserver 记过，不再重复记对话。 */
    public void askAiRecorded(CommandSender sender, String text, String channel) { askAi(sender, text, channel, true); }

    public void askAi(CommandSender sender, String text, String channel, boolean chatRecorded) {
        if (text == null || text.isBlank()) return;
        if (sender instanceof Player p && npcs != null && isNpcSelf(p)) return; // AI 自己说的话不回复
        String senderName = sender == null ? "世界" : sender.getName();
        long now = System.currentTimeMillis();

        String dedupeKey = normalizeForDedupe(text);
        Long prev = recentAsks.put(dedupeKey, now);
        if (prev != null && now - prev < 1500L) return; // 同一条消息的第二次投递（Bukkit 事件 + 协议包）
        if (recentAsks.size() > 400) recentAsks.entrySet().removeIf(e -> now - e.getValue() > 10_000L);

        boolean priority = !channel.equals("public");
        UUID who = sender instanceof Player p ? p.getUniqueId() : new UUID(0, 0);
        if (!priority) { // 普通群聊：沿用冷却，避免它逢每条都抢话（要更省可调 persona.cooldown-seconds）
            long wait = getConfig().getLong("persona.cooldown-seconds", 3) * 1000L;
            if (now - cooldowns.getOrDefault(who, 0L) < wait) return;
            cooldowns.put(who, now);
        }
        if (!chatRecorded) memory.add(who, senderName, text);
        memory.mark(who);
        if (channel.equals("public")) noteFeed(senderName, text); // 私聊绝不进全局流水，防止大脑把悄悄话当众复述

        // 有人跟它说话：停下脚步，转头看着对方（更像真人）
        if (behavior != null) behavior.pause(getConfig().getLong("persona.listen-pause-seconds", 20) * 1000L);
        if (sender instanceof Player p && p.isOnline() && npcs != null) npcs.faceTowards(p.getLocation());

        Deque<Ask> q = priority ? hotQueue : coldQueue;
        if (q.size() >= (priority ? 12 : 20)) { q.pollFirst(); getLogger().warning("[对话队列] 积压超上限，丢掉了最早的一条" + (priority ? "点名" : "群聊") + "消息"); }
        q.addLast(new Ask(sender, text, channel, priority));
    }

    private boolean isNpcSelf(Player p) {
        if (npcs == null) return false;
        if (npcs.uuid() != null && p.getUniqueId().equals(npcs.uuid())) return true;
        return p.getName().equalsIgnoreCase(npcs.name()) && npcs.online();
    }

    private static String normalizeForDedupe(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}]+", "");
    }

    private void notifyAdminsRateLimited(String msg, long lastMs, java.util.function.LongConsumer stamp) {
        long now = System.currentTimeMillis();
        if (now - lastMs < 60_000L) return;
        stamp.accept(now);
        for (Player p : Bukkit.getOnlinePlayers())
            if (p.hasPermission("aiplayer.admin")) p.sendMessage(msg);
    }

    /** 大脑/安家模块的"随口一说"：走嘴巴模块并记进记忆。 */
    public void sayAsNpc(String text, java.util.function.Consumer<String> reporter) {
        if (text == null || text.isBlank()) return;
        String clean = sanitize(text);
        memory.add(selfUuid(), npcs.name(), clean);
        memory.noteSelf("说了：" + clip(clean, 50));
        delivery.sayPublic(clean, getConfig().getString("persona.nickname", npcs.name()), null, reporter);
    }

    /* ---------------- 秩序与技能 ---------------- */

    public boolean beforeChat(Player player, String text) {
        if (punishments.muted(player.getUniqueId())) { player.sendMessage("§c你正处于禁言中，请稍后再试。"); return false; }
        Watchdog.Verdict verdict = watchdog.check(player.getUniqueId(), text);
        int strikes = 0;
        if (verdict.level() > 0) {
            strikes = punishments.strike(player.getUniqueId());
            int action = Math.min(4, verdict.level() + strikes - 1);
            punishments.audit("MODERATE " + player.getName() + " level=" + verdict.level() + " strikes=" + strikes + " reason=" + verdict.reason());
            switch (action) {
                case 1 -> player.sendMessage("§e[秩序维护] " + verdict.reason() + "，请注意发言。");
                case 2 -> { punishments.mute(player.getUniqueId(), 5); player.sendMessage("§c[秩序维护] " + verdict.reason() + "，禁言 5 分钟。"); }
                case 3 -> player.kick(Component.text("[秩序维护] " + verdict.reason() + "，已被踢出。"));
                default -> { punishments.ban(player.getUniqueId(), getConfig().getInt("order.temporary-ban-minutes", 30)); player.kick(Component.text("[秩序维护] " + verdict.reason() + "，临时封禁。")); }
            }
            return action < 2;
        }
        return true;
    }

    public boolean confirm(CommandSender sender) {
        SkillRegistry.Pending p = skills.pending();
        if (p == null) return false;
        SkillRegistry.Skill skill = skills.get(p.skillName()); skills.clearPending();
        if (skill != null) { runSkill(skill, p.finalCommand(), sender.getName()); sender.sendMessage("§a已按确认执行：" + p.finalCommand()); }
        return skill != null;
    }

    public boolean deny(CommandSender sender) {
        SkillRegistry.Pending p = skills.pending();
        if (p == null) return false;
        skills.clearPending(); sender.sendMessage("§a已取消待执行的操作。"); return true;
    }

    private void handleSkill(CommandSender sender, String proposal) {
        String command = skills.resolveInvocation(proposal);
        if (command == null) { Bukkit.broadcastMessage("§7[" + npcs.name() + "] 这个操作不在白名单里，我不会执行。"); return; }
        SkillRegistry.Skill skill = skills.get(proposal.trim().split("\\s+")[0]);
        long remain = skill.remainingCooldown();
        if (remain > 0) { Bukkit.broadcastMessage("§7[" + npcs.name() + "] 技能冷却中，请 " + remain + " 秒后再试。"); return; }
        boolean admin = sender != null && sender.hasPermission("aiplayer.admin");
        if (skill.permissionLevel >= 2 && !admin) { Bukkit.broadcastMessage("§7[" + npcs.name() + "] 请求者权限不足，无法执行该技能。"); return; }
        if (getConfig().getBoolean("skills.require-confirmation", true)) {
            skills.setPending(new SkillRegistry.Pending(skill.name, command, sender == null ? npcs.name() : sender.getName(), System.currentTimeMillis() + 30_000));
            Bukkit.broadcastMessage("§e[" + npcs.name() + "] 我准备执行 `" + command + "`，请管理员用 /aiplayer confirm 确认，或 /aiplayer deny 取消。");
        } else { runSkill(skill, command, sender == null ? npcs.name() : sender.getName()); Bukkit.broadcastMessage("§a[" + npcs.name() + "] 已执行白名单技能 " + skill.name + "。"); }
    }

    /** 大脑自主发起的命令调用：低级技能可自动执行（管理服务器），高级技能仍要管理员确认。 */
    public void autonomousSkill(String proposal) {
        String command = skills.resolveInvocation(proposal);
        if (command == null) {
            getLogger().warning("[自主技能] 不在白名单，忽略：" + proposal);
            memory.noteSelf("想执行 " + proposal + "，但不在白名单");
            return;
        }
        String skillName = proposal.trim().split("\\s+")[0];
        SkillRegistry.Skill skill = skills.get(skillName);
        if (skill == null) return;
        long remain = skill.remainingCooldown();
        if (remain > 0) { getLogger().info("[自主技能] " + skillName + " 冷却中（" + remain + "s），跳过"); return; }
        int autoMax = getConfig().getInt("skills.autonomous-max-level", 1);
        if (getConfig().getBoolean("skills.autonomous", true) && skill.permissionLevel <= autoMax) {
            runSkill(skill, command, npcs.name() + "(自主)");
            Bukkit.broadcastMessage("§7[" + npcs.name() + "]（管理）我执行了：" + command);
            memory.noteSelf("自主执行了 " + skillName + "（" + command + "）");
        } else {
            skills.setPending(new SkillRegistry.Pending(skill.name, command, npcs.name() + "(自主)", System.currentTimeMillis() + 60_000));
            Bukkit.broadcastMessage("§e[" + npcs.name() + "] 我打算执行 `" + command + "`（管理），请管理员 /aiplayer confirm 确认或 /aiplayer deny 取消。");
            memory.noteSelf("提请管理员确认 " + skillName);
        }
    }

    private void runSkill(SkillRegistry.Skill skill, String command, String by) {
        skill.lastUse.set(System.currentTimeMillis());
        try { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command); punishments.audit("SKILL-EXEC " + skill.name + " -> " + command + " by " + by); }
        catch (Exception e) { punishments.audit("SKILL-FAIL " + skill.name + " -> " + command + " err=" + e.getMessage()); }
    }

    public void speakEffects() {
        if (npcs.entity() == null) return;
        Location l = npcs.entity().getLocation();
        l.getWorld().spawnParticle(Particle.NOTE, l.clone().add(0, 1.8, 0), 6, 0.3, 0.2, 0.3, 0.01);
        l.getWorld().playSound(l, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.1f);
    }

    private String sanitize(String text) {
        String out = text == null ? "" : text.replace("\r", "").replace("\n", " ").trim();
        for (String word : getConfig().getStringList("persona.banned-words")) if (!word.isBlank()) out = out.replace(word, "*");
        return out.length() > 300 ? out.substring(0, 300) + "…" : out;
    }

    /* ---------------- 记忆落盘 ---------------- */

    private void startMemoryFlusher() {
        if (flushTask != null) flushTask.cancel();
        flushTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            memory.flushDirty();
        }, 600L, 600L);
    }

    /* ---------------- 帮助/状态/蓝图/处罚 ---------------- */

    public String helpText() {
        return "§bAI 玩家命令：\n§7和它聊天：/msg " + npcs.name() + " <内容>（一定回复）；公共聊天里 @它的名字/称呼 也一定回复\n" +
            "§7/aiplayer status · tell <消息> · say <文字>（让它说这句并回报有没有送到聊天框）· confirm / deny\n" +
            "§7/aiplayer delivery diagnose [玩家] · delivery report <编号>（聊天框看不到时先跑这个：逐条通道打探针）\n" +
            "§7/aiplayer delivery retry（清掉冷静期，立刻重新尝试以玩家身份发言）\n" +
            "§7/aiplayer brain on|off|now|status（自主大脑：探索/记图/记人/监管聊天）\n" +
            "§7/aiplayer home show|rebuild|goto|set <x y z>（安家：自己盖房放床，从这里出生）\n" +
            "§7/aiplayer source add|key|enable|disable|remove|list|test（管理员；test=API 连通自检）\n" +
            "§7/aiplayer channel <public|private|mention> <源id>\n" +
            "§7/aiplayer persona nickname|tone|traits|cooldown|chance|banned|asplayer|wreply|delivery|fallback|hardbar|verify\n" +
            "§7/aiplayer npc name <名称> · skin <player:名字|url:皮肤图|base64:纹理值> · spawn|move|remove\n" +
            "§7/aiplayer npc method <auto|self-client|kernel|armorstand>（进服方式：自连协议/内核反射/盔甲架）\n" +
            "§7/aiplayer autocmd list|add <命令>|remove <序号|all>|prompt|run|exec <命令>|on|off（进服自动执行）\n" +
            "§7/aiplayer secret set <名字> <内容>|list|remove <名字>（加密保存登录密码等敏感值）\n" +
            "§7/aiplayer gather start|stop · build start <蓝图> [x y z 世界] · build stop\n" +
            "§7/aiplayer memory show|note|clear <玩家> · skill list|add|remove · punish <玩家> mute|unmute|kick|ban\n" +
            "§7/aiplayer reload · 详细说明书见 plugins/AiPlayerPaper/使用说明书.md";
    }

    public String statusText() {
        Location at = npcs.location();
        String where = at == null ? "无" : String.format("%s(%.1f, %.1f, %.1f)", at.getWorld().getName(), at.getX(), at.getY(), at.getZ());
        Location home = worldMemory.home();
        return "§bAI 玩家「§f" + npcs.name() + "§b」状态：§f" + (npcs.online() ? npcs.modeLabel() + "（在线）" : "离线") +
            "\n§7位置：" + where +
            "\n§7大脑：" + (brain == null ? "未启动" : brain.statusLine()) +
            "\n§7家：" + (home == null ? "还没建（进服后会自动找地盖房；也可 /aiplayer home rebuild）" : describe(home)) +
            "\n§7地图记忆：" + "已记录区域（格 " + worldMemory.cellSize() + "）；POI 见 /aiplayer home show 与说明书" +
            "\n§7对话队列：点名/私聊 " + hotQueue.size() + " 条待回（必答），群聊接话 " + coldQueue.size() + " 条" +
            (askInFlight ? "，正在处理 1 条" : "") +
            "\n§7可用 API 源：" + (enabledSources().isEmpty() ? "无（离线模式，玩家说话它回不了！用 /aiplayer source key 配置）" : String.join(", ", enabledSources())) +
            "\n§7回复冷却 " + getConfig().getLong("persona.cooldown-seconds", 3) + " 秒（只管群聊接话；点名/私聊不受冷却限制），公开回复概率 " + getConfig().getInt("persona.public-reply-chance", 100) + "%" +
            "\n§7送达方式：" + getConfig().getString("persona.delivery-mode", "auto") +
            "，最近一次发言结果：" + (delivery == null ? "（未初始化）" : delivery.lastResult()) +
            (delivery != null && delivery.cooldownActive() ? "§7（玩家聊天频道冷静期，下一条直接硬送达）" : "")
            + (delivery != null && delivery.lastHardResult() != null && !delivery.lastHardResult().anySent()
                ? "§c｜上一次硬送达 0 人成功，跑 /aiplayer delivery diagnose 定位" : "") +
            "\n§7白名单技能 " + skills.all().size() + " 个（自主可用等级≤" + getConfig().getInt("skills.autonomous-max-level", 1)
            + (getConfig().getBoolean("skills.autonomous", true) ? "·开" : "·关") + "）；最近采集 " + behavior.gatheredCount() + " 块。";
    }

    private static String describe(Location l) {
        return String.format("%s(%.0f, %.0f, %.0f)", l.getWorld().getName(), l.getX(), l.getY(), l.getZ());
    }

    public List<String> blueprints() { return new ArrayList<>(blueprintNames()); }
    public Set<String> blueprintNames() {
        File dir = new File(getDataFolder(), "blueprints"); File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        Set<String> out = new TreeSet<>(); if (files != null) for (File f : files) out.add(f.getName().substring(0, f.getName().length() - 4));
        return out;
    }
    public List<Map<?, ?>> loadBlueprint(String name) {
        File file = new File(new File(getDataFolder(), "blueprints"), name.endsWith(".yml") ? name : name + ".yml");
        return file.isFile() ? YamlConfiguration.loadConfiguration(file).getMapList("blocks") : List.of();
    }
    public void manualPunish(CommandSender admin, String targetName, String type, int minutes, String reason) {
        Player target = Bukkit.getPlayerExact(targetName);
        punishments.audit("ADMIN " + admin.getName() + " " + type + " " + targetName + " reason=" + reason);
        if (target == null) { admin.sendMessage("§c玩家不在线：" + targetName); return; }
        switch (type) {
            case "mute" -> { punishments.mute(target.getUniqueId(), Math.max(1, minutes)); target.sendMessage("§c[秩序维护] 你已被管理员禁言 " + minutes + " 分钟。"); }
            case "unmute" -> punishments.unmute(target.getUniqueId());
            case "kick" -> target.kick(Component.text("[秩序维护] " + reason));
            default -> { punishments.ban(target.getUniqueId(), Math.max(1, minutes)); target.kick(Component.text("[秩序维护] 临时封禁 " + minutes + " 分钟：" + reason)); }
        }
    }

    /* ---------------- 事件 ---------------- */

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        // AI 分身自己进服（协议自连或内核模式）：触发"进服后自动执行命令"，不参与秩序检查
        if (npcs != null && ((npcs.uuid() != null && p.getUniqueId().equals(npcs.uuid())) || npcs.onSelfJoinSeen(p))) {
            onAiJoin(p);
            return;
        }
        if (punishments.banned(p.getUniqueId())) { p.kick(Component.text("§c[秩序维护] 你正处于临时封禁中，请稍后再来。")); return; }
        memory.get(p.getUniqueId());
    }

    /** AI 进服统一入口：只触发一次进服命令（协议自连时事件与轮询认领可能先后到）。 */
    public void onAiJoin(Player p) {
        if (autoCmdKickoff != p) {
            autoCmdKickoff = p;
            if (autoCommands != null) autoCommands.onJoin(p);
        }
        // 进服后自动安家检查：没家就自己找地盖房（延后一点，等地形加载、登录命令先发完）
        if (getConfig().getBoolean("home.auto-build", true) && System.currentTimeMillis() - autoHomeCheckMs > 60_000L) {
            autoHomeCheckMs = System.currentTimeMillis();
            getServer().getScheduler().runTaskLater(this, () -> {
                if (homeBase != null && !homeBase.hasHome() && npcs.online() && brain != null && !brainBusy()) {
                    getLogger().info("[安家] 它发现自己还没有家，开始找宅基地…");
                    homeBase.ensureBuiltAsync(false);
                }
            }, 500L);
        }
    }

    private boolean brainBusy() { return brain != null && (brain.isBusy() || (homeBase != null && homeBase.isWorking())); }

    /** 协议自连的 AI 在预登录阶段注入皮肤纹理（offline 服务器上有效）。 */
    @EventHandler public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (npcs == null || event.getName() == null || !event.getName().equalsIgnoreCase(npcs.name())) return;
        String textures = npcs.loginTexturesValue();
        if (textures == null || textures.isBlank()) return;
        try {
            com.destroystokyo.paper.profile.PlayerProfile profile = event.getPlayerProfile();
            profile.setProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures", textures));
            event.setPlayerProfile(profile);
        } catch (Throwable ignored) {}
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        if (autoCmdKickoff == p) autoCmdKickoff = null;
        if (autoCommands != null && npcs != null && npcs.uuid() != null && p.getUniqueId().equals(npcs.uuid())) {
            autoCommands.onQuit(p);
        }
    }
}
