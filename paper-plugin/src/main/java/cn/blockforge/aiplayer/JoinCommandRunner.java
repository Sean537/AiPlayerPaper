package cn.blockforge.aiplayer;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 进服后自动执行命令（登录服场景）。
 * 两种发送方式：
 * 1) join-commands.commands：进服后按固定节奏逐条发送（不带 / 也行）；
 * 2) join-commands.prompts：AI 收到含关键词的服务器消息时立刻发送对应命令，
 *    timeout-seconds 内没等到提示也可以兜底发一次；-1 表示只等提示。
 * 命令文本支持 ${secret:名字} 占位符，敏感值用 /aiplayer secret set 加密保存，
 * 不会以明文出现在 config.yml 或日志里。
 */
public final class JoinCommandRunner {
    private final AiPlayerPlugin plugin;
    private volatile Session session;

    private static final Pattern SECRET_REF = Pattern.compile("\\$\\{secret:([A-Za-z0-9_.\\-]+)}");
    private static final Pattern PASSWORD_CMD = Pattern.compile(
        "^(login|l|register|reg|auth|password|pwd|2fa|code|encryptedlogin|elogin)(\\s|$)", Pattern.CASE_INSENSITIVE);

    public JoinCommandRunner(AiPlayerPlugin plugin) { this.plugin = plugin; }

    /** 一条"提示响应"规则。 */
    private static final class Prompt {
        final List<String> keywords; final String command; final int maxTimes;
        int sent; boolean done; int waitSteps; // waitSteps：还剩多少步触发超时兜底，-1=只等提示
        Prompt(List<String> keywords, String command, int waitSteps, int maxTimes) {
            this.keywords = keywords; this.command = command; this.waitSteps = waitSteps; this.maxTimes = maxTimes;
        }
    }

    /** 一次在线期间的发送会话。 */
    private static final class Session {
        final Player player; final long intervalMs;
        final Deque<String> fixed = new ArrayDeque<>();
        final List<Prompt> prompts = new ArrayList<>();
        BukkitTask task; int totalSent; boolean finished;
        Session(Player player, long intervalMs) { this.player = player; this.intervalMs = intervalMs; }
    }

    /* ---------- 生命周期 ---------- */

