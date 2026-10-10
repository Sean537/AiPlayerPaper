package cn.blockforge.aiplayer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.util.Vector;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * AI 的"眼睛"。
 *
 * <p>服务器端插件本身无法拿到客户端的渲染结果（真像素的截屏），所以这里提供两种层次的"视觉"：
 * <ol>
 *   <li><b>场景感知</b> —— 从世界数据还原 NPC 当前面朝方向的视釧所及：看着的方块、
 *       视釧内的生物/玩家、光照、时间、天气。这总是可得、纯服务器数据。</li>
 *   <li><b>图像识别</b> —— 对一个玩家抓取他的 Minecraft 皮肤 PNG 图片（真实像素，
 *       从 Mojang 会话服务器 / 玩家已加载纹理里要到），编码为 base64，交给支持多模态的
 *       视觉 LLM（gpt-4o / Claude / Gemini）去"认识"长相、装备、手持物品等。</li>
 * </ol>
 * 没拿到图像（比如对方从未登录过正版，或到 Mojang 网络超时）时，所有图像请求都会自动
 * 退回纯文本场景描述，从不阻塞大脑。
 */
public final class Vision {
    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();

    private Vision() {}

    /** 玩家皮肤图片抓取结果（真实像素）。base64 为 data URI 去掉前缀的纯 base64。 */
    public record ImageCapture(String base64, String mimeType, String sourceUrl) {}

    /** 把玩家的皮肤抓成一张图片交给视觉 LLM。异步完成，用 CompletableFuture 回调。 */
    public static CompletableFuture<ImageCapture> captureSkin(Player player) {
        if (player == null) return CompletableFuture.completedFuture(null);
        UUID uuid = player.getUniqueId();
        return CompletableFuture.supplyAsync(() -> {
            try {
                String value = skinTextureValue(uuid, player.getName());
                if (value == null || value.isBlank()) return null;
                String url = skinUrlFromValue(value);
                if (url == null || url.isBlank()) return null;
                byte[] png = download(url);
                if (png == null || png.length < 8) return null;
                return new ImageCapture(Base64.getEncoder().encodeToString(png), pngMimeType(png), url);
            } catch (Throwable t) {
                return null;
            }
        });
    }

    /** 当前玩家已在线但服务器没缓存皮肤纹理值时，走 Mojang 会话服务器按 UUID 反查。 */
    private static String skinTextureValue(UUID uuid, String fallbackName) {
        // 1) 优先走 Bukkit profile 缓存（online 模式下服务器可能已拉过）
        try {
            PlayerProfile profile = org.bukkit.Bukkit.createProfile(uuid);
            PlayerTextures tex = profile.getTextures();
            if (tex != null) {
                var skin = tex.getSkin();
                if (skin != null && skin.getValue() != null && !skin.getValue().isBlank())
                    return skin.getValue();
            }
        } catch (Throwable ignored) {}
        // 2) 回退到 Mojang sessionserver API（公开皮肤可用）
        return fetchTextureValueFromMojang(uuid);
    }

