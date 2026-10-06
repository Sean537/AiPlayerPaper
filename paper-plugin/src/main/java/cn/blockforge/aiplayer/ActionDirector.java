package cn.blockforge.aiplayer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * 行动指挥台：把 AI（大模型）说出的一行行动指令变成身体动作——
 * 走到哪（goto/follow/stop）、做什么（look/wave/jump）、挖哪个方块（break x y z | break 材料 数量）、
 * 放哪个方块（place 材料 x y z）、盖什么形状（build platform|wall|tower|shelter|stairs|bridge|box ...）。
 * 所有破坏/放置都走真实掉落进背包，并受 actions.* 安全配置约束（保护方块、够得着才动手、总量上限）。
 */
public final class ActionDirector {
    private final AiPlayerPlugin plugin;
    private BukkitTask followTask;
    private UUID following;
    private volatile String lastSummary = "（还没指挥过身体）";

    /** handled=是不是身体类动词；ok=执行是否成功；summary=给人看的一句话。 */
    public record Outcome(boolean handled, boolean ok, String summary) {
        public static Outcome notHandled() { return new Outcome(false, false, ""); }
        public static Outcome ok(String s) { return new Outcome(true, true, s); }
        public static Outcome fail(String s) { return new Outcome(true, false, s); }
    }

    public ActionDirector(AiPlayerPlugin plugin) { this.plugin = plugin; }

    public boolean enabled() { return plugin.getConfig().getBoolean("actions.enabled", true); }
    public String lastSummary() { return lastSummary; }

    private static final Map<String, String> CN_MATERIALS = new LinkedHashMap<>();
    static {
        CN_MATERIALS.put("木板", "OAK_PLANKS"); CN_MATERIALS.put("木头", "OAK_LOG");
        CN_MATERIALS.put("原木", "OAK_LOG"); CN_MATERIALS.put("白桦木", "BIRCH_LOG");
        CN_MATERIALS.put("云杉木", "SPRUCE_LOG"); CN_MATERIALS.put("圆石", "COBBLESTONE");
        CN_MATERIALS.put("石头", "STONE"); CN_MATERIALS.put("泥土", "DIRT"); CN_MATERIALS.put("土", "DIRT");
        CN_MATERIALS.put("草块", "GRASS_BLOCK"); CN_MATERIALS.put("沙子", "SAND"); CN_MATERIALS.put("沙", "SAND");
        CN_MATERIALS.put("沙砾", "GRAVEL"); CN_MATERIALS.put("玻璃", "GLASS"); CN_MATERIALS.put("床", "RED_BED");
        CN_MATERIALS.put("火把", "TORCH"); CN_MATERIALS.put("工作台", "CRAFTING_TABLE");
        CN_MATERIALS.put("熔炉", "FURNACE"); CN_MATERIALS.put("箱子", "CHEST"); CN_MATERIALS.put("梯子", "LADDER");
        CN_MATERIALS.put("栅栏", "OAK_FENCE"); CN_MATERIALS.put("门", "OAK_DOOR"); CN_MATERIALS.put("铁门", "IRON_DOOR");
        CN_MATERIALS.put("黑曜石", "OBSIDIAN"); CN_MATERIALS.put("雪块", "SNOW_BLOCK");
        CN_MATERIALS.put("石砖", "STONE_BRICKS"); CN_MATERIALS.put("红石块", "REDSTONE_BLOCK");
    }
    private static final Set<String> SHAPES = Set.of("platform", "wall", "tower", "shelter", "stairs", "bridge", "box");

    /* ---------------- 总入口 ---------------- */

    /** 主线程调用。返回 Outcome：handled=false 时由调用方（大脑）决定怎么兜底。 */
    public Outcome run(String instruction) {
        if (instruction == null || instruction.isBlank()) return Outcome.notHandled();
        String[] head = instruction.trim().split("\\s+", 2);
        String verb = head[0].toLowerCase(Locale.ROOT).replace("：", "");
        String rest = head.length > 1 ? head[1].trim() : "";
        if (!enabled()) return Outcome.fail("身体控制被 actions.enabled=false 关着，我动不了");
        switch (verb) {
            case "goto", "go", "walk", "move_to", "come":
                return gotoInstruction(rest);
            case "follow":
                return followPlayer(rest);
            case "unfollow":
                return stopFollowing("不再跟着了");
            case "stop", "halt", "cancel":
                plugin.behavior().stopJob();
                boolean wasFollowing = following != null;
                stopFollowing(null);
                return Outcome.ok(wasFollowing ? "停下了，也不跟着人了" : "停了下来");
            case "look", "face":
                return lookAt(rest);
            case "wave", "swing", "nod":
                return wave();
            case "jump", "hop":
                return jump();
            case "break", "dig", "chop":
                return breakInstruction(rest);
            case "place", "put", "set_block":
                return placeInstruction(rest);
            case "build":
                if (rest.isEmpty() || SHAPES.contains(rest.split("\\s+")[0].toLowerCase(Locale.ROOT))) return buildInstruction(rest);
                return Outcome.fail("不认识的建筑形状：" + rest.split("\\s+")[0] + "（可选 " + String.join("/", SHAPES) + "）");
            case "collect", "pickup":
                return collectNearby();
            default:
                return Outcome.notHandled();
        }
    }

