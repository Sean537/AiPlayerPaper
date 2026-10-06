package cn.blockforge.aiplayer;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * 公共聊天监听（"耳朵"之一）。三条线同时走：
 * 1) 每条群聊都记进全局流水 + 玩家档案（PlayerObserver 也记，这里喂大脑）；
 * 2) 有人点名（提到 AI 名字/称呼）→ 必回，走高优先队列，冷却也拦不住；
 * 3) 没点名的普通聊天按 persona.public-reply-chance 决定要不要接话。
 * 注意：/msg 私聊不会触发这里的事件——协议自连模式下私聊只进 AI 客户端的聊天窗口，
 * 由 {@link WhisperHook} 通过封包认读，保证"私聊必答"。
 */
public final class ChatListener implements Listener {
    private final AiPlayerPlugin plugin;
    public ChatListener(AiPlayerPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player npc = plugin.npcs().player();
        if (npc != null && event.getPlayer().getUniqueId().equals(npc.getUniqueId())) return; // AI 自己的消息不参与审核与回复
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (!plugin.beforeChat(event.getPlayer(), text)) { event.setCancelled(true); return; }
        Player speaker = event.getPlayer();
        plugin.noteFeed(speaker.getName(), text); // 全局监管流水：一条不漏
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            String nickname = plugin.getConfig().getString("persona.nickname", plugin.npcs().name());
            String npcName = plugin.npcs().name();
            String lower = text.toLowerCase(Locale.ROOT);
            boolean mention = (!nickname.isBlank() && lower.contains(nickname.toLowerCase(Locale.ROOT)))
                || lower.contains(npcName.toLowerCase(Locale.ROOT))
                || lower.contains("@" + npcName.toLowerCase(Locale.ROOT))
                || lower.contains("@" + nickname.toLowerCase(Locale.ROOT));
            int chance = plugin.getConfig().getInt("persona.public-reply-chance", 100);
            // 点名 100% 回复（priority 队列，永不丢弃）；普通聊天按概率接话
            if (mention) plugin.askAiRecorded(speaker, strip(text, nickname, npcName), "mention");
            else if (chance >= 100 || Math.random() * 100 < chance) plugin.askAiRecorded(speaker, text, "public");
        });
    }

    private static String strip(String text, String... names) {
        String out = text;
        for (String n : names) {
            if (n == null || n.isBlank()) continue;
            out = out.replace("@" + n, "").replace(n, "");
        }
        return out.trim();
    }
}
