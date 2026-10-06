package cn.blockforge.aiplayer;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 玩家的"耳朵"。
 *
 * 协议自连模式下，AI 是一个真实的 TCP 客户端：别的玩家 /msg 私聊它时，
 * 消息只会被服务器推送到它的聊天窗口（CLIENTBOUND_SYSTEM_CHAT / PLAYER_CHAT 包），
 * 不会触发任何 Bukkit 聊天事件——旧版本插件因此完全"听不见"私聊，
 * askAi 没被调用，API 自然一个 token 都不消耗。
 *
 * 本类负责把这些收到的文本认读出来：
 * 1) 命中 config 的 chat.whisper-patterns（命名组 player / text）→ 当作有人私聊 AI，
 *    一定调用 plugin.askAi(...)，走 private 频道；
 * 2) 收到 relay/广播类消息里 @ 了 AI 的名字或称呼 → 按 mention 频道处理；
 * 3) 忽略 AI 自己发出去的消息的回声，防止自言自语死循环。
 */
public final class WhisperHook {
    private final AiPlayerPlugin plugin;

    /** AI 最近一次对外说的话（防止它自己发的 /msg 回声再触发一次对话）。 */
    private volatile String lastOutgoing = "";
    private volatile long lastOutgoingMs;

    public WhisperHook(AiPlayerPlugin plugin) { this.plugin = plugin; }

    /** 记下 AI 刚说出去的内容（deliver 时调用）。 */
    public void noteNpcOutgoing(String answer) {
        lastOutgoing = normalize(answer);
        lastOutgoingMs = System.currentTimeMillis();
    }

    /**
     * 处理一条"服务器发给 AI 客户端"的纯文本（主线程调用）。
     * @return 是否识别为对 AI 说话并已转给 askAi
     */
    public boolean handle(String receivedText) {
        if (receivedText == null || receivedText.isBlank()) return false;
        if (!plugin.getConfig().getBoolean("chat.listen-client-messages", true)) return false;
        String text = receivedText.trim();

        // 送达回执：这条如果正是 AI 刚说出去的话的回显，说明它真的进了聊天频道
        ChatDelivery mouth = plugin.delivery();
        if (mouth != null) mouth.noteIncoming(text);

        // AI 自己说话的回声：10 秒内、内容归一化后互相包含，就认定为回声
        if (!lastOutgoing.isEmpty() && System.currentTimeMillis() - lastOutgoingMs < 10_000) {
            String n = normalize(text);
            if (n.contains(lastOutgoing) || lastOutgoing.contains(n)) return false;
        }

        for (String regex : plugin.getConfig().getStringList("chat.whisper-patterns")) {
            try {
                Matcher m = Pattern.compile(regex).matcher(text);
                while (m.find()) {
                    String body = m.group("text");
                    if (body == null || body.isBlank()) break;
                    Player from = resolvePlayer(m.group("player"), text);
                    if (from == null) continue; // 认不出说话人，先不冒泡回复
                    plugin.getLogger().info("[AI耳朵] 收到私聊：" + from.getName() + " → " + truncate(body));
                    plugin.askAi(from, body.trim(), "private");
                    return true;
                }
            } catch (IllegalArgumentException bad) {
                plugin.getLogger().warning("[AI耳朵] chat.whisper-patterns 有一条正则无法编译或命名组缺失（需要 <player> 和 <text>）：" + bad.getMessage());
            } catch (Exception ignored) {}
        }

        // 兜底：不含私聊格式、但提到了 AI 名字/称呼的广播文本（QQ/Discord 桥、跨服 chat relay 等）
        String lower = text.toLowerCase(Locale.ROOT);
        String nickname = plugin.getConfig().getString("persona.nickname", plugin.npcs().name());
        String npcName = plugin.npcs().name();
        boolean hitName = (!nickname.isBlank() && lower.contains(nickname.toLowerCase(Locale.ROOT)))
            || lower.contains(npcName.toLowerCase(Locale.ROOT));
        if (!hitName) return false;
        Player from = firstNamedPlayerIn(text);
        if (from == null) return false; // 认不出是谁说的，避免对着空气回复
        plugin.getLogger().info("[AI耳朵] 广播消息里提到了 AI：" + truncate(text));
        plugin.askAi(from, stripNames(text, nickname, npcName), "mention");
        return true;
    }

    /** 从正则捕获组里认出在线玩家：先精确名，再"组里包含某个在线名"，最后"全文只含一个在线名"。 */
    private Player resolvePlayer(String group, String fullText) {
        String g = group == null ? "" : group.trim();
        String gl = g.toLowerCase(Locale.ROOT);
        Player best = null;
        List<Player> hits = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isNpc(p)) continue;
            if (p.getName().equalsIgnoreCase(g)) return p; // 精确命中
            if (!gl.isEmpty() && gl.contains(p.getName().toLowerCase(Locale.ROOT))) {
                if (best == null || p.getName().length() > best.getName().length()) best = p; // 最长名优先，防 Bob/Bobby
            }
            if (!fullText.isEmpty() && fullText.contains(p.getName())) hits.add(p);
        }
        if (best != null) return best;
        return hits.size() == 1 ? hits.get(0) : null;
    }

    /** 广播文本里找说话人：优先"名字出现在被 @ 的词之前"，唯一命中才算。 */
    private Player firstNamedPlayerIn(String text) {
        Player best = null;
        int bestPos = -1;
        int at = text.indexOf('@');
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isNpc(p)) continue;
            int pos = text.indexOf(p.getName());
            if (pos < 0) continue;
            if (at >= 0 && pos > at) continue; // @ 之后的名字是被 @ 的 AI 或别人，不是说话人
            if (best == null || pos < bestPos) { best = p; bestPos = pos; }
        }
        return best;
    }

    private boolean isNpc(Player p) {
        if (p == null) return true;
        if (plugin.npcs().uuid() != null && p.getUniqueId().equals(plugin.npcs().uuid())) return true;
        return p.getName().equalsIgnoreCase(plugin.npcs().name());
    }

    private static String stripNames(String text, String... names) {
        String out = text;
        for (String n : names) {
            if (n == null || n.isBlank()) continue;
            out = out.replace("@" + n, "").replace(n, "");
        }
        return out.trim();
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}]+", "");
    }

    private static String truncate(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }
}
