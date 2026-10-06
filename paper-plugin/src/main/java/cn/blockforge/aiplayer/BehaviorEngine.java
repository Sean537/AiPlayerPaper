package cn.blockforge.aiplayer;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;

/**
 * 行为引擎：像真人一样一步步走过去（带朝向、可迈台阶/跨障碍），
 * 到位后转头、挥手、破坏方块；支持蓝图逐块放置与程序化盖房。
 * 开了"大脑"之后，随机闲逛自动让路——所有移动都来自决策或任务。
 */
public final class BehaviorEngine {
    private final AiPlayerPlugin plugin;
    private BukkitTask tickTask;
    private BukkitTask walkTask;
    private BukkitTask actionTask;
    private volatile long pauseUntilMs;
    private final List<String> gathered = new ArrayList<>();

    public BehaviorEngine(AiPlayerPlugin plugin) { this.plugin = plugin; }

    public void start() { tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 10L); }
    public void stop() {
        if (tickTask != null) tickTask.cancel();
        cancelWalk();
        if (actionTask != null) { actionTask.cancel(); actionTask = null; }
        tickTask = walkTask = actionTask = null;
    }
    public boolean isBusy() { return walkTask != null || actionTask != null; }
    /** 空闲 = 没在走路/干活，也不在"听人说话"的暂停窗口里。 */
    public boolean isIdle() { return !isBusy() && System.currentTimeMillis() >= pauseUntilMs; }

    /** 有人跟 AI 说话时调用：这段时间里停掉走动、站定听讲话（采集/建造任务不受影响）。 */
    public void pause(long ms) {
        long until = System.currentTimeMillis() + Math.max(0, ms);
        if (until > pauseUntilMs) pauseUntilMs = until;
        if (actionTask == null && walkTask != null) cancelWalk();
    }

    private void tick() {
        Entity npc = plugin.npcs().entity();
        if (npc == null || isBusy()) return;
        if (System.currentTimeMillis() < pauseUntilMs) return;
        // 大脑接管后不再随机瞎逛（那是"闲逛"，不是"探索"）
        if (plugin.brainEnabledForBehavior()) return;
        if (plugin.getConfig().getBoolean("npc.wander", true) && new Random().nextInt(20) == 0) {
            Location base = plugin.npcs().location();
            if (base != null) {
                double r = plugin.getConfig().getDouble("npc.wander-radius", 8);
                double a = Math.random() * Math.PI * 2;
                Location target = base.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r);
                target.setY(surfaceY(target));
                walkTo(target, 0, null, null);
            }
        }
    }

    /** 目标点地表高度（脚站的位置）。 */
    private static double surfaceY(Location l) {
        Block ground = l.getWorld().getHighestBlockAt(l.getBlockX(), l.getBlockZ());
        return ground.getY() + 1.0;
    }

    public void walkTo(Location target, Runnable onArrive) { walkTo(target, 0, onArrive, null); }

    /**
     * 走到 target（speed<=0 用配置默认）。onArrive 到达时执行；onFail 被地形卡死时执行。
     * 遇到 1~2 格高的坎会尝试"跳过去"，跨不过 3 格以上的深沟才放弃。
     */
    public void walkTo(Location target, double speed, Runnable onArrive, Runnable onFail) {
        if (walkTask != null) walkTask.cancel();
        final double v = speed > 0 ? speed : Math.max(0.05, plugin.getConfig().getDouble("npc.move-speed", 0.12));
        walkTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int stuckTicks = 0; double lastDistance = Double.MAX_VALUE;
            public void run() {
                Entity npc = plugin.npcs().entity();
                if (npc == null) { cancelWalk(); if (onFail != null) onFail.run(); return; }
                Location now = npc.getLocation();
                double dx = target.getX() - now.getX(), dz = target.getZ() - now.getZ();
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance < 0.6) { cancelWalk(); if (onArrive != null) onArrive.run(); return; }
                // 目标区块没加载就催加载，加载好之前不算卡
                if (!ensureChunkLoaded(target)) return;
                double stepLen = Math.min(v, distance);
                double nx = now.getX() + dx / distance * stepLen;
                double nz = now.getZ() + dz / distance * stepLen;
                double surface = surfaceY(new Location(now.getWorld(), (int) nx, 0, (int) nz));
                double ny = now.getY() + Math.max(-0.6, Math.min(1.2, surface - now.getY()));
                Location next = new Location(now.getWorld(), nx, ny, nz,
                        (float) Math.toDegrees(Math.atan2(-dx, dz)), 0);
                ensureChunkLoaded(next);
                // 迈台阶/跳坎：脚被挡→上抬 1；还不行→往前找 2~3 格内可站的落脚点
                if (blockedFeet(next)) {
                    Location up1 = next.clone(); up1.setY(next.getY() + 1.0);
                    Location up2 = next.clone(); up2.setY(next.getY() + 2.0);
                    if (!blockedFeet(up1)) next = up1;
                    else if (!blockedFeet(up2)) next = up2;
                    else {
                        Location hop = tryHopOver(now, dx / distance, dz / distance, next);
                        if (hop != null) next = hop;
                        else {
                            if (++stuckTicks > 60 || distance > lastDistance + 2) {
                                cancelWalk();
                                if (onFail != null) onFail.run();
                                return;
                            }
                            return;
                        }
                    }
                }
                stuckTicks = 0; lastDistance = distance;
                plugin.npcs().moveStep(next);
            }
        }, 1L, 1L);
    }

    /** 该位置站人是否被挡（脚下或头顶是实心方块，或脚下没有方块且是深坑外的空中）。 */
    private static boolean blockedFeet(Location l) {
        Block feet = l.getWorld().getBlockAt(l.getBlockX(), (int) Math.floor(l.getY()), l.getBlockZ());
        if (feet.getType().isSolid()) return true;
        return feet.getRelative(0, 1, 0).getType().isSolid();
    }

    /** 前面跨不过去：沿行进方向找 2~4 格内、高度差 ≤3 的可落脚点，"跳"过去（跑酷式越障）。 */
    private Location tryHopOver(Location from, double dirX, double dirZ, Location next) {
        World w = from.getWorld();
        for (double lead = 4.0; lead >= 2.0; lead -= 0.5) {
            int x = (int) Math.round(next.getX() + dirX * lead);
            int z = (int) Math.round(next.getZ() + dirZ * lead);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int top = w.getHighestBlockAt(x, z).getY();
            double dy = (top + 1.0) - next.getY();
            if (dy > -3.5 && dy <= 3.0) {
                Location spot = new Location(w, next.getX() + dirX * lead, top + 1.0, next.getZ() + dirZ * lead, next.getYaw(), 0);
                if (!blockedFeet(spot)) return spot;
            }
        }
        return null;
    }

    /** 目标位置所在区块未加载时发起加载；返回是否已就绪。 */
    private static boolean ensureChunkLoaded(Location l) {
        World w = l.getWorld();
        int cx = l.getBlockX() >> 4, cz = l.getBlockZ() >> 4;
        if (w.isChunkLoaded(cx, cz)) return true; // isChunkLoaded 用区块坐标
        try { w.getChunkAt(l.getBlockX(), l.getBlockZ()).load(); } catch (Exception ignored) {} // getChunkAt 用方块坐标
        return false;
    }

    private void cancelWalk() { if (walkTask != null) { walkTask.cancel(); walkTask = null; } }

    public void startGather(int radius) {
        stopJob();
        actionTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int cooldown = 0;
            public void run() {
                if (cooldown > 0) { cooldown--; return; }
                Entity npc = plugin.npcs().entity();
                if (npc == null) { stopJob(); return; }
                if (walkTask != null) return; // 正走着
                Block target = findTarget(npc.getLocation(), radius);
                if (target == null) { cooldown = 10; return; }
                Location dest = target.getLocation().add(0.5, 0, 0.5);
                dest.setY(surfaceY(dest));
                Location standAt = nearestFreeSpot(target, dest, npc.getLocation());
                walkTo(standAt, 0, () -> {
                    Entity n = plugin.npcs().entity();
                    if (n == null) return;
                    Location look = target.getLocation().add(0.5, 0.5, 0.5);
                    plugin.npcs().faceTowards(look);
                    if (n instanceof Player p) { try { p.swingHand(org.bukkit.inventory.EquipmentSlot.HAND); } catch (Exception ignored) {} }
                    cooldown = Math.max(5, plugin.getConfig().getInt("behavior.gather.interval-ticks", 30));
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (target.getType().isAir()) return;
                        Material material = target.getType();
                        Location dropAt = target.getLocation().add(0.5, 0.2, 0.5);
                        target.getWorld().dropItemNaturally(dropAt, new ItemStack(material, 1));
                        target.setType(Material.AIR);
                        gathered.add(material.name());
                        target.getWorld().playSound(target.getLocation(), Sound.BLOCK_STONE_BREAK, 0.6f, 1f);
                    }, 8L);
                }, null);
            }
        }, 1L, 4L);
    }

    /** 在目标方块附近找一个站得下、离 AI 最近的点。ActionDirector 的 place 动作也要用，所以是 public。 */
    public Location nearestFreeSpot(Block target, Location center, Location from) {
        Location best = center; double bestDist = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            Location l = new Location(target.getWorld(), target.getX() + 0.5 + dx, target.getY() + 1, target.getZ() + 0.5 + dz);
            Block feet = l.getBlock();
            if (feet.getType().isSolid()) continue;
            Block head = feet.getRelative(0, 1, 0);
            if (head.getType().isSolid()) continue;
            double d = l.distanceSquared(from);
            if (d < bestDist) { bestDist = d; best = l; }
        }
        return best;
    }

    private Block findTarget(Location origin, int radius) {
        Set<String> allowed = new HashSet<>(plugin.getConfig().getStringList("behavior.gather.materials"));
        Block best = null; double distance = Double.MAX_VALUE;
        for (int x = -radius; x <= radius; x++) for (int y = -3; y <= 3; y++) for (int z = -radius; z <= radius; z++) {
            Block block = origin.getBlock().getRelative(x, y, z);
            if (!allowed.isEmpty() && !allowed.contains(block.getType().name())) continue;
            if (block.getType().isAir()) continue;
            double d = block.getLocation().distanceSquared(origin);
            if (d < distance) { distance = d; best = block; }
        }
        return best;
    }

    /* ---------------- 建造 ---------------- */

    public void startBuild(String blueprint, Location origin) {
        List<Map<?, ?>> blocks = plugin.getConfig().getMapList("blueprints." + blueprint + ".blocks");
        if (blocks.isEmpty()) blocks = plugin.loadBlueprint(blueprint);
        if (blocks.isEmpty()) return;
        List<Object[]> specs = new ArrayList<>();
        for (Map<?, ?> row : blocks) {
            try {
                specs.add(new Object[]{
                    Material.valueOf(String.valueOf(row.get("material")).toUpperCase(Locale.ROOT)),
                    Integer.parseInt(String.valueOf(row.get("x"))),
                    Integer.parseInt(String.valueOf(row.get("y"))),
                    Integer.parseInt(String.valueOf(row.get("z"))),
                    row.get("facing") == null ? null : String.valueOf(row.get("facing"))});
            } catch (Exception ignored) {}
        }
        buildBlocks(specs, origin, () -> Bukkit.broadcastMessage("§a[" + plugin.npcs().name() + "] 蓝图建造完成。"));
    }

    /**
     * 逐块建造。specs 每行 {Material, dx, dy, dz, facing或null}，AI 转头+音效一样一样放，
     * 完工执行 onDone。门/床/朝向自动处理。
     */
    public void buildBlocks(List<Object[]> specs, Location origin, Runnable onDone) {
        stopJob();
        if (specs.isEmpty()) { if (onDone != null) onDone.run(); return; }
        final int per = Math.max(1, plugin.getConfig().getInt("behavior.build.blocks-per-action", 2));
        actionTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int index = 0;
            public void run() {
                if (index >= specs.size()) {
                    if (actionTask != null) { actionTask.cancel(); actionTask = null; }
                    if (onDone != null) onDone.run();
                    return;
                }
                Entity npc = plugin.npcs().entity();
                int end = Math.min(specs.size(), index + per);
                while (index < end) {
                    Object[] row = specs.get(index);
                    try {
                        Material m = (Material) row[0];
                        Block block = origin.getWorld().getBlockAt(
                            origin.getBlockX() + (Integer) row[1], origin.getBlockY() + (Integer) row[2],
                            origin.getBlockZ() + (Integer) row[3]);
                        if (!ensureChunkLoaded(block.getLocation())) return; // 等区块，下轮从当前方块继续
                        if (npc != null) plugin.npcs().faceTowards(block.getLocation().add(0.5, 0.5, 0.5));
                        applyBlock(block, m, row[4] == null ? null : String.valueOf(row[4]));
                        if (!m.isAir())
                            origin.getWorld().playSound(block.getLocation(),
                                m.equals(Material.OAK_PLANKS) || m.equals(Material.SPRUCE_PLANKS)
                                    ? Sound.BLOCK_WOOD_PLACE : Sound.BLOCK_STONE_PLACE, 0.45f, 1f);
                    } catch (Exception ignored) {}
                    index++;
                }
            }
        }, 1L, 2L);
    }

    /** 放置并处理朝向：床（含头/脚两格）、门（下半自动带上半）、箱子/熔炉等 Directional。 */
    private static void applyBlock(Block block, Material m, String facing) {
        block.setType(m);
        if (facing == null || m.isAir()) return;
        try {
            BlockData data = block.getBlockData();
            String fu = facing.toUpperCase(Locale.ROOT);
            if (data instanceof Bed bed) {
                bed.setFacing(org.bukkit.block.BlockFace.valueOf(fu.replace("_HEAD", "")));
                bed.setPart(fu.endsWith("_HEAD") ? Bed.Part.HEAD : Bed.Part.FOOT);
                block.setBlockData(bed);
            } else if (data instanceof Directional d) {
                d.setFacing(org.bukkit.block.BlockFace.valueOf(fu));
                block.setBlockData(d);
            }
        } catch (Exception ignored) {}
    }

    /**
     * 记一笔"做了一件事"（挖掉/采集/放置），供 status 与大脑统计用。
     * ActionDirector 的挖方块与放方块都会来这里记账。列表有上限：大脑会一直跑下去，
     * 无界增长就是个慢性内存泄漏。
     */
    public void countGather(String key) {
        if (key == null || key.isBlank()) return;
        synchronized (gathered) {
            gathered.add(key);
            while (gathered.size() > 500) gathered.remove(0);
        }
    }

    /**
     * 放一块方块。ActionDirector 的 place 动作走到位后调它，
     * 和蓝图建造共用同一套"朝向处理 + 放置音效"逻辑（applyBlock）。
     */
    public void placeSingle(Block block, Material material) {
        if (block == null || material == null || material.isAir()) return;
        if (!ensureChunkLoaded(block.getLocation())) return;   // 区块没加载就放弃，调用方下一轮会重试
        block.setType(material);
        block.getWorld().playSound(block.getLocation(),
            material.equals(Material.OAK_PLANKS) || material.equals(Material.SPRUCE_PLANKS)
                ? Sound.BLOCK_WOOD_PLACE : Sound.BLOCK_STONE_PLACE, 0.45f, 1f);
    }

    public void stopJob() {
        cancelWalk();
        if (actionTask != null) { actionTask.cancel(); actionTask = null; }
    }
    public int gatheredCount() { return gathered.size(); }
    public String gatheredSummary() {
        if (gathered.isEmpty()) return "（没采到东西）";
        Map<String, Integer> tally = new LinkedHashMap<>();
        for (String g : gathered) tally.merge(g, 1, Integer::sum);
        List<String> parts = new ArrayList<>();
        tally.forEach((k, v) -> parts.add(k.toLowerCase(Locale.ROOT).replace('_', ' ') + "×" + v));
        return String.join("、", parts);
    }
}