    /** 大脑统一记录一步行动（日志 + status + 自我记忆）。 */
    private Outcome note(Outcome o) {
        lastSummary = o.summary();
        if (o.summary() != null && !o.summary().isBlank()) {
            plugin.getLogger().info("[身体] " + (o.ok() ? "✔ " : "✘ ") + o.summary());
            plugin.memory().noteSelf(o.summary());
        }
        return o;
    }

    /* ---------------- 移动 ---------------- */

    private Outcome gotoInstruction(String rest) {
        Location here = plugin.npcs().location();
        if (here == null) return note(Outcome.fail("还没有位置，走不了"));
        String[] parts = rest.split("\\s+");
        if (parts.length < 2) return note(Outcome.fail("goto 需要坐标，例如 goto 120 64 -30"));
        World w = here.getWorld();
        int i = 0;
        if (parts.length >= 4) { World ww = Bukkit.getWorld(parts[0]); if (ww != null) { w = ww; i = 1; } }
        try {
            double x = Double.parseDouble(parts[i]), z = Double.parseDouble(parts[i + 1]);
            double y = parts.length > i + 2 ? tryDouble(parts[i + 2], Double.NaN) : Double.NaN;
            Location dest = new Location(w, x, Double.isNaN(y) ? surfaceY(w, x, z) : y, z);
            double dist = here.distance(dest);
            double speed = plugin.getConfig().getDouble("behavior.explore.move-speed", 0.45);
            if (dist > plugin.getConfig().getDouble("actions.goto-max-distance", 400)
                && plugin.getConfig().getBoolean("actions.allow-teleport-far", true)) {
                Entity e = plugin.npcs().entity();
                if (e == null) return note(Outcome.fail("人不在线，没法去"));
                e.teleport(dest);
                return note(Outcome.ok(String.format("太远了（%.0f 格），直接传送到了 %.0f,%.0f,%.0f", dist, dest.getX(), dest.getY(), dest.getZ())));
            }
            final World fw = w; final double fx = x, fz = z;
            plugin.behavior().walkTo(dest, speed,
                () -> note(Outcome.ok(String.format("走到了 %s(%.0f,%.0f,%.0f)", fw.getName(), fx, surfaceY(fw, fx, fz), fz))),
                () -> note(Outcome.ok("去 (" + fx + "," + fz + ") 的路上被地形卡住了")));
            return note(Outcome.ok(String.format("正赶往 %s(%.0f,%.0f)，%.0f 格路", w.getName(), x, z, dist)));
        } catch (NumberFormatException bad) {
            return note(Outcome.fail("坐标没读懂：" + rest));
        }
    }

