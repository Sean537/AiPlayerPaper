package cn.blockforge.aiplayer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 安家：在出生点附近找一块平整、干燥、离玩家不太远的地，
 * 盖一间带门、窗、床、工作台、箱子、火把的小屋，把"床的位置"设为它的重生点，
 * 以后每次进服/死亡都从家里醒来。
 */
public final class HomeBase implements Listener {
    private final AiPlayerPlugin plugin;
    private volatile boolean working;
    private static final int W = 7, D = 5, H = 3;   // 屋内 5x3、含墙 7x5、高 3

    public HomeBase(AiPlayerPlugin plugin) { this.plugin = plugin; }

    public boolean isWorking() { return working; }
    public boolean hasHome() { return plugin.worldMemory().home() != null; }

    /** 幂等：已有家且没要求重建就直接完成。异步选址→走过去→盖房→放床→定重生点。 */
    public void ensureBuiltAsync(boolean force) {
        if (working) return;
        Location existing = plugin.worldMemory().home();
        if (existing != null && !force) {
            applyBedSpawn(plugin.worldMemory().bed() != null ? plugin.worldMemory().bed() : existing);
            plugin.getLogger().info("[安家] 已经有家了：" + describe(existing));
            return;
        }
        working = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                Location site = findSite();
                if (site == null) {
                    finish(false, "出生点附近 " + maxDistance() + " 格内找不到合适的平地（也许都在水里/山上）");
                    return;
                }
                Location here = plugin.npcs().location();
                if (here == null || here.getWorld() != site.getWorld()) {
                    placeHouse(site, () -> finish(true, "家在 " + describe(site)));
                    return;
                }
                plugin.getLogger().info("[安家] 选中宅基地 " + describe(site) + "，走过去开工…");
                double speed = plugin.getConfig().getDouble("behavior.explore.move-speed", 0.45);
                Location doorway = site.clone().add(0, 1.0, D / 2.0 + 1.0);
                plugin.behavior().walkTo(doorway, speed,
                    () -> placeHouse(site, () -> finish(true, "家在 " + describe(site))),
                    () -> { // 走不过去就直接传送过去（它是真玩家，允许）
                        if (plugin.npcs().player() != null) plugin.npcs().player().teleport(doorway);
                        else if (plugin.npcs().entity() != null) plugin.npcs().entity().teleport(doorway);
                        placeHouse(site, () -> finish(true, "家在 " + describe(site)));
                    });
            } catch (Exception e) {
                finish(false, "建家流程异常：" + e.getClass().getSimpleName() + " " + e.getMessage());
            }
        });
    }

    private void finish(boolean ok, String msg) {
        working = false;
        if (ok) {
            plugin.getLogger().info("[安家] 完成：" + msg);
            plugin.memory().noteSelf("我建好了自己的家：" + msg);
            plugin.sayAsNpc("我在新家安了张床，以后就从这儿出生啦～谁来做客我泡茶。", null);
        } else {
            plugin.getLogger().warning("[安家] 失败：" + msg);
        }
    }

    /* ---------------- 选址 ---------------- */

    private int maxDistance() { return Math.max(16, plugin.getConfig().getInt("home.max-distance", 96)); }

    /** 螺旋外扩找第一块合格的平地（主线程调用，只做采样，开销很小）。 */
    private Location findSite() {
        Location anchor = plugin.npcs().location();
        if (anchor == null) {
            World w = Bukkit.getWorld(plugin.getConfig().getString("npc.world", "world"));
            if (w == null && !Bukkit.getWorlds().isEmpty()) w = Bukkit.getWorlds().get(0);
            if (w == null) return null;
            anchor = w.getSpawnLocation();
        }
        World w = anchor.getWorld();
        int step = 10, radius = maxDistance();
        for (int ring = 0; ring <= radius / step; ring++) {
            for (int attempt = 0; attempt < (ring == 0 ? 1 : 12); attempt++) {
                double ang = Math.random() * Math.PI * 2;
                int dist = ring == 0 ? 0 : step + (int) (Math.random() * ring * step);
                int x = anchor.getBlockX() + (int) (Math.cos(ang) * dist);
                int z = anchor.getBlockZ() + (int) (Math.sin(ang) * dist);
                if (!w.isChunkLoaded(x >> 4, z >> 4)) { w.getChunkAt(x, z).load(); }
                Location site = evaluate(w, x, z);
                if (site != null) return site;
            }
        }
        return null;
    }

    /** 7x5 范围内：高度基本一致、地面实心、不淹水、头顶有空间 → 合格。返回中心点（地面方块位置）。 */
    private Location evaluate(World w, int cx, int cz) {
        int x0 = cx - W / 2, z0 = cz - D / 2;
        int baseY = w.getHighestBlockAt(cx, cz).getY();
        int diffs = 0, bad = 0;
        for (int x = x0; x < x0 + W; x++) for (int z = z0; z < z0 + D; z++) {
            Block top = w.getHighestBlockAt(x, z);
            if (Math.abs(top.getY() - baseY) > 1) diffs++;
            Material m = top.getType();
            if (m == Material.WATER || m == Material.LAVA || !m.isSolid() || m == Material.BEDROCK) bad++;
            Block above = top.getRelative(0, 1, 0);
            if (above.getType().isSolid()) bad++;
        }
        int area = W * D;
        if (diffs > area * 0.25 || bad > area * 0.15) return null;
        if (baseY < w.getSeaLevel() - 2) return null;
        return new Location(w, cx + 0.5, baseY, cz + 0.5);
    }

    /* ---------------- 盖房 ---------------- */

    /** site=宅基地中心（最上面那块地面方块）。盖 7x5x3 小木屋：门、窗、床、工作台、箱子、熔炉、灯。 */
    private void placeHouse(Location site, Runnable onDone) {
        List<Object[]> specs = new ArrayList<>();   // {Material, dx, dy, dz, facing}
        World w = site.getWorld();
        int bx = site.getBlockX() - W / 2, by = site.getBlockY(), bz = site.getBlockZ() - D / 2;
        // 清理宅基地（削平）
        for (int x = bx; x < bx + W; x++) for (int z = bz; z < bz + D; z++) {
            int topY = w.getHighestBlockAt(x, z).getY();
            for (int y = Math.min(topY, by + H + 1); y > by; y--) w.getBlockAt(x, y, z).setType(Material.AIR);
            if (topY < by) w.getBlockAt(x, by, z).setType(Material.DIRT);
        }
        for (int x = 0; x < W; x++) for (int z = 0; z < D; z++) specs.add(new Object[]{Material.OAK_PLANKS, x, 0, z, null});
        for (int y = 1; y <= H; y++) for (int x = 0; x < W; x++) for (int z = 0; z < D; z++) {
            boolean edge = x == 0 || x == W - 1 || z == 0 || z == D - 1;
            if (!edge) continue;
            if (y == H) { specs.add(new Object[]{Material.OAK_PLANKS, x, y, z, null}); continue; } // 屋顶封板
            boolean door = z == D - 1 && x == W / 2 && y <= 2;          // 南墙中间做门
            if (door && y == 2) continue;                                // 门上半由下半自动带出，不能再写 AIR 擦掉它
            boolean glass = (x == 0 || x == W - 1) && z == D / 2 && y == 2;
            if (door) { specs.add(new Object[]{Material.OAK_DOOR, x, y, z, "SOUTH"}); continue; }
            specs.add(new Object[]{glass ? Material.GLASS : Material.OAK_PLANKS, x, y, z, null});
        }
        // 家具（屋内地面站在 y=1）：床（西北角，床头朝北）、工作台、箱子、熔炉、屋顶嵌灯
        specs.add(new Object[]{Material.RED_BED, 1, 1, 2, "NORTH"});       // 床脚
        specs.add(new Object[]{Material.RED_BED, 1, 1, 1, "NORTH_HEAD"});  // 床头（重生点）
        specs.add(new Object[]{Material.CRAFTING_TABLE, 3, 1, 1, null});
        specs.add(new Object[]{Material.CHEST, 4, 1, 1, "SOUTH"});
        specs.add(new Object[]{Material.FURNACE, 5, 1, 1, "SOUTH"});
        specs.add(new Object[]{Material.SEA_LANTERN, 3, 3, 1, null});      // 嵌在屋顶里，不会掉
        specs.add(new Object[]{Material.SEA_LANTERN, 3, 3, 3, null});

        Location bedHead = new Location(w, bx + 1 + 0.5, by + 1, bz + 1 + 0.5);
        Location doorway = new Location(w, bx + W / 2 + 0.5, by + 1, bz + D + 0.5);
        plugin.getLogger().info("[安家] 开始砌墙：" + specs.size() + " 个方块…");
        plugin.behavior().buildBlocks(specs, new Location(w, bx, by, bz), () -> {
            plugin.worldMemory().setHome(doorway);
            plugin.worldMemory().setBed(bedHead);
            applyBedSpawn(bedHead);
            // 把进服落点也搬到家里（门口）
            plugin.getConfig().set("npc.world", w.getName());
            plugin.getConfig().set("npc.x", doorway.getX());
            plugin.getConfig().set("npc.y", doorway.getY());
            plugin.getConfig().set("npc.z", doorway.getZ());
            plugin.saveConfig();
            plugin.memory().addEvent(plugin.selfUuid(), "亲手建了家（" + describe(doorway) + "），床已设为出生点");
            onDone.run();
        });
    }

    /** 把床的坐标设为 AI 的重生点（Paper 提供直接 API，不需要真"睡"一次）。 */
    public void applyBedSpawn(Location bedHead) {
        Player me = plugin.npcs().player();
        if (me == null || bedHead == null) return;
        try { me.setRespawnLocation(bedHead, true); } catch (Throwable ignored) {}
    }

    /** AI 死亡/重进后的落点保险：一律传送回家。 */
    @EventHandler public void onRespawn(PlayerRespawnEvent event) {
        if (plugin.npcs() == null || !event.getPlayer().getUniqueId().equals(plugin.selfUuid())) return;
        Location bed = plugin.worldMemory().bed();
        Location home = plugin.worldMemory().home();
        Location respawn = bed != null ? bed : home;
        if (respawn != null) event.setRespawnLocation(respawn.clone().add(0, 0.5, 0));
        applyBedSpawn(bed != null ? bed : home);
    }

    /** 手动指定宅基地（/aiplayer home set）。 */
    public void adoptSite(Location center, Runnable onDone) {
        working = true;
        placeHouse(center, () -> { working = false; if (onDone != null) onDone.run(); });
    }

    private static String describe(Location l) {
        return String.format("%s(%.0f, %.0f, %.0f)", l.getWorld().getName(), l.getX(), l.getY(), l.getZ());
    }
}
