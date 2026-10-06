package cn.blockforge.aiplayer;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * AI 的"嘴巴"：把回复真正送到玩家能看见的地方，并且带回执。
 *
 * <h2>为什么不能只退回 {@code Bukkit.broadcastMessage}</h2>
 * 服务器上"看不到 AI 说话"的现场最后都能归到两件事上：
 * <ol>
 *   <li><b>聊天管线被别的插件掐断。</b>{@code Player#chat} 会触发 {@code AsyncChatEvent}，auth 登录插件、
 *       聊天频道/格式插件、反作弊、聊天过滤都能取消它；取消时 Bukkit 不抛异常，于是
 *       "控制台有回复、游戏里一个字都没有"。老版本的兜底是 {@code Bukkit.broadcastMessage} ——
 *       但那是一个<b>发完就不知道结果的调用</b>：不统计收件人、不捕获单个玩家的异常、也不告诉你
 *       到底送到了几个人手里。只要再叠一个会吞系统消息的插件（很常见），兜底同样会静默消失，
 *       而日志照样打"已发出"。这正是"已用广播兜底发出"却仍然什么都看不到的原因。</li>
 *   <li><b>回执认错了文本。</b>老代码用"整条回复归一化后被完整包含"来判定送达。聊天插件改个格式、
 *       加个前缀、截断一下，这条判定就失真：轻则误报"没送出去"从而<b>同一条话发两遍</b>，
 *       重则 pending 被后来的发言顶掉，永远等不到回执。</li>
 * </ol>
 *
 * <h2>现在的做法</h2>
 * <ol>
 *   <li>先照旧尝试"以玩家身份发言"，但登记到<b>注册表</b>（不是单个字段），用<b>指纹比对</b>认领回执；</li>
 *   <li>失败时走<b>硬送达</b>：逐个在线玩家直接发包，统计真实收件人数、逐个捕获异常，
 *       并且默认叠加一条<b>动作栏</b>通道 —— 动作栏是独立封包，几乎没有聊天插件会碰它，
 *       而且显示在屏幕正中，不可能被"消息刷过去看不见"。这是"广播也看不到"的根治点。</li>
 *   <li>{@code /aiplayer delivery diagnose} 逐条通道打探针，一次问清"这台服务器到底哪条通道是通的"。</li>
 * </ol>
 */
public final class ChatDelivery implements Listener {

    /* ---------------- 送达方式（写进日志与 status / /aiplayer say） ---------------- */

    public static final String WAY_PLAYER_CHAT = "以玩家身份发言（服务器聊天管线）";
    public static final String WAY_BROADCAST = "硬送达（绕过聊天管线）";
    public static final String WAY_DIRECT = "私聊直发";

    /** 一次硬送达的真实结果：尝试了几个人、成功几个人、哪几个失败、走了哪些通道。 */
    public record HardResult(int attempted, int sent, List<String> failures, String channels) {
        public boolean anySent() { return sent > 0; }

        public String describe() {
            String base = "送达 " + sent + "/" + attempted + " 人（通道：" + channels + "）";
            return failures.isEmpty() ? base : base + "；失败：" + String.join(", ", failures);
        }
    }

    /** 硬送达可选的样式。 */
    public enum Style {
        /** {@code §b[「昵称」] 内容} */
        BRACKET,
        /** 仿原版 {@code <玩家名> 内容} */
        VANILLA,
        /** 不往公共频道发，只单发给提问的人 */
        NONE,
        /** 只走动作栏（聊天栏被别的插件吞掉时的首选） */
        ACTIONBAR,
        /** 动作栏 + 标题，双保险 */
        TITLE,
        /** 聊天栏 + 动作栏 + 标题，全都来一遍 */
        MULTI;

        static Style parse(String raw) {
            String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            return switch (s) {
                case "vanilla" -> VANILLA;
                case "none" -> NONE;
                case "actionbar", "bar", "titlebar" -> ACTIONBAR;
                case "title" -> TITLE;
                case "multi", "all" -> MULTI;
                default -> BRACKET;
            };
        }
    }

    private final AiPlayerPlugin plugin;
    /** 待确认的发言。注册表而非单字段：并发的两条回复不会互相顶掉回执。 */
    private final Map<UUID, Pending> pendings = new ConcurrentHashMap<>();
    /** 这段时间内认为"以玩家身份发言"这条路不通，直接走硬送达，省掉白等。 */
    private volatile long skipAsPlayerUntilMs;
    private long lastAdminWarnMs;
    private String lastResult = "（还没发过言）";
    /** 最近一次硬送达的统计，供 status 展示。 */
    private volatile HardResult lastHard;

    public ChatDelivery(AiPlayerPlugin plugin) { this.plugin = plugin; }

    public String lastResult() { return lastResult; }

    public HardResult lastHardResult() { return lastHard; }

    /** 玩家聊天频道是否还在"判定不通"的冷静期里（status 用）。 */
    public boolean cooldownActive() { return System.currentTimeMillis() < skipAsPlayerUntilMs; }

    public long cooldownRemainingSeconds() { return Math.max(0L, (skipAsPlayerUntilMs - System.currentTimeMillis()) / 1000L); }

    /* ---------------- 公共 / 提及频道 ---------------- */

    /**
     * 让 AI 在公共频道说话。
     * @param reporter 自检回调（/aiplayer say 用），可为 null
     */
    public void sayPublic(String answer, String nickname, Player asker, Consumer<String> reporter) {
        String mode = configMode();
        if (wantAsPlayer(mode)) {
            if (sendAsPlayer(answer, nickname, asker, false, reporter)) return;
        }
        if (mode.equals("asplayer")) {
            lastResult = "§c未发出：delivery-mode=asplayer 锁定了玩家聊天频道，但这条路现在不可用";
            warn("AI 想说话但没法以玩家身份发言（AI 不在线或已离线）。delivery-mode=asplayer 不会自动兜底，建议改 auto。");
            if (reporter != null) reporter.accept(lastResult);
            return;
        }
        hardBroadcast(answer, nickname, asker, whyBroadcast(mode), false, reporter);
        plugin.speakEffects();
    }

    /** 公共回复没走"玩家聊天频道"时的准确原因（写日志与自检结果用）。 */
    private String whyBroadcast(String mode) {
        if (!mode.equals("auto")) return "按配置固定硬送达（delivery-mode=" + mode + "）";
        Player npc = plugin.npcs().player();
        if (npc == null || !npc.isOnline() || npc.isDead()) return "AI 当前不在线（用 /aiplayer npc spawn 让它重新进服）";
        if (!plugin.getConfig().getBoolean("persona.chat-as-player", true)) return "配置关了以玩家身份发言（persona.chat-as-player=off）";
        if (cooldownActive()) return "玩家聊天频道正在冷静期（" + cooldownRemainingSeconds() + " 秒后自动复测）";
        return "玩家聊天频道不可用";
    }

    /** 有人私聊 AI 时回复他本人（绝不外泄到公共频道）。 */
    public void sayPrivate(String answer, String nickname, Player target, Consumer<String> reporter) {
        if (target == null || !target.isOnline()) {
            lastResult = "§c未送达：提问的玩家已下线";
            if (reporter != null) reporter.accept("§c提问的玩家已下线，回复没发出。");
            return;
        }
        String msgMode = configString("persona.whisper-reply-mode", "direct").toLowerCase(Locale.ROOT);
        if (msgMode.equals("msg") && wantAsPlayer(configMode())) {
            if (sendAsPlayer(answer, nickname, target, true, reporter)) return;
        }
        directToTarget(answer, nickname, target, reporter);
        plugin.speakEffects();
    }

    /* ---------------- 主路径：以玩家身份发言（带回执） ---------------- */

    private boolean wantAsPlayer(String mode) {
        if (!mode.equals("auto") && !mode.equals("asplayer")) return false;
        Player npc = plugin.npcs().player();
        if (npc == null || !npc.isOnline() || npc.isDead()) return false;
        if (!plugin.getConfig().getBoolean("persona.chat-as-player", true)) return false;
        return System.currentTimeMillis() >= skipAsPlayerUntilMs;
    }

    private String configMode() {
        String mode = configString("persona.delivery-mode", "auto").toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "asplayer", "player", "true" -> "asplayer";
            case "broadcast", "fallback" -> "broadcast";
            case "safe", "hard" -> "safe";
            default -> "auto";
        };
    }

    private Style configStyle() { return Style.parse(configString("persona.fallback-style", "bracket")); }

    /**
     * 走 Player#chat / performCommand 发言并登记回执确认。返回 false 表示当场就失败，调用方应立刻兜底。
     * @param whisper true=用 /msg 命令回私聊（不产生公开聊天事件，只认客户端回显）
     */
    private boolean sendAsPlayer(String answer, String nickname, Player target, boolean whisper, Consumer<String> reporter) {
        Player npc = plugin.npcs().player();
        if (npc == null || !npc.isOnline()) return false;
        Pending p = new Pending(answer, normalize(answer), nickname, target, whisper, reporter);
        try {
            pendings.put(p.id, p);
            plugin.whispers().noteNpcOutgoing(answer);
            if (whisper && target != null) {
                // 必须走命令而不是 npc.chat("msg ...")：后者会触发公开聊天事件，
                // 私聊内容会被当成公共聊天广播出去（等于当着全服泄露）。
                npc.performCommand("msg " + target.getName() + " " + answer);
            } else {
                npc.chat(answer);
            }
        } catch (Throwable t) {
            pendings.remove(p.id);
            // 私聊这一条失败不该连累公共频道的玩家聊天路径（/msg 命令不存在是很常见的服务器配置）
            if (!whisper) markAsPlayerBroken("调用失败：" + t.getClass().getSimpleName() + " " + t.getMessage());
            return false;
        }
        long delay = Math.max(2L, plugin.getConfig().getLong("persona.verify-delay-ticks", 16L));
        Bukkit.getScheduler().runTaskLater(plugin, () -> verify(p), delay);
        return true;
    }

    private void verify(Pending p) {
        pendings.remove(p.id);
        if (p.cancelled) {
            if (!p.whisper) markAsPlayerBroken("聊天事件被其他插件取消");
            fallbackAfterFailure(p, "被其他插件取消（多半是聊天频道/格式插件或 auth 登录插件拦的）");
            return;
        }
        if (p.eventConfirmed) {
            ok(p, WAY_PLAYER_CHAT + "已确认送达");
            return;
        }
        if (p.echoConfirmed) {
            ok(p, "已送达（AI 客户端收到了这条聊天的回显）");
            return;
        }
        // 私聊走 /msg 失败只影响那一条回复（马上改直发），不该连累公共频道的玩家聊天路径
        if (!p.whisper) markAsPlayerBroken("聊天管线里没有产生这条发言");
        fallbackAfterFailure(p, p.whisper
            ? "服务器的 /msg 没能把它送出去（可能没有 msg 命令或被拦截）"
            : "发言没进服务器聊天管线（被静默丢弃，常见原因是 AI 还没通过登录插件验证，或被刷屏/聊天插件拦下）");
    }

    /** 插件被关闭/重载时，调度器上待确认的回执任务会被取消 —— 那些话必须补发，否则一个字都不会出现。 */
    public void flushPending() {
        if (pendings.isEmpty()) return;
        for (Pending p : List.copyOf(pendings.values())) {
            pendings.remove(p.id);
            if (p.eventConfirmed || p.echoConfirmed) continue;
            fallbackAfterFailure(p, "插件在等待送达确认时重载了，那条没能确认");
        }
    }

    private void fallbackAfterFailure(Pending p, String reason) {
        if (p.whisper) {
            // 私聊绝不外泄：直发给提问的人
            if (p.asker != null && p.asker.isOnline()) {
                directToTarget(p.text, p.nickname, p.asker, p.reporter);
                plugin.speakEffects();
                return;
            }
            lastResult = "§c私聊回复没能送达（对方已下线）：" + reason;
            warn("私聊回复送达失败：" + reason);
            if (p.reporter != null) p.reporter.accept(lastResult);
            return;
        }
        hardBroadcast(p.text, p.nickname, p.asker, reason, true, p.reporter);
        plugin.speakEffects();
    }

    private void ok(Pending p, String how) {
        lastResult = "§a✔ 送达方式：" + how;
        plugin.getLogger().info("[AI嘴巴] " + truncate(p.text) + " → " + how + "（" + (p.endMs() - p.startMs) + " ms）");
        if (p.reporter != null) p.reporter.accept(lastResult);
    }

    /* ---------------- 兜底路径：硬送达 ---------------- */

    /**
     * 硬送达：绕过聊天管线，逐个在线玩家直接发包，并把真实收件人数记下来。
     * 这是"广播也看不到"的根治点 —— 不再依赖一次发完就不知道结果的全局广播。
     *
     * @param asker 点名/被回复的那个人（style=none 时只单发给它）
     * @param suspectBlocked 是否要顺带提醒管理员"以玩家身份发言被吞了"
     */
    private HardResult hardBroadcast(String answer, String nickname, Player asker,
                                     String reason, boolean suspectBlocked, Consumer<String> reporter) {
        Style style = configStyle();
        if (style == Style.NONE) {
            if (asker != null && asker.isOnline()
                    && plugin.getConfig().getBoolean("persona.mention-direct-fallback", true)) {
                directToTarget(answer, nickname, asker, reporter);
                plugin.getLogger().info("[AI嘴巴] 公共兜底已关闭（fallback-style=none），已改为只私聊回复提问者。");
                return null;
            }
            lastResult = "§e△ 公共频道兜底已关闭（fallback-style=none）。原因：" + reason;
            plugin.getLogger().info("[AI嘴巴] 按配置不往公共频道发（fallback-style=none）。原因：" + reason);
            if (reporter != null) reporter.accept(lastResult);
            return null;
        }

        plugin.whispers().noteNpcOutgoing(answer);
        Component line = renderLine(style, nickname, answer);
        boolean bar = useActionBar(style);
        boolean title = useTitle(style);
        Title popup = title ? titleOf(answer) : null;   // 循环外建一次，别给每个玩家都造一份
        int ok = 0, attempted = 0;
        List<String> failures = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            attempted++;
            try {
                p.sendMessage(line);
                if (bar) p.sendActionBar(line);
                if (title) p.showTitle(popup);
                ok++;
            } catch (Throwable t) {
                failures.add(p.getName() + "(" + t.getClass().getSimpleName() + ")");
            }
        }
        HardResult result = new HardResult(attempted, ok, failures, channelLabel(style, bar, title));
        lastHard = result;
        lastResult = result.anySent()
            ? "§e△ 送达方式：" + WAY_BROADCAST + "｜" + result.describe() + (suspectBlocked ? "｜原因：" + reason : "")
            : "§c未送达：" + WAY_BROADCAST + "｜一个在线玩家都没收到。原因：" + reason;
        plugin.getLogger().info("[AI嘴巴] 硬送达（样式 " + style.name().toLowerCase(Locale.ROOT) + "）：" + result.describe()
            + (suspectBlocked ? "｜原因：" + reason : "") + "｜内容：" + truncate(answer));
        if (!result.anySent()) {
            warnAdmins("§c[AI玩家] 「" + plugin.npcs().name() + "」的回复一条都没送出去（在线玩家 "
                + attempted + " 人全部失败）。请用 /aiplayer delivery diagnose 逐条通道定位是哪个插件在拦。");
        } else if (suspectBlocked) {
            warnAdmins("§c[AI玩家] 「" + plugin.npcs().name() + "」以玩家身份发言被吞掉了（" + reason
                + "），已改用硬送达（" + result.channels() + "，送达 " + result.sent() + "/" + result.attempted() + " 人）。"
                + "想让它真像玩家一样说话，请检查聊天/登录类插件是否放它过；"
                + "也可以 /aiplayer persona delivery safe 固定走这条必定可见的路。");
        }
        if (reporter != null) reporter.accept(lastResult);
        return result;
    }

    /** 私聊直发：只给那一个人看，走系统消息，必定可见。 */
    private void directToTarget(String answer, String nickname, Player target, Consumer<String> reporter) {
        plugin.whispers().noteNpcOutgoing(answer);
        try {
            target.sendMessage(legacy("§d[「" + nickname + "」对你说] §f" + answer));
            lastResult = "§a✔ 送达方式：" + WAY_DIRECT + "（收件人 " + target.getName() + "）";
        } catch (Throwable t) {
            lastResult = "§c私聊直发失败：" + t.getClass().getSimpleName();
            plugin.getLogger().warning("[AI嘴巴] 私聊直发给 " + target.getName() + " 失败：" + t);
        }
        plugin.getLogger().info("[AI嘴巴] 私聊直发给 " + target.getName() + "：" + truncate(answer));
        if (reporter != null) reporter.accept(lastResult);
    }

    /* ---------------- 渲染 ---------------- */

    private Component renderLine(Style style, String nickname, String answer) {
        return switch (style) {
            case VANILLA -> Component.translatable("chat.type.text",
                Component.text(plugin.npcs().name()), Component.text(answer));
            case ACTIONBAR, TITLE -> legacy("§b「" + nickname + "」§f " + answer);
            default -> legacy("§b[「" + nickname + "」] §f" + answer);
        };
    }

    private boolean useActionBar(Style style) {
        return switch (style) {
            case ACTIONBAR, TITLE, MULTI -> true;
            default -> plugin.getConfig().getBoolean("persona.hard-send-actionbar", true);
        };
    }

    private boolean useTitle(Style style) {
        return switch (style) {
            case TITLE, MULTI -> true;
            default -> plugin.getConfig().getBoolean("persona.hard-send-title", false);
        };
    }

    private String channelLabel(Style style, boolean bar, boolean title) {
        List<String> out = new ArrayList<>();
        out.add("聊天栏");
        if (bar) out.add("动作栏");
        if (title) out.add("标题");
        return String.join("+", out) + "（样式 " + style.name().toLowerCase(Locale.ROOT) + "）";
    }

    private Title titleOf(String answer) {
        return Title.title(
            legacy("§b「" + plugin.npcs().name() + "」"),
            legacy("§f" + clip(answer, 60)),
            Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2500), Duration.ofMillis(400)));
    }

    private static Component legacy(String raw) {
        return LegacyComponentSerializer.legacySection().deserialize(raw);
    }

    /* ---------------- 回执来源：聊天事件 ---------------- */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onAsyncChat(AsyncChatEvent event) {
        String text;
        try { text = PlainTextComponentSerializer.plainText().serialize(event.message()); }
        catch (Throwable t) { text = ""; }
        noteChatAttempt(event.getPlayer().getUniqueId(), text, event.isCancelled());
    }

    /** 有些服务器开了旧版聊天序列化（或别的插件转发），补一条旧事件监听以免漏判。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    @SuppressWarnings("deprecation")
    public void onLegacyChat(AsyncPlayerChatEvent event) {
        noteChatAttempt(event.getPlayer().getUniqueId(), event.getMessage(), event.isCancelled());
    }

    private void noteChatAttempt(UUID who, String text, boolean cancelled) {
        if (pendings.isEmpty()) return;
        Player npc = plugin.npcs().player();
        if (npc == null || !npc.getUniqueId().equals(who)) return;
        String n = normalize(text);
        if (n.isEmpty()) return;
        for (Pending p : pendings.values()) {
            if (p.whisper || !matches(n, p.key)) continue;
            if (cancelled) p.cancelled = true;
            else p.eventConfirmed = true;
        }
    }

    /* ---------------- 回执来源：AI 客户端收到的回显 ---------------- */

    /** WhisperHook 每认读到一条"服务器推给 AI 客户端"的文本就调这里。 */
    public void noteIncoming(String receivedText) {
        if (pendings.isEmpty()) return;
        String n = normalize(receivedText);
        if (n.isEmpty()) return;
        for (Pending p : pendings.values()) {
            if (p.echoConfirmed || p.eventConfirmed) continue;
            if (matches(n, p.key)) p.echoConfirmed = true;
        }
    }

    /**
     * 指纹比对：<b>不能</b>要求"收到的文本完整包含整条回复"。聊天/格式插件普遍会加前缀、改写、截断，
     * 按整条匹配必然失真 —— 轻则误判成没送出去从而同一条话发两遍，重则永远等不到回执。
     * 这里放宽到：完全相等 / 互相包含 / 头尾都对得上。
     */
    static boolean matches(String received, String key) {
        if (received == null || key == null) return false;
        if (key.isEmpty() || received.isEmpty()) return false;
        if (received.equals(key) || received.contains(key)) return true;
        // 插件截断后回显的片段：只有收到的那段够长才认，避免短片段误命中别人的话
        if (received.length() >= 6 && key.contains(received)) return true;
        if (key.length() >= 12) {
            String head = key.substring(0, 8);
            String tail = key.substring(key.length() - 6);
            if (received.contains(head) && (received.contains(tail) || received.length() > key.length() * 2L)) return true;
        }
        return false;
    }

    /* ---------------- 诊断：逐条通道打探针 ---------------- */

    private enum Probe { CHAT, ACTIONBAR, TITLE, SERVER_BROADCAST, NPC_CHAT, CHAT_AND_BAR }

    /**
     * {@code /aiplayer delivery diagnose}：逐条通道打探针，一次问清"这台服务器上哪条通道真的能显示"。
     * 这是定位"广播和聊天都看不到"的唯一可靠办法 —— 服务器端看不到客户端到底画没画出来。
     */
    public void diagnose(CommandSender sender, String targetName) {
        Player target;
        if (targetName != null && !targetName.isBlank()) {
            target = Bukkit.getPlayerExact(targetName);
            if (target == null) {
                sender.sendMessage("§c找不到在线玩家：" + targetName + "（探针必须发给一个真人玩家才有意义）。");
                return;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage("§c控制台收不到画面提示。请让一个玩家执行：§e/aiplayer delivery diagnose <玩家名>");
            return;
        }
        String tag = Integer.toHexString((int) (System.nanoTime() & 0xFFFFL));
        sender.sendMessage("§b—— AI 送达通道诊断 ——");
        sender.sendMessage("§7目标：" + target.getName() + "，下面 6 条会依次出现，每条走的通道都不一样。");
        sender.sendMessage("§7看到之后请回报编号：§f/aiplayer delivery report <编号> 例如 §f1,3,5");
        sender.sendMessage("§7一条都看不到 = 客户端整体屏蔽系统消息；只有某几条看不到 = 对应插件在拦那条通道。");
        Probe[] probes = Probe.values();
        for (int i = 0; i < probes.length; i++) {
            Probe probe = probes[i];
            int n = i + 1;
            Bukkit.getScheduler().runTaskLater(plugin, () -> fireProbe(target, n, probe, tag, sender), n * 20L);
        }
        plugin.getLogger().info("[诊断] 已向 " + target.getName() + " 排入 " + probes.length
            + " 条通道探针（标记 " + tag + "），等待 /aiplayer delivery report。");
    }

    private void fireProbe(Player target, int n, Probe probe, String tag, CommandSender reporter) {
        String text = "§b[探针" + n + "§7/" + label(probe) + "§f] 标记 " + tag;
        try {
            switch (probe) {
                case CHAT -> target.sendMessage(legacy(text));
                case ACTIONBAR -> target.sendActionBar(legacy(text));
                case TITLE -> target.showTitle(titleOf("探针 " + n + " · 标记 " + tag));
                case SERVER_BROADCAST -> Bukkit.broadcastMessage(text);
                case NPC_CHAT -> {
                    Player npc = plugin.npcs().player();
                    if (npc == null || !npc.isOnline()) {
                        reporter.sendMessage("§c探针 " + n + " 无法执行：AI 玩家不在线，先 /aiplayer npc spawn。");
                        return;
                    }
                    npc.chat(text);
                }
                case CHAT_AND_BAR -> {
                    target.sendMessage(legacy(text));
                    target.sendActionBar(legacy(text));
                }
            }
        } catch (Throwable t) {
            reporter.sendMessage("§c探针 " + n + "（" + label(probe) + "）发送时抛异常：" + t.getClass().getSimpleName() + " " + t.getMessage());
            plugin.getLogger().warning("[诊断] 探针 " + n + " 失败：" + t);
        }
    }

    private static String label(Probe probe) {
        return switch (probe) {
            case CHAT -> "聊天栏 sendMessage";
            case ACTIONBAR -> "动作栏 sendActionBar";
            case TITLE -> "标题 showTitle";
            case SERVER_BROADCAST -> "全服 broadcastMessage";
            case NPC_CHAT -> "玩家聊天事件（可能被取消）";
            case CHAT_AND_BAR -> "聊天栏+动作栏";
        };
    }

    /** {@code /aiplayer delivery report <编号>}：玩家回报看到了哪几条，据此给出结论。 */
    public void report(CommandSender sender, String seen) {
        Set<Integer> got = new HashSet<>();
        for (String part : seen.split("[^0-9]+")) {
            if (part.isBlank()) continue;
            try { int v = Integer.parseInt(part.trim()); if (v >= 1 && v <= Probe.values().length) got.add(v); }
            catch (NumberFormatException ignored) { }
        }
        sender.sendMessage("§b—— 诊断结论 ——");
        sender.sendMessage("§7你看到了：" + (got.isEmpty() ? "（一条都没有）" : got.stream().sorted().toList()));
        if (got.contains(1)) sender.sendMessage("§a✔ 聊天栏通 §8→ delivery-mode=auto / broadcast 都能用。");
        if (got.contains(2)) sender.sendMessage("§a✔ 动作栏通 §8→ 最稳的通道，聊天插件几乎不会碰它。");
        if (got.contains(3)) sender.sendMessage("§a✔ 标题通 §8→ 可作最终兜底：/aiplayer persona fallback multi。");
        if (got.contains(4)) sender.sendMessage("§a✔ 全服广播通 §8→ Bukkit.broadcastMessage 本身可用。");
        if (got.contains(5)) sender.sendMessage("§a✔ 玩家聊天事件通 §8→ AI 能以真实玩家身份说话，delivery-mode=asplayer 可用。");
        if (got.contains(6)) sender.sendMessage("§a✔ 聊天栏+动作栏双通道通 §8→ 这就是本插件兜底默认走的路。");
        if (got.isEmpty()) {
            sender.sendMessage("§c✘ 六条探针全部不可见 §8→ 这不是本插件的问题。请检查客户端 Chat 设置"
                + "（聊天选项 / F3+H），以及是否有插件在执行 hideChat 或屏蔽全部系统消息。");
        } else if (!got.contains(1) && !got.contains(6)) {
            sender.sendMessage("§c✘ 聊天栏不通 §8→ 有插件在吞系统消息，或客户端屏蔽了聊天栏。"
                + "修法：/aiplayer persona fallback multi（强制叠动作栏+标题）。");
        }
        if (!got.contains(2) && got.contains(1)) {
            sender.sendMessage("§e! 只有聊天栏通、动作栏不通 §8→ 别叠动作栏："
                + "/aiplayer persona fallback bracket，并把 persona.hard-send-actionbar 设为 false。");
        }
        if (!got.contains(5) && got.contains(1)) {
            sender.sendMessage("§e! 玩家聊天事件不通 §8→ 就是它在拦 AI 的发言。查这些插件："
                + "auth/登录类、聊天频道与格式类、反作弊与刷屏拦截、聊天过滤。临时解法：/aiplayer persona delivery safe。");
        }
        plugin.getLogger().info("[诊断] " + sender.getName() + " 报告看到了：" + got.stream().sorted().toList());
    }

    /* ---------------- /aiplayer say 自检 ---------------- */

    public void selfTest(CommandSender sender, String text) {
        String nickname = plugin.getConfig().getString("persona.nickname", plugin.npcs().name());
        sender.sendMessage("§7测试发送：形态 " + plugin.npcs().modeLabel()
            + "｜delivery-mode " + configMode()
            + "｜chat-as-player " + (plugin.getConfig().getBoolean("persona.chat-as-player", true) ? "开" : "关")
            + "｜fallback-style " + configStyle().name().toLowerCase(Locale.ROOT)
            + "｜动作栏 " + (plugin.getConfig().getBoolean("persona.hard-send-actionbar", true) ? "开" : "关")
            + "｜标题 " + (plugin.getConfig().getBoolean("persona.hard-send-title", false) ? "开" : "关")
            + (cooldownActive() ? "§e｜注意：玩家聊天频道正处于 " + cooldownRemainingSeconds() + " 秒冷静期" : ""));
        Player asker = sender instanceof Player p ? p : null;
        sayPublic(text, nickname, asker, sender::sendMessage);
    }

    /* ---------------- 杂项 ---------------- */

    /** /aiplayer persona delivery retry：清掉"这条路不通"的冷静期，下一条就重新尝试。 */
    public void resetCooldown() {
        skipAsPlayerUntilMs = 0L;
        plugin.getLogger().info("[AI嘴巴] 已清除玩家频道冷静期，下一条回复会重新尝试以玩家身份发言。");
    }

    private void markAsPlayerBroken(String reason) {
        long secs = Math.max(10, plugin.getConfig().getLong("persona.asplayer-retry-cooldown-seconds", 120));
        skipAsPlayerUntilMs = System.currentTimeMillis() + secs * 1000L;
        plugin.getLogger().warning("[AI嘴巴] 以玩家身份发言不通（" + reason + "），" + secs
            + " 秒内的回复直接走硬送达，不再白等。可用 /aiplayer say 随时复测。");
    }

    private void warn(String msg) { plugin.getLogger().warning("[AI嘴巴] " + msg); }

    private void warnAdmins(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastAdminWarnMs < 60_000L) return;
        lastAdminWarnMs = now;
        for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("aiplayer.admin")) p.sendMessage(msg);
    }

    private String configString(String path, String def) {
        String v = plugin.getConfig().getString(path, def);
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}]+", "");
    }

    private static String truncate(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    private static String clip(String s, int n) {
        return s == null ? "" : (s.length() > n ? s.substring(0, n) + "…" : s);
    }

    /** 一条待确认的发言。 */
    private static final class Pending {
        final UUID id = UUID.randomUUID();
        final String text;
        final String key;
        final String nickname;
        final Player asker;      // whisper 模式下要私聊回去的人
        final boolean whisper;
        final Consumer<String> reporter;
        final long startMs = System.currentTimeMillis();
        volatile boolean eventConfirmed;
        volatile boolean echoConfirmed;
        volatile boolean cancelled;
        long endMs() { return System.currentTimeMillis(); }

        Pending(String text, String key, String nickname, Player asker, boolean whisper, Consumer<String> reporter) {
            this.text = text;
            this.key = key;
            this.nickname = nickname;
            this.asker = asker;
            this.whisper = whisper;
            this.reporter = reporter;
        }
    }
}