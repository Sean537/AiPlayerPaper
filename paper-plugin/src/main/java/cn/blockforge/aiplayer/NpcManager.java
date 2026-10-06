package cn.blockforge.aiplayer;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.scheduler.BukkitTask;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 玩家管理。进服方式三级（npc.join-method，默认 auto）：
 * 1) self-client：插件自开 TCP 连到 127.0.0.1 的服务器端口，按 1.21.x 网络协议
 *    完成握手/登录/配置，服务器把它当一个从本地进来的真实玩家；
 * 2) kernel：反射服务端内部类直接造 ServerPlayer（旧方案，版本签名漂移时不可用）；
 * 3) armorstand：盔甲架形态（最后兜底，无玩家身份）。
 * 协议自连是异步的（登录要在服务器网络线程处理），由主线程轮询认领 Player 对象。
 */
public final class NpcManager {
    private final AiPlayerPlugin plugin;
    private FakePlayerService service;
    private ProtocolClientPlayer client;

    private ArmorStand stand;
    private Player fake;
    private boolean protocolActive;      // 当前 fake 来自协议自连
    private volatile boolean pendingProtocol;
    private Player joinEventSeen;
    private BukkitTask joinPoller;
    private long joinDeadlineMs;
    private boolean loggedUnsupported;

    private String name;
    private String skin;
    private boolean slim;
    private UUID uuid;
    private Location home;
    private boolean fakeFailedOnce;

    /** key: 皮肤来源原文（player:xxx / url:xxx / base64:xxx），value: 解析出的纹理属性值 */
    private final Map<String, String> skinCache = new ConcurrentHashMap<>();

    public NpcManager(AiPlayerPlugin plugin) {
        this.plugin = plugin;
        this.service = new FakePlayerService(plugin);
        this.service.setSystemMessageListener((player, text) -> {
            JoinCommandRunner runner = plugin.autoCommands();
            if (runner != null) runner.onSystemMessage(player, text);
        });
        this.client = new ProtocolClientPlayer(plugin, new ProtocolClientPlayer.Listener() {
            @Override public void onPlaying(String n, UUID u) { /* 由主线程轮询认领 */ }
            @Override public void onFailed(String n, String reason) {
                Bukkit.getScheduler().runTask(plugin, () -> onProtocolFailed(reason));
            }
            @Override public void onServerMessage(String n, String text) {
                Bukkit.getScheduler().runTask(plugin, () -> forwardServerMessage(n, text));
            }
        });
    }

    /* ---------------- 对外状态 ---------------- */

    public Entity entity() { return fake != null ? fake : stand; }
    public LivingEntity living() { return fake != null ? fake : stand; }
    public Player player() { return fake; }
    public boolean isFakePlayer() { return fake != null; }
    public boolean isPendingProtocol() { return pendingProtocol; }
    public String name() { return name == null ? "AiPlayer" : name; }
    public UUID uuid() { return uuid; }
    public Location location() { Entity e = entity(); return e != null ? e.getLocation() : home; }
    public boolean online() {
        if (fake != null) return fake.isOnline() && !fake.isDead();
        return stand != null && !stand.isDead();
    }
    public String modeLabel() {
        if (fake != null) return protocolActive ? "真玩家·协议自连" : "真玩家·内核内部";
        return stand != null ? "盔甲架" : "离线";
    }

    /* ---------------- 配置 ---------------- */

