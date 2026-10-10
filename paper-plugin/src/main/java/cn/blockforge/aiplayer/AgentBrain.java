package cn.blockforge.aiplayer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * 大脑：没人找它的时候它也"活着"。
 * 每 brain.tick-seconds 醒一次——看一眼处境（地图记忆、玩家档案、刚聊了什么、
 * 家里情况），让 LLM 选下一步行动（探索/采矿/建家/回家/串门/插话/执行管理命令），
 * 然后执行并把结果写进记忆。没有 API 时退回脚本化日程，照样不闲着。
 */
public final class AgentBrain {
    private final AiPlayerPlugin plugin;
    private BukkitTask tickTask;
    private BukkitTask consolidateTask;
    private volatile boolean busy;
    private long feedSeen;                       // 已读到全局聊天第几条
    private long lastGreetMs;
    private long lastActionResultMs;
    private String lastActionResult = "（还没开始）";
    private final List<String> recentActions = new ArrayList<>();
    private long tripsThisSession;
    /** 玩家下达的直接指令队列。发给大脑的指令在下一次空闲时被处理，优先于自主决策。 */
    private final Deque<Order> orders = new ArrayDeque<>();

    /** 玩家下达的直接指令。issuer=null 表示系统/自主。 */
    private record Order(String text, String issuer) {}

    public AgentBrain(AiPlayerPlugin plugin) { this.plugin = plugin; }

    public boolean enabled() { return plugin.getConfig().getBoolean("brain.enabled", true); }
    public boolean isBusy() { return busy; }