    private Outcome followPlayer(String name) {
        Player target = findPlayer(name);
        if (target == null) return note(Outcome.fail("找不到玩家：" + name));
        following = target.getUniqueId();
        if (followTask != null) followTask.cancel();
        double speed = plugin.getConfig().getDouble("behavior.explore.move-speed", 0.45);
        followTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            Player p = Bukkit.getPlayer(following);
            if (p == null || !p.isOnline()) { stopFollowing("跟丢人了"); return; }
            Location me = plugin.npcs().location();
            if (me == null || !me.getWorld().equals(p.getWorld())) return;
            if (me.distance(p.getLocation()) > 2.5) plugin.behavior().walkTo(p.getLocation(), speed, null, null);
        }, 20L, 40L);
        return note(Outcome.ok("开始跟着 " + target.getName() + " 走"));
    }

    private Outcome stopFollowing(String summary) {
        if (followTask != null) { followTask.cancel(); followTask = null; }
        following = null;
        return summary == null ? Outcome.ok("取消跟随") : note(Outcome.ok(summary));
    }

    public void shutdown() {
        if (followTask != null) { followTask.cancel(); followTask = null; }
        following = null;
    }

    /* ---------------- 小动作 ---------------- */

    private Outcome lookAt(String rest) {
        Entity e = plugin.npcs().entity();
        if (e == null) return note(Outcome.fail("人不在线"));
        Location here = e.getLocation();
        String[] parts = rest.split("\\s+");
        try {
            if (parts.length >= 3 && isDouble(parts[0])) {
                Location t = new Location(here.getWorld(), Double.parseDouble(parts[0]),
                    Double.parseDouble(parts[1]), Double.parseDouble(parts[2]));
                plugin.npcs().faceTowards(t);
                return note(Outcome.ok("转头看向 (" + parts[0] + "," + parts[1] + "," + parts[2] + ")"));
            }
            Player p = findPlayer(rest);
            if (p != null) {
                plugin.npcs().faceTowards(p.getLocation());
                return note(Outcome.ok("转头看着 " + p.getName()));
            }
        } catch (Exception ignored) {}
        return note(Outcome.fail("look 需要玩家名或 x y z 坐标：" + rest));
    }

    private Outcome wave() {
        Player me = plugin.npcs().player();
        if (me != null) {
            try { me.swingHand(EquipmentSlot.HAND); } catch (Exception ignored) {}
            Location l = me.getLocation();
            l.getWorld().spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER, l.clone().add(0, 1.8, 0), 6, 0.3, 0.3, 0.3);
            return note(Outcome.ok("挥了挥手"));
        }
        Entity e = plugin.npcs().entity();
        if (e != null) {
            e.getWorld().spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER, e.getLocation().clone().add(0, 1.8, 0), 6, 0.3, 0.3, 0.3);
            return note(Outcome.ok("比划了一下（盔甲架形态没有手）"));
        }
        return note(Outcome.fail("人不在线"));
    }

    /** 玩家实体不吃服务端重力，跳跃用一串传送模拟起跳落地（站着不动时才能跳）。 */
    private Outcome jump() {
        Entity e = plugin.npcs().entity();
        if (e == null) return note(Outcome.fail("人不在线"));
        if (plugin.behavior().isBusy()) return note(Outcome.fail("正忙着走路/干活，跳不了"));
        Location base = e.getLocation().clone();
        double[] arc = {0.45, 0.75, 0.45, 0.05};
        for (int t = 0; t < arc.length; t++) {
            final double dy = arc[t];
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                Entity cur = plugin.npcs().entity();
                if (cur == null || plugin.behavior().isBusy()) return;
                Location hop = base.clone(); hop.setY(base.getY() + dy);
                cur.teleport(hop);
            }, 2L + t * 3L);
        }
        return note(Outcome.ok("蹦了一下"));
    }

    /* ---------------- 破坏方块 ---------------- */

    private Outcome breakInstruction(String rest) {
        Entity e = plugin.npcs().entity();
        if (e == null) return note(Outcome.fail("人不在线"));
        Location here = e.getLocation();
        String[] parts = rest.split("\\s+");
        if (parts.length >= 3 && isInt(parts[0]) && isInt(parts[1]) && isInt(parts[2])) {
            int x = Integer.parseInt(parts[0]), y = Integer.parseInt(parts[1]), z = Integer.parseInt(parts[2]);
            int count = parts.length >= 4 && isInt(parts[3]) ? clamp(1, Integer.parseInt(parts[3]), 32) : 1;
            return note(breakAtThen(x, y, z, count, here.getWorld()));
        }
        Material m = parseMaterial(parts[0]);
        if (m == null) return note(Outcome.fail("不认识的目标：" + rest + "（break 后接坐标或方块名）"));
        int count = parts.length >= 2 && isInt(parts[1]) ? clamp(1, Integer.parseInt(parts[1]), 32) : 1;
        Block found = findNearestMaterial(here, m);
        if (found == null) return note(Outcome.fail("附近 " + searchRadius() + " 格内没有" + pretty(m)));
        return note(breakVeinStartingAt(found, m, count));
    }

    /** 走到方块够得着的地方，转头，挖掉；count>1 时顺着同类方块连锁挖。 */
    private Outcome breakAtThen(int x, int y, int z, int count, World w) {
        if (!w.isChunkLoaded(x >> 4, z >> 4)) {
            try { w.getChunkAt(x, z).load(); } catch (Exception ignored) {}
            return Outcome.fail("(" + x + "," + y + "," + z + ") 的区块还在加载，等下再指这个坐标");
        }
        Block target = w.getBlockAt(x, y, z);
        if (target.getType().isAir()) return Outcome.fail("(" + x + "," + y + "," + z + ") 那里是空气，没东西可挖");
        if (isProtected(target.getType())) return Outcome.fail(pretty(target.getType()) + " 在保护名单里，我不能挖");
        if (!plugin.getConfig().getBoolean("actions.break-anywhere", false)
            && target.getLocation().distanceSquared(plugin.npcs().entity().getLocation()) > 64 * 64)
            return Outcome.fail("(" + x + "," + y + "," + z + ") 离我太远了，先 goto 过去");
        Location standAt = plugin.behavior().nearestFreeSpot(target,
            target.getLocation().add(0.5, 0, 0.5), plugin.npcs().location());
        double speed = plugin.getConfig().getDouble("behavior.explore.move-speed", 0.45);
        Material mat = target.getType();
        plugin.behavior().walkTo(standAt, speed, () -> {
            Entity en = plugin.npcs().entity(); if (en == null) return;
            plugin.npcs().faceTowards(target.getLocation().add(0.5, 0.5, 0.5));
            swingArm();
            plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> note(Outcome.ok("挖掉了 " + veinSummary(breakVein(target, mat, count, 0)))), 8L);
        }, () -> note(Outcome.fail("走到 (" + x + "," + y + "," + z + ") 旁边被地形卡住了")));
        return Outcome.ok("正走过去挖 " + pretty(mat) + "（目标 " + x + "," + y + "," + z + "）");
    }

    /** 从起始方块出发，最多挖 count 个同类（找相邻同材质连锁）。 */
    private Outcome breakVeinStartingAt(Block start, Material m, int count) {
        Location standAt = plugin.behavior().nearestFreeSpot(start,
            start.getLocation().add(0.5, 0, 0.5), plugin.npcs().location());
        double speed = plugin.getConfig().getDouble("behavior.explore.move-speed", 0.45);
        plugin.behavior().walkTo(standAt, speed, () -> {
            plugin.npcs().faceTowards(start.getLocation().add(0.5, 0.5, 0.5));
            swingArm();
            plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> note(Outcome.ok("挖掉了 " + veinSummary(breakVein(start, m, count, 0)))), 8L);
        }, () -> note(Outcome.fail("够不到那些" + pretty(m))));
        return Outcome.ok("正走向最近的" + pretty(m) + "准备开挖（计划 " + count + " 块）");
    }

    private int breakVein(Block b, Material m, int count, int done) {
        if (done >= count || b == null || !b.getType().equals(m)) return done;
        if (!breakBlockNow(b, "行动")) { return done; }
        // done 是被累加的参数，不能直接进 lambda：既编不过，而且真编过了递归也会拿到
        // 旧值（下一轮又从同一个 done 起步，count 限制就形同虚设）。
        final int progressed = done + 1;
        Block next = nearestSameMaterialAround(b, m);
        if (next != null && progressed < count) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> breakVein(next, m, count, progressed), 8L);
        }
        return progressed;
    }

    private static String veinSummary(int n) {
        return n <= 0 ? "没挖成（可能被保护/跑了）" : n + " 个方块（掉落物已捡进背包）";
    }

    /** 真实破坏一下：掉落 + 音效 + 捡进背包 + 记账。够不着/被保护返回 false。 */
    public boolean breakBlockNow(Block block, String reason) {
        if (!enabled()) return false;
        if (block == null || block.getType().isAir()) return false;
        if (isProtected(block.getType())) {
            plugin.getLogger().warning("[身体] " + reason + "时想挖 " + pretty(block.getType()) + "，被保护名单拦下");
            return false;
        }
        Material before = block.getType();
        ItemStack tool = toolStack();
        boolean broke;
        try { broke = block.breakNaturally(tool); } catch (Exception e) { broke = false; }
        if (!broke) return false;
        try {
            block.getWorld().playSound(block.getLocation(), org.bukkit.Sound.BLOCK_STONE_BREAK, 0.6f, 1f);
            block.getWorld().spawnParticle(org.bukkit.Particle.EXPLOSION,
                block.getLocation().add(0.5, 0.5, 0.5), 4);
        } catch (Exception ignored) {}
        int collected = collectNearby(block.getLocation().add(0.5, 0.5, 0.5));
        plugin.behavior().countGather(before.name());
        plugin.getLogger().info("[身体] " + reason + "：挖掉 " + pretty(before)
            + " @ " + block.getX() + "," + block.getY() + "," + block.getZ()
            + (collected > 0 ? "（捡了 " + collected + " 样进背包）" : ""));
        plugin.memory().addEvent(plugin.selfUuid(), "挖掉了 " + pretty(before)
            + "（" + block.getWorld().getName() + " " + block.getX() + "," + block.getY() + "," + block.getZ() + "，原因：" + reason + "）");
        return true;
    }

    private Block nearestSameMaterialAround(Block from, Material m) {
        for (int d = 1; d <= 2; d++) {
            for (int dx = -d; dx <= d; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -d; dz <= d; dz++) {
                Block b = from.getRelative(dx, dy, dz);
                if (b.getType().equals(m)) return b;
            }
        }
        return null;
    }

    /* ---------------- 放置方块 ---------------- */

    private Outcome placeInstruction(String rest) {
        String[] parts = rest.split("\\s+");
        if (parts.length < 4) return note(Outcome.fail("place 需要方块名和坐标，例如 place 玻璃 100 64 -25"));
        Material m = parseMaterial(parts[0]);
        if (m == null || m.isAir()) return note(Outcome.fail("不认识的方块名：" + parts[0]));
        try {
            int x = Integer.parseInt(parts[1]), y = Integer.parseInt(parts[2]), z = Integer.parseInt(parts[3]);
            return note(placeAt(m, x, y, z));
        } catch (NumberFormatException bad) {
            return note(Outcome.fail("坐标没读懂：" + rest));
        }
    }

    private Outcome placeAt(Material m, int x, int y, int z) {
        Entity e = plugin.npcs().entity();
        if (e == null) return Outcome.fail("人不在线");
        if (isProtected(m)) return Outcome.fail("不能放 " + pretty(m) + "（在保护名单里）");
        World w = e.getWorld();
        if (!w.isChunkLoaded(x >> 4, z >> 4)) {
            try { w.getChunkAt(x, z).load(); } catch (Exception ignored) {}
            return Outcome.fail("(" + x + "," + y + "," + z + ") 的区块还在加载");
        }
        if (y < w.getMinHeight() || y > w.getMaxHeight() - 1) return Outcome.fail("高度超出世界范围");
        Block target = w.getBlockAt(x, y, z);
        if (!target.getType().isAir() && !target.isReplaceable()) return Outcome.fail("(" + x + "," + y + "," + z + ") 已经有" + pretty(target.getType()));
        if (!standable(x, y, z, w)) return Outcome.fail("那里站着人，不能放");
        double dist = target.getLocation().add(0.5, 0.5, 0.5).distance(e.getLocation());
        double maxReach = plugin.getConfig().getDouble("actions.max-reach", 5) + 25; // 允许先走过去
        if (dist > maxReach) return Outcome.fail("目标离得太远（" + (int) dist + " 格），先 goto 过去再 place");
        Location standAt = plugin.behavior().nearestFreeSpot(target,
            target.getLocation().add(0.5, 0, 0.5), e.getLocation());
        double speed = plugin.getConfig().getDouble("behavior.explore.move-speed", 0.45);
        plugin.behavior().walkTo(standAt, speed, () -> {
            plugin.npcs().faceTowards(target.getLocation().add(0.5, 0.5, 0.5));
            swingArm();
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (consumeOrCreative(m)) {
                    plugin.behavior().placeSingle(target, m);
                    plugin.memory().addEvent(plugin.selfUuid(), "放了一块 " + pretty(m)
                        + "（" + x + "," + y + "," + z + "）");
                    plugin.behavior().countGather("PLACED:" + m.name());
                    note(Outcome.ok("放好了 " + pretty(m) + " @ " + x + "," + y + "," + z));
                } else {
                    note(Outcome.fail("背包里没有 " + pretty(m) + "，配置也没允许凭空放置"));
                }
            }, 6L);
        }, () -> note(Outcome.fail("走不到 (" + x + "," + y + "," + z + ") 旁边")));
        return Outcome.ok("正走过去放 " + pretty(m) + " @ " + x + "," + y + "," + z);
    }

    private static boolean standable(int x, int y, int z, World w) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location l = p.getLocation();
            if (!l.getWorld().equals(w)) continue;
            if (l.getBlockX() == x && l.getBlockZ() == z
                && (l.getBlockY() == y || l.getBlockY() + 1 == y)) return false;
        }
        return true;
    }

    private boolean consumeOrCreative(Material m) {
        Player me = plugin.npcs().player();
        if (me == null) return plugin.getConfig().getBoolean("actions.place-from-creative", true);
        ItemStack one = new ItemStack(m, 1);
        if (me.getInventory().contains(m)) {
            me.getInventory().removeItem(one);
            return true;
        }
        return plugin.getConfig().getBoolean("actions.place-from-creative", true);
    }

    /* ---------------- 盖形状 ---------------- */

    private Outcome buildInstruction(String rest) {
        Entity e = plugin.npcs().entity();
        if (e == null) return note(Outcome.fail("人不在线"));
        String[] parts = rest.split("\\s+");
        String shape = parts[0].toLowerCase(Locale.ROOT);
        Material m = parts.length >= 2 ? parseMaterial(parts[1]) : Material.OAK_PLANKS;
        if (m == null) return note(Outcome.fail("不认识的建材：" + parts[1]));
        List<Object[]> specs = generateShape(shape, m, parts, e.getLocation());
        if (specs == null) return note(Outcome.fail("不支持的形状：" + shape + "（platform/wall/tower/shelter/stairs/bridge/box）"));
        if (specs.isEmpty()) return note(Outcome.fail("这个形状需要实心的方块"));
        int cap = plugin.getConfig().getInt("actions.max-build-blocks", 300);
        final boolean cut = specs.size() > cap;
        // specs 下面被 subList 重新赋值过，就不再是"有效 final"了，不能直接进 lambda。
        // 截断后的那一份单独存成 final 供 lambda 用，语义也更清楚：回调里报的数字
        // 一定是真正建掉的块数，而不是截断前的原始规模。
        final List<Object[]> buildSpecs = cut ? specs.subList(0, cap) : specs;
        final Location origin = anchorFor(shape, e.getLocation());
        final String what = shape + "·" + pretty(m);
        plugin.behavior().buildBlocks(buildSpecs, origin, () -> {
            if (shape.equals("shelter") || shape.equals("tower") || shape.equals("box")) {
                plugin.worldMemory().putPoi("我盖的" + shapeCn(shape),
                    "用 " + pretty(m) + " 盖的 " + buildSpecs.size() + " 块", origin.add(0.5, 0, 0.5));
            }
            plugin.memory().addEvent(plugin.selfUuid(), "盖好了一个 " + what + "（" + buildSpecs.size() + " 块）");
            plugin.sayAsNpc("我的 " + shapeCn(shape) + " 盖好了！用的" + pretty(m) + "，一共 " + buildSpecs.size() + " 块。", null);
            note(Outcome.ok("盖好了 " + what + "（" + buildSpecs.size() + " 块" + (cut ? "，被上限截断" : "") + "）"));
        });
        return note(Outcome.ok("开始动工 " + what + "（约 " + buildSpecs.size() + " 块）"));
    }

    private String shapeCn(String shape) {
        switch (shape) {
            case "platform": return "平台"; case "wall": return "围墙"; case "tower": return "瞭望塔";
            case "shelter": return "小屋"; case "stairs": return "楼梯"; case "bridge": return "小桥";
            case "box": return "盒子房"; default: return shape;
        }
    }

    /** 形状的原点（buildBlocks 按 origin+偏移逐块放）。不同形状不同锚点。 */
    private Location anchorFor(String shape, Location here) {
        BlockFace f = facingOf(here.getYaw());
        int ax = here.getBlockX(), ay = here.getBlockY() - 1, az = here.getBlockZ();
        switch (shape) {
            case "wall": case "bridge": case "stairs":
                return new Location(here.getWorld(), ax + modX(f, 2), ay, az + modZ(f, 2));
            case "shelter": case "box":
                return new Location(here.getWorld(), ax + modX(f, 4), ay, az + modZ(f, 4));
            default:
                return new Location(here.getWorld(), ax, ay, az);
        }
    }

    private List<Object[]> generateShape(String shape, Material m, String[] parts, Location here) {
        List<Object[]> s = new ArrayList<>();
        BlockFace f = facingOf(here.getYaw());
        int a = parts.length >= 3 && isInt(parts[2]) ? clamp(1, Integer.parseInt(parts[2]), 24) : 7;
        int b = parts.length >= 4 && isInt(parts[3]) ? clamp(1, Integer.parseInt(parts[3]), 16) : 3;
        switch (shape) {
            case "platform": {
                int w = a, d = parts.length >= 4 && isInt(parts[3]) ? clamp(1, Integer.parseInt(parts[3]), 24) : a;
                for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) s.add(new Object[]{m, x, 0, z, null});
                break;
            }
            case "wall": {
                int len = a, h = b;
                boolean alongX = f == BlockFace.NORTH || f == BlockFace.SOUTH;
                for (int i = 0; i < len; i++) for (int y = 0; y < h; y++)
                    s.add(new Object[]{m, alongX ? i : 0, y, alongX ? 0 : i, null});
                break;
            }
            case "tower": {
                int h = Math.min(a, 24);
                for (int y = 0; y < h; y++) s.add(new Object[]{m, 0, y, 0, null});
                for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
                    s.add(new Object[]{m, x, h, z, null});
                for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
                    if (!(x == 0 && z == 0)) s.add(new Object[]{Material.TORCH, x, h + 1, z, null});
                break;
            }
            case "stairs": {
                int steps = Math.min(a, 12);
                for (int i = 0; i < steps; i++) for (int y = 0; y <= i; y++)
                    s.add(new Object[]{m, modX(f, i), y, modZ(f, i), null});
                break;
            }
            case "bridge": {
                int len = a, wdt = Math.max(1, parts.length >= 4 && isInt(parts[3]) ? Integer.parseInt(parts[3]) : 2);
                boolean alongX = f == BlockFace.NORTH || f == BlockFace.SOUTH;
                for (int i = 0; i < len; i++) for (int j = 0; j < wdt; j++)
                    s.add(new Object[]{m, alongX ? i : j, 0, alongX ? j : i, null});
                break;
            }
            case "shelter": case "box": {
                int w = a % 2 == 0 ? a + 1 : a; if (w < 5) w = 5; if (w > 11) w = 11;
                int h = 3;
                for (int x = 0; x < w; x++) for (int z = 0; z < w; z++) s.add(new Object[]{m, x, 0, z, null});
                for (int y = 1; y <= h; y++) for (int x = 0; x < w; x++) for (int z = 0; z < w; z++) {
                    boolean edge = x == 0 || x == w - 1 || z == 0 || z == w - 1;
                    if (!edge) continue;
                    boolean door = z == w - 1 && x == w / 2 && y <= 2 && f == BlockFace.SOUTH;
                    if (door && y == 2) continue;
                    if (door) { s.add(new Object[]{Material.OAK_DOOR, x, y, z, "SOUTH"}); continue; }
                    boolean window = (x == 0 || x == w - 1) && z == w / 2 && y == 2;
                    s.add(new Object[]{window ? Material.GLASS : m, x, y, z, null});
                }
                for (int x = 1; x < w - 1; x++) for (int z = 1; z < w - 1; z++) s.add(new Object[]{m, x, h, z, null});
                if (shape.equals("shelter")) {
                    s.add(new Object[]{Material.CRAFTING_TABLE, 1, 1, 1, null});
                    s.add(new Object[]{Material.TORCH, w / 2, 2, w / 2, null});
                }
                break;
            }
            default:
                return null;
        }
        return s;
    }

    /* ---------------- 捡东西 / 环境快照 ---------------- */

    private Outcome collectNearby() {
        Entity e = plugin.npcs().entity();
        if (e == null) return note(Outcome.fail("人不在线"));
        int n = collectNearby(e.getLocation());
        return note(n > 0 ? Outcome.ok("捡起了 " + n + " 样掉落物") : Outcome.fail("脚边没有掉落物"));
    }

    /** 把范围内的掉落物吸进背包（背包满的原地留下）。返回捡到的物品组数。 */
    private int collectNearby(Location center) {
        Player me = plugin.npcs().player();
        if (!plugin.getConfig().getBoolean("actions.collect-drops", true) || me == null) return 0;
        int got = 0;
        for (Entity ent : center.getWorld().getNearbyEntities(center, 3.5, 2.5, 3.5)) {
            if (!(ent instanceof Item item)) continue;
            ItemStack stack = item.getItemStack();
            if (stack.getType().isAir()) continue;
            Map<Integer, ItemStack> left = me.getInventory().addItem(stack);
            if (left.isEmpty()) { item.remove(); got++; }
            else item.setItemStack(left.values().iterator().next());
        }
        if (got > 0) try { center.getWorld().playSound(center, org.bukkit.Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.4f); } catch (Exception ignored) {}
        return got;
    }

    /** 给提示词用的"身边的方块"清单（带坐标，AI 照着点名挖/放）。 */
    public String nearbyBlocksSnapshot(int radius, int limit) {
        Entity e = plugin.npcs().entity();
        if (e == null) return "（不在线）";
        Location here = e.getLocation();
        World w = here.getWorld();
        List<Block> found = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (int dx = -radius; dx <= radius; dx++) for (int dy = -4; dy <= 3; dy++) for (int dz = -radius; dz <= radius; dz++) {
            Block b = here.getBlock().getRelative(dx, dy, dz);
            Material type = b.getType();
            if (type.isAir() || b.isReplaceable()) continue;
            if (isProtected(type)) continue;
            String key = type.name();
            if (seen.merge(key, 1, Integer::sum) > 3) continue;   // 每种最多点名 3 块
            found.add(b);
        }
        found.sort(Comparator.comparingDouble((Block b) -> b.getLocation().distanceSquared(here))
            .thenComparing(b -> interestRank(b.getType())));
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Block b : found) {
            if (n >= limit) break;
            if (n++ > 0) sb.append("、");
            sb.append(b.getType().name()).append('(').append(b.getX()).append(',')
              .append(b.getY()).append(',').append(b.getZ()).append(')');
        }
        return n == 0 ? "（身边没几个方块）" : sb.toString();
    }

    /** 有价值的方块排前面：矿石 > 原木/功能块 > 其它。 */
    private static int interestRank(Material m) {
        String n = m.name();
        if (n.endsWith("_ORE")) return 0;
        if (n.endsWith("_LOG") || n.endsWith("_PLANKS")) return 1;
        if (Set.of("CHEST", "FURNACE", "CRAFTING_TABLE", "RED_BED", "TORCH", "GLASS").contains(n)) return 2;
        return 3;
    }

    /** 一句话环境（给聊天/大脑提示词用）：位置 + 朝向 + 身边方块。 */
    public String environmentLine() {
        Entity e = plugin.npcs().entity();
        if (e == null) return "（AI 现在不在线，动不了身体）";
        Location here = e.getLocation();
        int radius = plugin.getConfig().getInt("actions.scan-radius", 10);
        return String.format("我站在 %s(%.0f,%.0f,%.0f)，面朝%s。身边 %d 格内认得出的方块（挖/放就点这些坐标）：%s",
            here.getWorld().getName(), here.getX(), here.getY(), here.getZ(),
            facingCn(facingOf(here.getYaw())), radius, nearbyBlocksSnapshot(radius, 12));
    }

    public String actionGuide() {
        return "【身体指令】你可以控制这具身体（每轮最多一条 ACTION）：\n"
            + "  goto <x> <z> [y] / walk <x> <y> <z>：走过去（太远会自动传送）\n"
            + "  follow <玩家名> / stop：跟着某人 / 停下\n"
            + "  look <玩家名|x y z> / wave / jump：转头、挥手、蹦一下\n"
            + "  break <x> <y> <z> [数量] / break <方块名> [数量]：挖掉指定方块（数量最多32，顺着连锁）\n"
            + "  place <方块名> <x> <y> <z>：放一个方块（背包里有就扣背包）\n"
            + "  build <platform|wall|tower|shelter|stairs|bridge|box> <方块名> [长] [高]：盖个小造型\n"
            + "  collect：把脚边掉落物捡进背包\n"
            + "挖/放只许点身边 ~48 格内的真实坐标（用我给的环境行里的方块清单）；保护名单方块（基岩/箱子/刷怪笼等）不能碰。";
    }

    /* ---------------- 工具方法 ---------------- */

    private void swingArm() {
        Player me = plugin.npcs().player();
        if (me != null) try { me.swingHand(EquipmentSlot.HAND); } catch (Exception ignored) {}
    }

    private ItemStack toolStack() {
        Material tool = parseMaterial(plugin.getConfig().getString("actions.virtual-tool", "DIAMOND_PICKAXE"));
        return new ItemStack(tool == null ? Material.DIAMOND_PICKAXE : tool);
    }

    public Material parseMaterialNullable(String raw) { return parseMaterial(raw); }

    private static Material parseMaterial(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        String cn = CN_MATERIALS.get(s);
        if (cn != null) return Material.matchMaterial(cn, false);
        s = s.toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        Material m = Material.matchMaterial(s, false);
        if (m == null) m = Material.matchMaterial("minecraft:" + s, false);
        if (m == null && !s.endsWith("S")) m = Material.matchMaterial(s + "S", false);
        return m;
    }

    private boolean isProtected(Material m) {
        List<String> names = protectedList();
        return names.contains(m.name());
    }

    private List<String> protectedList() {
        List<String> out = new ArrayList<>();
        for (String s : plugin.getConfig().getStringList("actions.protected-blocks")) out.add(s.toUpperCase(Locale.ROOT));
        return out;
    }

    private Block findNearestMaterial(Location here, Material m) {
        int r = searchRadius();
        Block best = null; double dist = Double.MAX_VALUE;
        World w = here.getWorld();
        for (int dx = -r; dx <= r; dx++) for (int dy = -4; dy <= 3; dy++) for (int dz = -r; dz <= r; dz++) {
            int x = here.getBlockX() + dx, y = here.getBlockY() + dy, z = here.getBlockZ() + dz;
            if (y < w.getMinHeight() || y > w.getMaxHeight()) continue;
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            Block b = w.getBlockAt(x, y, z);
            if (!b.getType().equals(m)) continue;
            double d = b.getLocation().distanceSquared(here);
            if (d < dist) { dist = d; best = b; }
        }
        return best;
    }

    private int searchRadius() { return Math.max(4, Math.min(32, plugin.getConfig().getInt("actions.search-radius", 16))); }

    private Player findPlayer(String name) {
        if (name == null || name.isBlank()) return null;
        Player exact = Bukkit.getPlayerExact(name.trim());
        if (exact != null && !exact.getUniqueId().equals(plugin.selfUuid())) return exact;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getUniqueId().equals(plugin.selfUuid())) continue;
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(name.trim().toLowerCase(Locale.ROOT))) return p;
        }
        return null;
    }

    private static double surfaceY(World w, double x, double z) {
        return w.getHighestBlockAt((int) Math.floor(x), (int) Math.floor(z)).getY() + 1.0;
    }

    private static BlockFace facingOf(float yaw) {
        double a = ((yaw % 360) + 360) % 360;
        if (a >= 315 || a < 45) return BlockFace.SOUTH;
        if (a < 135) return BlockFace.WEST;
        if (a < 225) return BlockFace.NORTH;
        return BlockFace.EAST;
    }
    private static String facingCn(BlockFace f) {
        switch (f) { case SOUTH: return "南"; case NORTH: return "北"; case EAST: return "东"; default: return "西"; }
    }
    private static int modX(BlockFace f, int n) {
        if (f == BlockFace.WEST) return -n; if (f == BlockFace.EAST) return n; return 0;
    }
    private static int modZ(BlockFace f, int n) {
        if (f == BlockFace.SOUTH) return n; if (f == BlockFace.NORTH) return -n; return 0;
    }

    private static String pretty(Material m) {
        String cn = null;
        for (Map.Entry<String, String> e : CN_MATERIALS.entrySet())
            if (e.getValue().equals(m.name())) { cn = e.getKey(); break; }
        return cn != null ? cn + "（" + m.name().toLowerCase(Locale.ROOT) + "）" : m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static boolean isInt(String s) { return s.matches("-?\\d+"); }
    private static boolean isDouble(String s) { return s.matches("-?\\d+(\\.\\d+)?"); }
    private static double tryDouble(String s, double fallback) { try { return Double.parseDouble(s); } catch (Exception e) { return fallback; } }
    private static int clamp(int lo, int v, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