    public void loadFromConfig() {
        name = sanitizeName(plugin.getConfig().getString("npc.name", "AiPlayer"));
        skin = plugin.getConfig().getString("npc.skin", "");
        slim = plugin.getConfig().getBoolean("npc.slim", false);
        String wname = plugin.getConfig().getString("npc.world", "world");
        World world = Bukkit.getWorld(wname);
        if (world == null && !Bukkit.getWorlds().isEmpty()) world = Bukkit.getWorlds().get(0);
        if (world != null) home = new Location(world,
            plugin.getConfig().getDouble("npc.x", 0.5), plugin.getConfig().getDouble("npc.y", 65),
            plugin.getConfig().getDouble("npc.z", 0.5),
            (float) plugin.getConfig().getDouble("npc.yaw", 0), (float) plugin.getConfig().getDouble("npc.pitch", 0));
        String su = plugin.getConfig().getString("npc.uuid", "");
        try { uuid = su.isBlank() ? null : UUID.fromString(su); } catch (Exception ignored) {}
        if (uuid == null) {
            uuid = UUID.nameUUIDFromBytes(("AiPlayerPaper:" + name).getBytes(StandardCharsets.UTF_8));
            plugin.getConfig().set("npc.uuid", uuid.toString()); plugin.saveConfig();
        }
    }

    private static String sanitizeName(String raw) {
        String v = raw == null ? "AiPlayer" : raw.trim().replace(' ', '_');
        if (v.isEmpty()) v = "AiPlayer";
        return v.length() > 16 ? v.substring(0, 16) : v;
    }

    /* ---------------- 进服主流程 ---------------- */

    public void spawn() {
        despawn();
        if (home == null) loadFromConfig();
        if (home == null) { plugin.getLogger().warning("没有可用的世界，AI 无法进入。"); return; }

        String method = plugin.getConfig().getString("npc.join-method", "auto").toLowerCase();
        String legacyType = plugin.getConfig().getString("npc.type", "player");
        if (method.equals("armorstand") || (method.equals("auto") && legacyType.equalsIgnoreCase("armorstand"))) {
            spawnStand();
            return;
        }
        if (method.equals("auto") || method.equals("self-client")) {
            if (startProtocolJoin()) return;
            if (method.equals("self-client")) { spawnStand(); return; }
        }
        if (method.equals("auto") || method.equals("kernel")) {
            spawnKernel();
            if (fake != null) return;
        }
        spawnStand();
    }