    /** 场景感知：NPC 眼前的世界是啥样的（纯文本，服务器数据）。selfUuid 用于跳过 AI 自己。 */
    public static String scene(Location eye, java.util.UUID selfUuid) {
        if (eye == null || eye.getWorld() == null) return "（无法感知周围）";
        World w = eye.getWorld();
        Vector forward = eye.getDirection();
        StringBuilder sb = new StringBuilder("【视釧所及】面朝 ")
                .append(horizontal(forward)).append("；");

        Location eye3 = eye.clone().add(0, 1.62, 0);
        Block target = rayBlock(eye3, forward, 24);
        if (target != null) {
            sb.append("凝视着 ").append(blockName(target))
                    .append("（").append((int) eye3.distance(target.getLocation())).append(" 格）；");
        } else sb.append("视线落空，远处见山水；");

        sb.append("周围有 ");
        List<String> ents = new ArrayList<>();
        Vector fwdY0 = forward.clone().setY(0).normalize();
        double dotThreshold = Math.cos(Math.toRadians(35)); // 70° 视釧
        int players = 0, hostiles = 0, neutrals = 0;
        for (Entity e : w.getNearbyEntities(eye, 32, 16, 32)) {
            if (e instanceof Player p && selfUuid != null && p.getUniqueId().equals(selfUuid)) continue;
            Vector to = e.getLocation().toVector().subtract(eye3.toVector());
            double horiz = Math.sqrt(to.getX() * to.getX() + to.getZ() * to.getZ());
            if (horiz < 0.5) continue;
            double dot = (to.getX() * fwdY0.getX() + to.getZ() * fwdY0.getZ()) / (horiz * fwdY0.length());
            if (dot < dotThreshold) continue;
            double dist = to.length();
            if (dist > 32) continue;
            String tag;
            if (e instanceof Player p) { players++; tag = "玩家 " + p.getName(); }
            else if (e instanceof Monster) { hostiles++; tag = "敌怪 " + typeName(e); }
            else if (e instanceof LivingEntity) { neutrals++; tag = typeName(e); }
            else { tag = typeName(e); }
            ents.add(tag + " (" + (int) dist + " 格)");
        }
        ents.sort(Comparator.comparingInt(s -> Integer.parseInt(s.substring(s.lastIndexOf('(') + 1, s.lastIndexOf(' ')))));
        for (int i = 0; i < Math.min(ents.size(), 6); i++) {
            sb.append(ents.get(i)).append("、");
        }
        if (ents.size() > 6) sb.append("…");
        if (sb.charAt(sb.length() - 1) == '、') sb.setLength(sb.length() - 1);
        sb.append("；玩家 ").append(players).append("，敌怪 ").append(hostiles)
                .append("，中立 ").append(neutrals).append("。");

        int light;
        try { light = eye.getBlock().getLightLevel(); } catch (Throwable ignored) { light = -1; }
        if (light >= 0) sb.append(" 光照 ").append(light).append("/15；");
        long time = w.getTime() % 24000L;
        if (time < 12000L) sb.append(" 白天");
        else if (time < 13000L) sb.append(" 黄昬");
        else sb.append(" 夜晚");
        if (w.hasStorm()) sb.append(w.isThundering() ? "，暴雨滂沱" : "，下个小雨");
        return sb.toString();
    }

    /** 背包主手物品名，用于"观察玩家"时的补充描述。 */
    public static String itemName(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return "空手";
        String n = stack.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return n;
    }

    public static String typeName(Entity e) {
        try { return e.getType().getName().toLowerCase(Locale.ROOT).replace('_', ' '); } catch (Throwable t) { return "神秘生物"; }
    }

    private static String blockName(Block b) {
        try {
            String t = b.getType().name();
            return t.isEmpty() || t.equalsIgnoreCase("AIR") ? "空气" : t.toLowerCase(Locale.ROOT).replace('_', ' ');
        } catch (Throwable t) { return "未知方块"; }
    }

    private static Block rayBlock(Location from, Vector dir, int max) {
        Location cur = from.clone();
        Vector step = dir.clone().normalize().multiply(0.5);
        for (int i = 0; i < max * 2; i++) {
            cur.add(step);
            try {
                Block b = cur.getBlock();
                if (!b.getType().isAir()) return b;
            } catch (Throwable ignored) { return null; }
        }
        return null;
    }

    private static String horizontal(Vector dir) {
        double dx = dir.getX(), dz = dir.getZ();
        if (Math.abs(dz) >= Math.abs(dx)) return dz < 0 ? "北" : "南";
        return dx < 0 ? "西" : "东";
    }

    private static String fetchTextureValueFromMojang(UUID uuid) {
        try {
            String u = "https://sessionserver.mojang.com/profile/" + uuid.toString().replace("-", "");
            String body = http.send(HttpRequest.newBuilder(URI.create(u))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "AiPlayerPaper")
                    .header("Accept", "application/json")
                    .GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            for (JsonElement el : o.getAsJsonArray("properties")) {
                JsonObject p = el.getAsJsonObject();
                if (p.get("name").getAsString().equals("textures")) return p.get("value").getAsString();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String skinUrlFromValue(String value) {
        try {
            String json = new String(java.util.Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            return o.getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").getAsString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] download(String url) {
        try {
            HttpResponse<byte[]> resp = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "AiPlayerPaper")
                    .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) return null;
            return resp.body();
        } catch (Throwable t) { return null; }
    }

    private static String pngMimeType(byte[] png) {
        return png.length >= 8 && png[0] == (byte) 0x89 && png[1] == 0x50 && png[2] == 0x4E
                && png[3] == 0x47 ? "image/png" : "application/octet-stream";
    }
}