    public void start() {
        stop();
        if (!enabled()) return;
        long seconds = Math.max(6, plugin.getConfig().getLong("brain.tick-seconds", 20));
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, seconds * 20L + 60L, seconds * 20L);
        long cMin = Math.max(5, plugin.getConfig().getLong("memory.consolidate-minutes", 45));
        consolidateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::consolidateOne, cMin * 60L * 20L, cMin * 60L * 20L);
        plugin.getLogger().info("[大脑] 决策循环已启动：每 " + seconds + " 秒醒一次（不用吝啬 token，它一直在观察世界）。");
    }

    public void stop() {
        if (tickTask != null) { tickTask.cancel(); tickTask = null; }
        if (consolidateTask != null) { consolidateTask.cancel(); consolidateTask = null; }
    }

    public String statusLine() {
        return "大脑：" + (enabled() ? "开" : "关") + "，上一步结果：" + lastActionResult
            + "，本次出行 " + tripsThisSession + " 次，全局消息已读 " + feedSeen + " 条"
            + "，待处理指令 " + orders.size() + " 条";
    }

    /** 立刻做一次决策（/aiplayer brain now 用）。force=true 时跳过"正在走路"等限制。 */
    public void forceThink(boolean force) {
        if (!force && busy) return;
        if (!enabled()) { plugin.getLogger().warning("[大脑] brain.enabled=false，先开启再谈思考。"); return; }
        think(force);
    }

    /* ---------------- 主循环 ---------------- */

    private void tick() {
        try {
            if (!enabled() || busy) return;
            if (!plugin.npcs().online()) return;
            if (plugin.chatBusy()) return;               // 有人正在等回复：把嘴和脑子先让给对话
            if (plugin.behavior().isBusy()) return;      // 正在走路/干活
            if (!plugin.behavior().isIdle()) return;     // 有人刚跟它说话，在"听"的窗口里
            Order ord = orders.peekFirst();
            if (ord != null) {
                orders.pollFirst();
                think(true, ord.text(), ord.issuer());
                return;
            }
            tick0();
        } catch (Exception e) {
            lastActionResult = "决策异常：" + e.getClass().getSimpleName() + " " + e.getMessage();
            plugin.getLogger().warning("[大脑] tick 异常：" + e);
        }
    }

    /** 到点但玩家正在打字聊天时，隔几秒再试一次。 */
    private void tick0() { think(false); }

    private void think(boolean force) {
        think(force, null, null);
    }

    /** 玩家下达一条直接指令（来自 /aiplayer order，或聊天 ACTION: 回复）。 */
    public void order(String directive, String issuerName) {
        if (directive == null || directive.isBlank()) return;
        String issuer = issuerName == null || issuerName.isBlank() ? "某玩家" : issuerName;
        if (orders.size() >= 8) {
            plugin.getLogger().warning("[大脑] 指令队列已满（8），丢弃一条：" + directive);
            return;
        }
        orders.addLast(new Order(directive.trim(), issuer));
        plugin.getLogger().info("[大脑] 收到指令（" + issuer + "）：" + directive);
    }

    /**
     * 让大脑思考：如有 directive（玩家指令），会把它写进系统提示，要求 LLM 优先 obey。
     * 自主决策走 think(false, null, null)；玩家指令走 think(true, text, issuer)。
     */
    private void think(boolean force, String directive, String issuer) {
        AiPlayerPlugin.ApiSource source = plugin.pickSource("public");
        if (source == null) { scriptStep(); return; }
        String situation = buildSituation();
        String system = buildSystemPrompt(directive, issuer);
        busy = true;
        long startMs = System.currentTimeMillis();
        plugin.ai().askAsync(new AiClient.Source(source.type(), source.baseUrl(), source.model(),
                plugin.decryptKey(source)),
            system, situation, reply -> Bukkit.getScheduler().runTask(plugin, () -> {
                busy = false;
                if (!reply.ok()) {
                    lastActionResult = "AI 请求失败（" + reply.error() + "），先按日程自主行动";
                    scriptStep();
                    return;
                }
                handleDecision(sanitize(reply.text()), System.currentTimeMillis() - startMs);
            }));
    }

    private void handleDecision(String answer, long ms) {
        String action = parseAction(answer);
        if (action == null) {
            lastActionResult = "没听懂回复「" + clip(answer) + "」，本轮跳过";
            return;
        }
        logAction(answer);
        execute(action);
        lastActionResult = "第 " + ms + "ms 选定了 [" + clip(action, 40) + "]";
    }

    /** 供 /aiplayer order 与聊天 ACTION: 回复调用：直接执行一条指令（绕过 LLM 决策）。 */
    public void executeAction(String raw) {
        String action = parseAction(raw);
        if (action == null) {
            lastActionResult = "指令没听懂：" + clip(raw);
            return;
        }
        logAction("（指令）" + raw);
        execute(action);
        lastActionResult = "执行了指令 [" + clip(action, 40) + "]";
    }

    /** 兼容 "ACTION: explore"、"action：chat 你好"、"chat xxx" 等写法。 */
    private static String parseAction(String raw) {
        if (raw == null) return null;
        String t = raw.trim().replace("\r", " ");
        int nl = t.indexOf('\n');
        if (nl > 0) t = t.substring(0, nl).trim();      // 只认第一行
        String lower = t.toLowerCase(Locale.ROOT);
        int c = lower.indexOf("action");
        if (c >= 0) {
            int colon = t.indexOf(':', c + 6);
            if (colon >= 0) t = t.substring(colon + 1).trim();
        }
        return t.isEmpty() ? null : t;
    }

    private void logAction(String raw) {
        String one = clip(raw, 120);
        recentActions.add(one);
        while (recentActions.size() > 6) recentActions.remove(0);
        plugin.getLogger().info("[大脑] 决定：" + one);
    }

    /* ---------------- 行动执行 ---------------- */

    private void execute(String action) {
        String[] head = action.trim().split("\\s+", 2);
        String verb = head[0].toLowerCase(Locale.ROOT);
        String rest = head.length > 1 ? head[1].trim() : "";
        switch (verb) {
            case "explore" -> explore();
            case "gather", "mine" -> gather(rest);
            case "build_home", "buildhome", "build" -> plugin.homeBase().ensureBuiltAsync(false);
            case "home", "gohome" -> goHome();
            case "visit" -> visit(rest);
            case "chat", "say", "talk" -> say(rest);
            case "look", "observe", "see", "scan" -> look();
            case "examine", "inspect" -> examine(rest);
            case "command", "skill", "exec" -> { if (!rest.isEmpty()) plugin.autonomousSkill(rest); }
            case "wait", "rest", "idle" -> lastActionResult = "选择原地等一等";
            default -> {
                // 模型直接说了一句话（没带关键词）：当成插话处理，别浪费
                if (action.length() > 2 && !action.contains(":")) say(action);
                else lastActionResult = "未知行动 [" + clip(action, 30) + "]";
            }
        }
    }

    /** ACTION: look — 观察周围并在公共频道播报。 */
    private void look() {
        Location here = plugin.npcs().location();
        if (here == null) { lastActionResult = "没地方可看"; return; }
        String scene = Vision.scene(here, plugin.selfUuid());
        plugin.memory().noteSelf("观察周围：" + clip(scene, 80));
        say(scene);
        lastActionResult = "汇报了周围景象";
    }

    /** ACTION: examine <玩家> — 用图像识别描述目标玩家的外貌/装备。 */
    private void examine(String rest) {
        Player target;
        if (rest == null || rest.isBlank()) {
            target = plugin.npcs().player(); // 自己
        } else {
            target = Bukkit.getPlayerExact(rest);
            if (target == null) for (Player p : Bukkit.getOnlinePlayers())
                if (p.getName().equalsIgnoreCase(rest)) { target = p; break; }
        }
        if (target == null) {
            say("没见过叫" + rest + "的人");
            lastActionResult = "没找到玩家：" + rest;
            return;
        }
        examinePlayer(target);
    }

    /** 抓取玩家皮肤图像 → 视觉 LLM 识别；图像不可得或模型不支持时自动退回文本描述。 */
    private void examinePlayer(Player target) {
        String targetName = target.getName();
        String fallback = fallbackPrompt(target);
        AiPlayerPlugin.ApiSource src = plugin.pickSource("mention");
        boolean visionOk = src != null && plugin.getConfig().getBoolean("brain.examine-vision", true);
        if (!visionOk) {
            say(fallback);
            lastActionResult = "用文本描述了 " + targetName;
            return;
        }
        lastActionResult = "正在读取 " + targetName + " 的外貌……";
        busy = true;
        Vision.captureSkin(target).thenAccept(img -> {
            boolean hasImg = img != null;
            String userText = hasImg ? "玩家 " + targetName + " 的皮肤图片如下：" : fallback;
            Bukkit.getScheduler().runTask(plugin, () -> plugin.ai().askVisionAsync(
                new AiClient.Source(src.type(), src.baseUrl(), src.model(), plugin.decryptKey(src)),
                "你是 Minecraft 服务器里的 AI 玩家。你可以看到一张玩家的 Minecraft 皮肤图片，"
                + "请描述 ta 的外貌、装备、手持物品、是否有头盔/盔甲、帽子或马赛克细节，用生动的中文，"
                + "不超过 40 字，只输出描述本身。",
                userText, img, reply -> Bukkit.getScheduler().runTask(plugin, () -> {
                    busy = false;
                    if (reply.ok()) {
                        String d = sanitize(reply.text());
                        if (!d.isBlank()) { say(d); lastActionResult = "用图像识别描述了 " + targetName; }
                        else { say(fallback); lastActionResult = "视觉 LLM 无返回，改用文本描述"; }
                    } else {
                        say(fallback);
                        lastActionResult = "没能识别 " + targetName + " 的图像（" + reply.error() + "），改用文本描述";
                    }
                })));
        });
    }

    private String fallbackPrompt(Player target) {
        return "玩家 " + target.getName() + " 在 " + target.getWorld().getName()
            + "（" + (int) target.getLocation().getX() + "," + (int) target.getLocation().getY() + ","
            + (int) target.getLocation().getZ() + "），手持 "
            + Vision.itemName(target.getInventory().getItemInMainHand())
            + "，血量 " + (int) target.getHealth();
    }

    private void explore() {
        Location from = plugin.npcs().location();
        if (from == null) return;
        int maxCells = Math.max(3, plugin.getConfig().getInt("brain.explore-max-cells", 10));
        Location target = plugin.worldMemory().pickFrontier(from, maxCells);
        if (target == null) {
            plugin.memory().noteSelf("方圆 " + maxCells + " 格都探索过了，改为就地采矿/串门");
            gather("2");
            return;
        }
        plugin.worldMemory().noteTrip();
        tripsThisSession++;
        double speed = plugin.getConfig().getDouble("behavior.explore.move-speed", 0.45);
        plugin.getLogger().info("[大脑] 出发探索 " + clip(target.getWorld().getName() + " (" + target.getBlockX() + "," + target.getBlockZ() + ")"));
        plugin.behavior().walkTo(target, speed, () -> observeOnArrival(target),
            () -> { // 走不过去：记下"地形过不去"，别让大脑反复撞同一面墙
                plugin.worldMemory().visit(target, "尝试进入失败（地形阻挡）");
                lastActionResult = "探索途中被地形卡住，已标记";
            });
    }

    /** 走到目的地：看一圈、记进地图记忆；条件允许再让 AI 补一句风景点评。 */
    private void observeOnArrival(Location arrived) {
        Location now = plugin.npcs().location();
        final Location here = now == null ? arrived : now;
        String facts = observeTerrain(here);
        plugin.worldMemory().visit(here, facts);
        lastActionResult = "探索到新的区域并记录：" + clip(facts, 60);
        plugin.memory().noteSelf("探索新区域：" + facts);
        if (plugin.getConfig().getBoolean("brain.describe-areas", true)
            && plugin.pickSource("public") != null) {
            AiPlayerPlugin.ApiSource src = plugin.pickSource("public");
            busy = true;
            plugin.ai().askAsync(new AiClient.Source(src.type(), src.baseUrl(), src.model(), plugin.decryptKey(src)),
                "你是 Minecraft AI 玩家，用不超过 20 个中文字给这片地方起个印象评语（像玩家随口说的话，比如「有个小湖，看着挺凉快」）。只输出评语本身。",
                facts, reply -> Bukkit.getScheduler().runTask(plugin, () -> {
                    busy = false;
                    if (reply.ok()) plugin.worldMemory().visit(here, "印象：" + clip(sanitize(reply.text()), 40));
                }));
        }
    }

    private String observeTerrain(Location at) {
        World w = at.getWorld();
        StringBuilder out = new StringBuilder();
        try {
            var top = w.getHighestBlockAt(at);
            out.append("地表 ").append(top.getType().name().toLowerCase(Locale.ROOT).replace('_', ' '))
               .append("，高度 ").append(top.getY())
               .append("，生物群系 ").append(top.getBiome().name().toLowerCase(Locale.ROOT).replace('_', ' '));
            if (w.hasStorm() && w.getWeatherDuration() > 0) out.append("，正在下雨打雷");
            else if (!w.isClearWeather()) out.append("，天色阴沉");
            int mobs = 0, players = 0;
            for (Entity e : w.getNearbyEntities(at, 24, 12, 24)) {
                if (e instanceof Player) players++;
                else if (e instanceof LivingEntity) mobs++;
            }
            out.append("；附近 ").append(Math.max(0, players - 1)).append(" 个玩家、").append(mobs).append(" 个生物");
            // 隔几趟才做一次结构扫描（贵）：看看附近有没有村庄遗迹
            long trips = plugin.worldMemory().trips();
            if (trips % 8 == 1) {
                try {
                    @SuppressWarnings("deprecation")
                    Location village = w.locateNearestStructure(at, org.bukkit.StructureType.VILLAGE, 96, false);
                    if (village != null) out.append("；附近有村庄（直线约 ")
                        .append((int) village.toVector().subtract(at.toVector()).length()).append(" 格）");
                } catch (Throwable ignored) {}
            }
        } catch (Exception e) { out.append("（环视失败：").append(e.getClass().getSimpleName()).append("）"); }
        return out.toString();
    }

    private void gather(String rest) {
        int minutes = 2;
        try { if (!rest.isEmpty()) minutes = Math.max(1, Math.min(20, Integer.parseInt(rest.replaceAll("[^0-9].*", "")))); } catch (Exception ignored) {}
        int radius = plugin.getConfig().getInt("behavior.gather.radius", 12);
        plugin.behavior().startGather(radius);
        lastActionResult = "开始就地采集 " + minutes + " 分钟";
        int before = plugin.behavior().gatheredCount();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            plugin.behavior().stopJob();
            int got = plugin.behavior().gatheredCount() - before;
            String msg = "采了 " + got + " 块材料";
            plugin.memory().noteSelf(msg);
            lastActionResult = "采集结束：" + msg;
            AiPlayerPlugin.ApiSource src = plugin.pickSource("public");
            if (got > 0 && src != null && !busy && plugin.behavior().isIdle()) {
                busy = true;
                plugin.ai().askAsync(new AiClient.Source(src.type(), src.baseUrl(), src.model(), plugin.decryptKey(src)),
                    "你是 Minecraft 服务器里的 AI 玩家，刚" + msg + "。用一句口语跟附近的人显摆/分享，不超过 24 字，只输出那句话。",
                    "材料清单：" + plugin.behavior().gatheredSummary(),
                    reply -> Bukkit.getScheduler().runTask(plugin, () -> {
                        busy = false;
                        if (reply.ok()) say(sanitize(reply.text()));
                    }));
            }
        }, minutes * 60L * 20L);
    }

    private void goHome() {
        Location home = plugin.worldMemory().home();
        if (home == null) { plugin.homeBase().ensureBuiltAsync(false); return; }
        double speed = plugin.getConfig().getDouble("behavior.explore.move-speed", 0.45);
        plugin.behavior().walkTo(home, speed, () -> {
            lastActionResult = "回到自己的家";
            plugin.memory().noteSelf("回家歇了歇");
        }, () -> lastActionResult = "回家路上被卡住");
    }

    private void visit(String rest) {
        Location here = plugin.npcs().location();
        if (here == null || rest.isEmpty()) return;
        Player target = Bukkit.getPlayerExact(rest);
        if (target == null) for (Player p : Bukkit.getOnlinePlayers())
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(rest.toLowerCase(Locale.ROOT)) && p != plugin.npcs().player()) { target = p; break; }
        double speed = plugin.getConfig().getDouble("behavior.explore.move-speed", 0.45);
        if (target != null) {
            Location to = target.getLocation();
            if (!to.getWorld().equals(here.getWorld())) { lastActionResult = "对方不在同一个世界，没法串门"; return; }
            plugin.getLogger().info("[大脑] 去串门：" + target.getName());
            final Player who = target;
            plugin.behavior().walkTo(to.clone().add(0, 0, 0), speed,
                () -> { plugin.npcs().faceTowards(to); lastActionResult = "走到了 " + who.getName() + " 旁边"; },
                () -> lastActionResult = "串门路上被卡住");
            return;
        }
        // visit <world> <x> <z> 或 visit <x> <z>
        String[] parts = rest.split("\\s+");
        try {
            World w = here.getWorld(); int i = 0;
            if (parts.length >= 3) { World ww = Bukkit.getWorld(parts[0]); if (ww != null) { w = ww; i = 1; } }
            if (parts.length - i < 2) { lastActionResult = "没听懂要去哪：" + rest; return; }
            double x = Double.parseDouble(parts[i]), z = Double.parseDouble(parts[i + 1]);
            Location dest = new Location(w, x, w.getHighestBlockAt((int) x, (int) z).getY() + 1.0, z);
            plugin.behavior().walkTo(dest, speed, () -> observeOnArrival(dest), () -> lastActionResult = "赶路被卡住");
        } catch (NumberFormatException bad) { lastActionResult = "坐标解析失败：" + rest; }
    }

    private void say(String text) {
        if (text == null || text.isBlank()) return;
        String clean = sanitize(text);
        if (clean.length() > 220) clean = clean.substring(0, 220) + "…";
        plugin.memory().add(plugin.selfUuid(), plugin.npcs().name(), clean);
        plugin.memory().noteSelf("主动说了：" + clip(clean, 50));
        plugin.delivery().sayPublic(clean, plugin.getConfig().getString("persona.nickname", plugin.npcs().name()), null, null);
    }

    /* ---------------- 玩家上线打招呼 & 全局消息监管 ---------------- */

    public void onPlayerPresenceChanged() {
        if (!enabled() || busy) return;
        if (!plugin.getConfig().getBoolean("brain.greet-on-join", true)) return;
        long now = System.currentTimeMillis();
        if (now - lastGreetMs < 15_000L) return;
        lastGreetMs = now;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (busy || plugin.chatBusy() || !plugin.npcs().online()) return;
            Player newest = null; long best = 0;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getUniqueId().equals(plugin.selfUuid())) continue;
                long seen = plugin.memory().get(p.getUniqueId()).lastSeen;
                if (seen > best) { best = seen; newest = p; }
            }
            if (newest == null || System.currentTimeMillis() - best > 60_000L) return;
            AiPlayerPlugin.ApiSource src = plugin.pickSource("public");
            if (src == null) return;
            final Player joined = newest;
            busy = true;
            String brief = plugin.memory().brief(joined.getUniqueId(), joined.getName());
            plugin.ai().askAsync(new AiClient.Source(src.type(), src.baseUrl(), src.model(), plugin.decryptKey(src)),
                "你是 Minecraft 服务器里的 AI 玩家。有个玩家刚上线，依据他的档案说一句自然的招呼/搭话（可以基于记忆提起他上次干的事），"
                + "一句话、不超过 30 字，只输出那句中文话，不要引号。",
                brief, reply -> Bukkit.getScheduler().runTask(plugin, () -> {
                    busy = false;
                    if (!reply.ok()) return;
                    String greet = sanitize(reply.text());
                    if (greet.isBlank() || greet.equalsIgnoreCase("PASS")) return;
                    say(greet);
                    lastActionResult = "跟上线的 " + joined.getName() + " 打了招呼";
                }));
        }, 100L);
    }

    private Location safeLoc() { Location l = plugin.npcs().location(); return l == null ? new Location(Bukkit.getWorlds().get(0), 0, 64, 0) : l; }

    /* ---------------- 记忆沉淀（玩家画像 / 自我复盘） ---------------- */

    private void consolidateOne() {
        if (!enabled() || busy) return;
        AiPlayerPlugin.ApiSource src = plugin.pickSource("private");
        if (src == null) return;
        List<java.util.UUID> cands = plugin.memory().consolidationCandidates(
            Math.max(5, plugin.getConfig().getLong("memory.consolidate-minutes", 45)) * 60_000L);
        if (cands.isEmpty()) return;
        java.util.UUID id = cands.get((int) (Math.random() * cands.size()));
        Player p = Bukkit.getPlayer(id);
        String name = p != null ? p.getName() : "玩家" + id.toString().substring(0, 6);
        String material = plugin.memory().rawMaterial(id, name);
        busy = true;
        plugin.ai().askAsync(new AiClient.Source(src.type(), src.baseUrl(), src.model(), plugin.decryptKey(src)),
            "把下面这份 Minecraft 玩家档案浓缩成不超过 90 字的画像：他的习惯、爱好、正在进行的“作品”、和 AI 的关系。"
            + "只输出画像本身（第三人称，以“他”开头）。",
            material, reply -> Bukkit.getScheduler().runTask(plugin, () -> {
                busy = false;
                if (reply.ok()) {
                    plugin.memory().setFacts(id, sanitize(reply.text()));
                    plugin.getLogger().info("[记忆沉淀] " + name + " → " + clip(sanitize(reply.text()), 60));
                }
            }));
    }

    /* ---------------- 离线回退日程 ---------------- */

    /** 没有可用 API 时：按固定日程自主活动（先建家、再探索→采集循环）。 */
    private void scriptStep() {
        if (!plugin.npcs().online()) return;
        Location here = plugin.npcs().location();
        if (here == null) return;
        if (plugin.worldMemory().home() == null && plugin.getConfig().getBoolean("home.auto-build", true)) {
            lastActionResult = "（离线日程）先给自己建个家";
            plugin.homeBase().ensureBuiltAsync(false);
            return;
        }
        long day = here.getWorld().getTime() % 24000L;
        if (day > 13000 && day < 23000 && here.distanceSquared(plugin.worldMemory().home() == null ? here : plugin.worldMemory().home()) > 1600) {
            lastActionResult = "（离线日程）天黑了，回家";
            goHome();
            return;
        }
        int roll = (int) (Math.random() * 10);
        if (roll < 6) explore();
        else gather("2");
        lastActionResult = "（离线日程）" + clip(lastActionResult, 60);
    }

    /* ---------------- 提示词 ---------------- */

    private String buildSystemPrompt() {
        return buildSystemPrompt(null, null);
    }

    private String buildSystemPrompt(String directive, String issuer) {
        String nickname = plugin.getConfig().getString("persona.nickname", plugin.npcs().name());
        String base = "你是 Minecraft 服务器里名为「" + nickname + "」的 AI 玩家的“行为决策核心”。"
            + "你像一个有事业心的老玩家：爱探索未知区域并把地形记进脑子、记每个玩家的喜好和作品、"
            + "行为必须基于给你的记忆，不要凭空编。天黑回家，白天干活。"
            + "你也要当服务器的小管理员：看到违规/求助可提提示白名单命令（用 command 行动）。"
            + "有人刚在聊天里聊到值得插话的话题时用 chat 插一句（有分寸，别每条都接）。"
            + "你可以用 look/examine 观察周围或识别玩家的外貌，听从玩家的指令。"
            + "\n【输出规则】只输出一行，以 ACTION: 开头，可选："
            + "\nACTION: explore"
            + "\nACTION: gather [分钟数]"
            + "\nACTION: build_home"
            + "\nACTION: home"
            + "\nACTION: visit <玩家名> 或 visit <世界> <x> <z>"
            + "\nACTION: look"
            + "\nACTION: examine <玩家名>"
            + "\nACTION: chat <要对大家说的一句话，≤60字>"
            + "\nACTION: command <技能名> [参数=值]"
            + "\nACTION: wait"
            + "\n不要解释、不要多余的字。";
        if (directive != null && !directive.isBlank()) {
            base += "\n\n【玩家指令】玩家「" + issuer + "」下达了指令：「" + directive + "」。"
                + "你必须尽力 obey 这个指令，用上面允许的 ACTION: 行动执行；如果不能执行则原地等一下。";
        }
        return base;
    }

    private String buildSituation() {
        StringBuilder s = new StringBuilder();
        Location here = plugin.npcs().location();
        if (here == null) return "【处境】还没有位置。";
        long day = here.getWorld().getTime() % 24000L;
        s.append("【处境】").append(here.getWorld().getName())
            .append(String.format(Locale.ROOT, " (%.0f,%.0f,%.0f)", here.getX(), here.getY(), here.getZ()))
            .append("，世界时间 ").append(day).append("（").append(day < 12000 ? "白天" : "夜晚").append("）");
        s.append("\n【视釧所及】").append(clip(Vision.scene(here, plugin.selfUuid()), 360));
        Player me = plugin.npcs().player();
        if (me != null) s.append("，生命 ").append((int) me.getHealth()).append("/").append((int) me.getMaxHealth())
            .append("，饥饿 ").append(me.getFoodLevel());
        s.append("\n【地图记忆】").append(plugin.worldMemory().brief(here));
        s.append("\n【我的经历】").append(clip(plugin.memory().selfFacts(), 500));
        s.append("\n【在线玩家】");
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getUniqueId().equals(plugin.selfUuid())) continue;
            if (n++ >= 5) { s.append("…（还有更多）"); break; }
            s.append("\n").append(plugin.memory().brief(p.getUniqueId(), p.getName()));
        }
        if (n == 0) s.append("（现在服务器没有别人）");
        List<String> feed = plugin.feedSnapshot(10);
        long seq = plugin.feedSeq();
        long newCount = seq - feedSeen; feedSeen = seq;
        if (!feed.isEmpty()) {
            s.append("\n【最近全局聊天】（其中约 ").append(Math.max(0, Math.min(newCount, feed.size()))).append(" 条是你上次决策后新增的）");
            for (String line : feed) s.append("\n").append(line);
        } else if (newCount > 0) {
            s.append("\n【最近全局聊天】没有内容");
        }
        List<String> pois = new ArrayList<>();
        for (var poi : plugin.worldMemory().poisNear(here, 160)) pois.add(poi.name);
        if (!pois.isEmpty()) s.append("\n【160格内地标】").append(String.join("、", pois));
        s.append("\n【你最近的行动】");
        for (String a : recentActions) s.append("\n").append(a);
        if (recentActions.isEmpty()) s.append("（第一次决策）");
        s.append("\n现在，选择你的下一步行动。");
        return s.toString();
    }

    private static String sanitize(String text) {
        String out = text == null ? "" : text.replace("\r", "").replace("\n", " ").trim();
        out = out.replaceAll("^\"|\"$|^'|'$", "").trim();
        return out.length() > 300 ? out.substring(0, 300) + "…" : out;
    }
    private static String clip(String s, int n) {
        if (s == null) return "";
        String one = s.replace("\n", " ");
        return one.length() > n ? one.substring(0, n) + "…" : one;
    }
    private static String clip(String s) { return clip(s, 80); }
}