    /** AI 玩家进服时调用：建立会话，延迟若干秒后开始按节奏发送。 */
    public void onJoin(Player player) {
        cancelSession();
        if (!plugin.getConfig().getBoolean("join-commands.enabled", true)) return;
        long interval = Math.max(200, plugin.getConfig().getLong("join-commands.interval-ms", 800));
        Session s = new Session(player, interval);
        for (String c : plugin.getConfig().getStringList("join-commands.commands")) {
            String n = normalize(c); if (n != null) s.fixed.add(n);
        }
        for (Map<?, ?> m : plugin.getConfig().getMapList("join-commands.prompts")) {
            String send = normalize(m.get("send") == null ? null : String.valueOf(m.get("send")));
            if (send == null) continue;
            List<String> kws = new ArrayList<>();
            Object k = m.get("keywords");
            if (k instanceof List<?> list) for (Object o : list) if (o != null && !String.valueOf(o).isBlank())
                kws.add(String.valueOf(o).toLowerCase(Locale.ROOT));
            int timeout = intOf(m.get("timeout-seconds"), 15);
            int maxTimes = Math.max(1, intOf(m.get("max-times"), 2));
            int waitSteps = timeout < 0 ? -1 : (int) Math.max(1, Math.ceil(timeout * 1000.0 / interval));
            s.prompts.add(new Prompt(kws, send, waitSteps, maxTimes));
        }
        if (s.fixed.isEmpty() && s.prompts.isEmpty()) return;
        session = s;
        long delayTicks = Math.max(1, plugin.getConfig().getLong("join-commands.delay-seconds", 3) * 20);
        long intervalTicks = Math.max(1, interval / 50);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (s.finished) return;
            step(s);
            if (!s.finished) s.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> step(s), intervalTicks, intervalTicks);
        }, delayTicks);
        plugin.getLogger().info("[进服命令] 已就绪：" + s.fixed.size() + " 条固定命令、" + s.prompts.size() + " 条提示响应，"
            + plugin.getConfig().getLong("join-commands.delay-seconds", 3) + " 秒后开始。");
    }

    /** AI 玩家退服时调用。 */
    public void onQuit(Player player) {
        Session s = session;
        if (s != null && s.player == player) cancelSession();
    }

    public void cancelAll() { cancelSession(); }

    private void cancelSession() {
        Session s = session; session = null;
        if (s == null) return;
        s.finished = true;
        if (s.task != null) { try { s.task.cancel(); } catch (Exception ignored) {} s.task = null; }
    }

    /* ---------- 服务器消息 -> 关键词匹配（由 FakePlayerService 的发包泵回调） ---------- */

    public void onSystemMessage(Player player, String text) {
        Session s = session;
        if (s == null || s.finished || text == null || text.isEmpty() || s.player != player) return;
        String lower = text.toLowerCase(Locale.ROOT);
        for (Prompt p : s.prompts) {
            if (p.done || p.sent >= p.maxTimes) continue;
            for (String kw : p.keywords) {
                if (!kw.isEmpty() && lower.contains(kw)) {
                    plugin.getLogger().info("[进服命令] 收到服务器提示（含「" + kw + "」），发送：" + maskIfNeeded(p.command));
                    p.sent++;
                    p.waitSteps = -1; // 已经按提示发过了，本条不再走超时兜底
                    if (p.sent >= p.maxTimes) p.done = true;
                    dispatch(s, p.command);
                    break;
                }
            }
        }
    }

    /* ---------- 定时步：一次只发一条，避免被反作弊刷屏 ---------- */

    private void step(Session s) {
        if (s.finished) return;
        if (!s.player.isOnline()) { finish(s, "AI 已离线，进服命令会话结束。"); return; }
        String next = s.fixed.poll();
        if (next == null) {
            for (Prompt p : s.prompts) if (!p.done && p.waitSteps == 0) {
                p.done = true;
                plugin.getLogger().info("[进服命令] 未等到服务器提示，超时兜底发送：" + maskIfNeeded(p.command));
                next = p.command; break;
            }
        }
        // 固定命令与提示兜底共用同一条发送路径：每步最多发一条
        if (next == null) {
            for (Prompt p : s.prompts) if (!p.done && p.waitSteps > 0) p.waitSteps--;
        } else {
            dispatch(s, next);
        }
        if (next == null && pendingCount(s) == 0) finish(s, null);
    }

    private static int pendingCount(Session s) {
        int n = s.fixed.size();
        for (Prompt p : s.prompts) if (!p.done) n++;
        return n;
    }

    private void finish(Session s, String reason) {
        if (s.finished) return;
        s.finished = true;
        if (s.task != null) { try { s.task.cancel(); } catch (Exception ignored) {} s.task = null; }
        if (reason != null) plugin.getLogger().info("[进服命令] " + reason);
        else plugin.getLogger().info("[进服命令] 序列执行完毕（共发送 " + s.totalSent + " 条）。");
        if (session == s) session = null;
    }

    /* ---------- 发送 ---------- */

    private void dispatch(Session s, String normalizedCmd) {
        String cmd = resolvePlaceholders(normalizedCmd);
        if (cmd == null) return; // 有 ${secret:} 没配置，已警告，跳过这条
        if (s.totalSent >= 50) { finish(s, "单次进服发送命令超过 50 条，已停止（请检查 prompts 是否互相触发）。"); return; }
        s.totalSent++;
        try {
            Bukkit.dispatchCommand(s.player, cmd);
            plugin.getLogger().info("[进服命令] " + s.player.getName() + " → /" + maskIfNeeded(cmd));
            plugin.punishments().audit("AUTOCMD " + s.player.getName() + " -> /" + maskIfNeeded(cmd));
        } catch (Throwable t) {
            plugin.getLogger().warning("[进服命令] 执行 /" + maskIfNeeded(cmd) + " 失败：" + t);
        }
    }

    /** 管理员手动命令：让在线的 AI 玩家立刻执行一条命令（同样支持 ${secret:} 占位符）。 */
    public boolean execAsNpc(String rawCommand, String requesterName) {
        String cmd = normalize(rawCommand);
        if (cmd == null) return false;
        Player npc = plugin.npcs().player();
        if (npc == null || !npc.isOnline()) return false;
        cmd = resolvePlaceholders(cmd);
        if (cmd == null) return false;
        try {
            Bukkit.dispatchCommand(npc, cmd);
            plugin.getLogger().info("[进服命令] " + npc.getName() + " → /" + maskIfNeeded(cmd) + "（由 " + requesterName + " 手动触发）");
            plugin.punishments().audit("AUTOCMD-EXEC by " + requesterName + " -> /" + maskIfNeeded(cmd));
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("[进服命令] 手动执行失败：" + t);
            return false;
        }
    }

    /** 重新走一遍完整的进服命令序列（改完配置或登录失败后用）。 */
    public boolean retryNow() {
        Player npc = plugin.npcs().player();
        if (npc == null || !npc.isOnline()) return false;
        onJoin(npc);
        return true;
    }

    /* ---------- 工具 ---------- */

    /** 去掉开头的 / 并校验非空；无效返回 null。 */
    private static String normalize(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.startsWith("/")) v = v.substring(1).trim();
        return v.isEmpty() ? null : v;
    }

    /** 替换 ${secret:名字}；有找不到的密钥就警告并返回 null（宁可不发，也不发占位符原文）。 */
    private String resolvePlaceholders(String cmd) {
        Matcher m = SECRET_REF.matcher(cmd);
        if (!m.find()) return cmd;
        StringBuilder out = new StringBuilder();
        m.reset();
        while (m.find()) {
            String value = plugin.secretValue(m.group(1));
            if (value == null || value.isEmpty()) {
                plugin.getLogger().warning("[进服命令] 找不到加密值「" + m.group(1) + "」，本条命令未发送。用 /aiplayer secret set " + m.group(1) + " <内容> 设置。");
                return null;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** 日志/界面展示用：登录类命令只保留命令词，参数打码。 */
    public static String maskForDisplay(String cmd) {
        String c = cmd.startsWith("/") ? cmd.substring(1) : cmd;
        return PASSWORD_CMD.matcher(c).find() ? c.trim().split("\\s+", 2)[0] + " ****" : c;
    }

    private String maskIfNeeded(String cmd) {
        return plugin.getConfig().getBoolean("join-commands.hide-password", true) ? maskForDisplay(cmd) : cmd;
    }

    private static int intOf(Object v, int fallback) {
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return fallback; }
    }

    /** 状态栏摘要。 */
    public String summary() {
        List<String> cmds = plugin.getConfig().getStringList("join-commands.commands");
        List<Map<?, ?>> prompts = plugin.getConfig().getMapList("join-commands.prompts");
        boolean enabled = plugin.getConfig().getBoolean("join-commands.enabled", true);
        if (cmds.isEmpty() && prompts.isEmpty()) return "未配置";
        return cmds.size() + " 条固定命令 + " + prompts.size() + " 条提示响应（" + (enabled ? "启用" : "停用") + "）";
    }
}