    /** 发起协议自连；成功发起返回 true（后续主线程轮询认领 Player）。 */
    private boolean startProtocolJoin() {
        if (!client.supported()) {
            if (!loggedUnsupported) {
                loggedUnsupported = true;
                plugin.getLogger().warning("本服无法启用协议自连（" + client.unsupportedReason() + "），改用其他进服方式。");
            }
            return false;
        }
        UUID offline = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name()).getBytes(StandardCharsets.UTF_8));
        plugin.getLogger().info("正在以 1.21.x 协议自连 127.0.0.1 → 让「" + name() + "」像真实客户端一样进服…");
        pendingProtocol = true;
        joinEventSeen = null;
        if (!client.start(name(), offline)) {
            pendingProtocol = false;
            plugin.getLogger().warning("协议自连无法启动：" + (client.failureReason() == null ? "未知原因" : client.failureReason()));
            return false; // 由 spawn() 按 join-method 继续回退
        }
        joinDeadlineMs = System.currentTimeMillis()
            + Math.max(5, plugin.getConfig().getLong("self-client.login-timeout-seconds", 25)) * 1000L + 8000L;
        if (joinPoller != null) joinPoller.cancel();
        joinPoller = Bukkit.getScheduler().runTaskTimer(plugin, this::pollProtocolJoin, 2L, 4L);
        return true;
    }

    private void pollProtocolJoin() {
        if (!pendingProtocol) return;
        ProtocolClientPlayer.State st = client.state();
        if (st == ProtocolClientPlayer.State.PLAYING) {
            Player p = Bukkit.getPlayerExact(name());
            UUID want = client.serverUuid();
            if (p != null && (want == null || p.getUniqueId().equals(want))) { adoptProtocol(p); return; }
            if (joinEventSeen != null && joinEventSeen.isOnline()) { adoptProtocol(joinEventSeen); return; }
        }
        if (System.currentTimeMillis() > joinDeadlineMs) {
            client.close();
            onProtocolFailed("等待进服超时：登录包已发出但服务器没有让「" + name() + "」进入（服务器已满/重名/插件拦截，看服务器日志）");
        }
    }

    private void adoptProtocol(Player p) {
        pendingProtocol = false;
        joinEventSeen = null;
        if (joinPoller != null) { joinPoller.cancel(); joinPoller = null; }
        protocolActive = true;
        fake = p;
        uuid = p.getUniqueId();
        plugin.getConfig().set("npc.uuid", uuid.toString());
        plugin.getConfig().set("npc.world", p.getWorld().getName());
        plugin.saveConfig();
        try {
            GameMode mode = GameMode.valueOf(plugin.getConfig().getString("npc.gamemode", "SURVIVAL").toUpperCase());
            p.setGameMode(mode);
        } catch (Exception ignored) {}
        if (home != null) p.teleport(home);
        p.setHealth(p.getMaxHealth());
        plugin.getLogger().info("AI 玩家「" + name() + "」已通过协议自连以真实玩家身份进入世界（会显示在 tab 列表）。");
        plugin.onAiJoin(p);
    }

    private void onProtocolFailed(String reason) {
        if (!pendingProtocol) return;
        pendingProtocol = false;
        if (joinPoller != null) { joinPoller.cancel(); joinPoller = null; }
        client.close();
        plugin.getLogger().warning("协议自连进服失败：" + reason);
        String method = plugin.getConfig().getString("npc.join-method", "auto").toLowerCase();
        if (method.equals("auto")) {
            spawnKernel();
            if (fake == null) spawnStand();
        } else {
            spawnStand();
        }
    }

    private void spawnKernel() {
        if (!service.supported()) return; // init 时已打日志说明原因
        GameMode mode = GameMode.SURVIVAL;
        try { mode = GameMode.valueOf(plugin.getConfig().getString("npc.gamemode", "SURVIVAL").toUpperCase()); } catch (Exception ignored) {}
        fake = service.join(name(), uuid, resolveTextureNow(), home.clone(), mode);
        if (fake != null) {
            protocolActive = false;
            plugin.getLogger().info("AI 玩家「" + name() + "」已以真玩家身份进入世界（内核内部方式）。");
        } else if (!fakeFailedOnce) {
            fakeFailedOnce = true;
            plugin.getLogger().warning("创建真玩家失败，本次回退为盔甲架形态。");
        }
    }

    private void spawnStand() {
        stand = home.getWorld().spawn(home, ArmorStand.class, s -> {
            s.setCustomName(name()); s.setCustomNameVisible(true); s.setBasePlate(false);
            s.setArms(true); s.setCanMove(false); s.setGravity(false);
        });
        applyStandSkin(skin);
    }

    /** 服务器发给 AI 客户端的文本：先过"耳朵"（私聊必答），再给进服命令模块（登录提示识别）。 */
    private void forwardServerMessage(String senderName, String text) {
        WhisperHook ears = plugin.whispers();
        if (ears != null) ears.handle(text);
        Player p = fake;
        if (p == null || !p.getName().equalsIgnoreCase(senderName)) return;
        JoinCommandRunner runner = plugin.autoCommands();
        if (runner != null) runner.onSystemMessage(p, text);
    }

    /**
     * PlayerJoinEvent 里调用：如果这正是我们等待中的协议自连分身，标记并接管（返回 true 表示"这是 AI 自己"）。
     * 认领完成（adoptProtocol）后才触发进服命令，保证只触发一次。
     */
    public boolean onSelfJoinSeen(Player p) {
        if (!pendingProtocol) return false;
        if (p.getName() == null || !p.getName().equalsIgnoreCase(name())) return false;
        joinEventSeen = p;
        return true;
    }

    /* ---------------- 皮肤（协议自连在预登录事件里注入） ---------------- */

    /** 预登录事件中要注入的纹理属性值；不处于协议自连等待期返回 null。 */
    public String loginTexturesValue() {
        if (!pendingProtocol && !protocolActive) return null;
        if (skin == null || skin.isBlank() || skin.equalsIgnoreCase("auto") || skin.equalsIgnoreCase("default")) return null;
        String source = skin.trim();
        try {
            if (source.startsWith("url:")) return FakePlayerService.textureValueFromUrl(source.substring(4), slim);
            if (source.startsWith("base64:")) return source.substring(7);
            if (source.startsWith("player:")) {
                String cached = skinCache.get(source);
                if (cached != null) return cached;
                String value = fetchTexturesBlocking(source.substring(7));
                if (value != null) skinCache.put(source, value);
                return value;
            }
            if (source.startsWith("http://") || source.startsWith("https://")) return FakePlayerService.textureValueFromUrl(source, slim);
        } catch (Exception ignored) {}
        return null;
    }

    /** 在异步线程里同步取正版玩家皮肤（预登录事件允许的阻塞窗口）。 */
    private String fetchTexturesBlocking(String playerName) {
        try {
            com.destroystokyo.paper.profile.PlayerProfile pp = Bukkit.createProfile(playerName);
            if (pp.complete(true)) {
                for (com.destroystokyo.paper.profile.ProfileProperty prop : pp.getProperties())
                    if (prop.getName().equals("textures")) return prop.getValue();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /* ---------------- 位置与重进 ---------------- */

    public void spawnAt(Location location) {
        saveHome(location);
        spawn();
    }

    private void saveHome(Location location) {
        home = location.clone();
        plugin.getConfig().set("npc.world", location.getWorld().getName());
        plugin.getConfig().set("npc.x", location.getX());
        plugin.getConfig().set("npc.y", location.getY());
        plugin.getConfig().set("npc.z", location.getZ());
        plugin.getConfig().set("npc.yaw", (double) location.getYaw());
        plugin.getConfig().set("npc.pitch", (double) location.getPitch());
        plugin.saveConfig();
    }

    /** 保持当前位置重新进服（用于改名、换肤、协议重连）。 */
    private void rejoinInPlace() {
        if (pendingProtocol) return; // 正在等待进服，不打断
        if (protocolActive && fake != null) {
            saveHome(fake.getLocation());
            despawn();
            spawn();
            return;
        }
        if (fake == null) { spawn(); return; }
        Location loc = fake.getLocation().clone();
        GameMode mode = fake.getGameMode();
        home = loc;
        despawn();
        fake = service.join(name(), uuid, resolveTextureNow(), loc, mode);
    }

    public void despawn() {
        pendingProtocol = false;
        joinEventSeen = null;
        if (joinPoller != null) { joinPoller.cancel(); joinPoller = null; }
        if (fake != null) {
            Player f = fake;
            fake = null;
            if (protocolActive) {
                protocolActive = false;
                if (f.isOnline()) try { f.kickPlayer("AI 玩家下线"); } catch (Throwable ignored) {}
                client.close();
            } else {
                service.disconnect(f, "AI 玩家下线");
            }
        } else if (protocolActive) {
            protocolActive = false;
            client.close();
        }
        if (stand != null && !stand.isDead()) stand.remove();
        stand = null;
    }

    /** 兼容旧调用名 */
    public void remove() { despawn(); }

    public void rename(String value) {
        name = sanitizeName(value);
        plugin.getConfig().set("npc.name", name); plugin.saveConfig();
        if (fake != null) rejoinInPlace();
        else if (pendingProtocol) { despawn(); spawn(); }
        else if (stand != null) stand.setCustomName(name);
    }

    public void setSkinSource(String value) {
        skin = value;
        plugin.getConfig().set("npc.skin", value); plugin.saveConfig();
        if (fake != null) rejoinInPlace();
        else if (stand != null) applyStandSkin(value);
    }

    /** 切换进服方式并立即生效。 */
    public void setJoinMethod(String method) {
        plugin.getConfig().set("npc.join-method", method);
        plugin.saveConfig();
        despawn();
        spawn();
    }

    public void move(Location location) {
        saveHome(location);
        Entity e = entity();
        if (e == null && !pendingProtocol) spawn();
        else if (e != null) e.teleport(location);
    }

    /** 单步移动（由 BehaviorEngine 的走动任务高频调用）。 */
    public void moveStep(Location location) {
        Entity e = entity();
        if (e != null) e.teleport(location);
    }

    /** 面朝目标（不位移），让 AI 转头看向方块/玩家。 */
    public void faceTowards(Location target) {
        Entity e = entity(); if (e == null || target == null) return;
        Location now = e.getLocation();
        double dx = target.getX() - now.getX(), dz = target.getZ() - now.getZ();
        double dy = (target.getY() + 1.0) - (now.getY() + 1.6);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        Location look = now.clone(); look.setYaw(yaw); look.setPitch(pitch);
        e.teleport(look);
    }

    /* ---------------- 旧：同步可得的纹理值（kernel 模式用） ---------------- */

    /** 同步可得就返回纹理值；player: 来源未缓存时触发异步解析并先返回 null（解析完成后会自动换肤重进）。 */
    private String resolveTextureNow() {
        if (skin == null || skin.isBlank() || skin.equalsIgnoreCase("auto") || skin.equalsIgnoreCase("default")) return null;
        String source = skin.trim();
        if (source.startsWith("url:")) {
            try { return FakePlayerService.textureValueFromUrl(source.substring(4), slim); } catch (Exception e) { return null; }
        }
        if (source.startsWith("base64:")) return source.substring(7);
        if (source.startsWith("player:")) {
            String cached = skinCache.get(source);
            if (cached != null) return cached;
            resolveAsync(source, source.substring(7));
            return null;
        }
        if (source.startsWith("http://") || source.startsWith("https://")) return FakePlayerService.textureValueFromUrl(source, slim);
        return null;
    }

    private void resolveAsync(String sourceKey, String playerName) {
        if (skinCache.containsKey(sourceKey)) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String value = fetchTexturesBlocking(playerName);
            final String result = value;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (result != null) skinCache.put(sourceKey, result);
                else plugin.getLogger().info("没能取到玩家「" + playerName + "」的皮肤，AI 将使用默认外观。");
                if (!protocolActive && fake != null && skin != null && skin.trim().equals(sourceKey)) rejoinInPlace();
            });
        });
    }

    /* ---------------- 盔甲架皮肤 ---------------- */

    private void applyStandSkin(String source) {
        if (stand == null || source == null || source.isBlank()) return;
        try {
            PlayerProfile profile;
            if (source.startsWith("player:")) {
                profile = Bukkit.createPlayerProfile(uuid, source.substring(7));
                String cached = skinCache.get(source);
                if (cached != null) {
                    try {
                        String decoded = new String(Base64.getDecoder().decode(cached), StandardCharsets.UTF_8);
                        int start = decoded.indexOf("http"); int end = decoded.indexOf('"', start);
                        if (start >= 0 && end > start) {
                            PlayerTextures textures = profile.getTextures();
                            textures.setSkin(new URL(decoded.substring(start, end)));
                            profile.setTextures(textures);
                        }
                    } catch (Exception ignored) {}
                } else resolveAsync(source, source.substring(7));
            } else {
                profile = Bukkit.createPlayerProfile(uuid, name());
                PlayerTextures textures = profile.getTextures();
                if (source.startsWith("url:")) textures.setSkin(new URL(source.substring(4)));
                else if (source.startsWith("base64:")) {
                    String decoded = new String(Base64.getDecoder().decode(source.substring(7)), StandardCharsets.UTF_8);
                    int start = decoded.indexOf("http"); int end = decoded.indexOf('"', start);
                    if (start >= 0 && end > start) textures.setSkin(new URL(decoded.substring(start, end)));
                }
                profile.setTextures(textures);
            }
            ItemStack head = new ItemStack(org.bukkit.Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwnerProfile(profile); head.setItemMeta(meta);
            stand.getEquipment().setHelmet(head);
        } catch (Exception e) { plugin.getLogger().warning("皮肤加载失败：" + e.getMessage()); }
    }
}
