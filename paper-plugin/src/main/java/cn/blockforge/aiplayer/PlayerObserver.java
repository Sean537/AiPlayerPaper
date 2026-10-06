package cn.blockforge.aiplayer;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 观察员：把服务器里每个玩家的动静写进他的档案——
 * 上线/下线、说了什么、放了/挖了什么方块、死了几次、在哪儿有"作品"。
 * 只记录不打扰：这些资料之后喂给 AI 大脑（画像、沉淀、决策）。
 */
public final class PlayerObserver implements Listener {
    private final AiPlayerPlugin plugin;
    /** 每玩家 60 秒只记一条"挖掘流水"，防止档案被刷屏。 */
    private final Map<UUID, Long> lastMineNote = new HashMap<>();
    private final Map<UUID, Integer> mineCount = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> mineTally = new HashMap<>();

    public PlayerObserver(AiPlayerPlugin plugin) { this.plugin = plugin; }

    private boolean isSelf(Player p) {
        return plugin.npcs() != null && (p.getUniqueId().equals(plugin.npcs().uuid())
            || p.getName().equalsIgnoreCase(plugin.npcs().name()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (isSelf(p)) return;
        String known = plugin.memory().get(p.getUniqueId()).facts.isBlank() ? "新朋友" : "老朋友";
        plugin.memory().addEvent(p.getUniqueId(), "上线了（" + known + "）");
        plugin.memory().mark(p.getUniqueId());
        plugin.brain().onPlayerPresenceChanged();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        if (isSelf(p)) return;
        plugin.memory().addEvent(p.getUniqueId(), "下线了");
        plugin.memory().mark(p.getUniqueId());
        plugin.brain().onPlayerPresenceChanged();
    }

    /** 所有聊天都记档（哪怕 AI 不接话），这是"记住每个玩家说了啥"的来源。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player p = event.getPlayer();
        if (isSelf(p)) return;
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (text.isBlank()) return;
        String slim = text.length() > 90 ? text.substring(0, 90) + "…" : text;
        plugin.memory().add(p.getUniqueId(), p.getName(), slim);
        plugin.memory().mark(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player p = event.getPlayer();
        if (isSelf(p)) return;
        Location at = event.getBlockPlaced().getLocation();
        plugin.memory().bumpPlaced(p.getUniqueId(),
            at.getWorld().getName() + "|" + at.getBlockX() + "|" + at.getBlockY() + "|" + at.getBlockZ());
        long total = plugin.memory().placed(p.getUniqueId());
        // 每放满 12 个方块就记一次"作品"事件，并把坐标登记为地标，AI 会去参观
        if (total % 12 == 0) {
            String note = "在 (" + at.getBlockX() + "," + at.getBlockZ() + ") 一带建东西（累计 " + total + " 块，常放 "
                + event.getBlockPlaced().getType().name().toLowerCase(Locale.ROOT).replace('_', ' ') + "）";
            plugin.memory().addEvent(p.getUniqueId(), note);
            plugin.worldMemory().putPoi(p.getName() + "的工地", note, at);
            plugin.memory().mark(p.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        if (isSelf(p)) return;
        plugin.memory().bumpBroken(p.getUniqueId());
        UUID id = p.getUniqueId();
        String block = event.getBlock().getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        mineCount.merge(id, 1, Integer::sum);
        mineTally.computeIfAbsent(id, k -> new HashMap<>()).merge(block, 1, Integer::sum);
        long now = System.currentTimeMillis();
        Long last = lastMineNote.get(id);
        if (last == null || now - last > 60_000L) {
            int n = mineCount.getOrDefault(id, 0);
            if (n >= 4) {
                String top = mineTally.getOrDefault(id, Map.of()).entrySet().stream()
                    .max(Map.Entry.comparingByValue()).map(e -> e.getKey() + "×" + e.getValue()).orElse("?");
                plugin.memory().addEvent(id, "这阵子挖了 " + Math.min(n, 999) + " 块（多为 " + top + "）");
                plugin.memory().mark(id);
                mineCount.put(id, 0);
                mineTally.remove(id);
            }
            lastMineNote.put(id, now);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        if (isSelf(p)) {
            Location at = p.getLocation();
            plugin.worldMemory().putPoi("我的死亡点", "我死过一次的地方，装备可能还掉在这儿", at);
            plugin.memory().noteSelf("我在 (" + at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ() + ") "
                + at.getWorld().getName() + " 死过一次");
            return;
        }
        plugin.memory().bumpDeath(p.getUniqueId());
        String killer = p.getKiller() == null ? "不明原因" : "被 " + p.getKiller().getName() + " 干掉";
        plugin.memory().addEvent(p.getUniqueId(), "死亡（" + killer + "，"
            + p.getLocation().getWorld().getName() + " " + p.getLocation().getBlockX() + "," + p.getLocation().getBlockZ() + "）");
        plugin.memory().mark(p.getUniqueId());
    }
}
