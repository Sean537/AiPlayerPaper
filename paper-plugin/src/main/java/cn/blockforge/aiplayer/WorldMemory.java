package cn.blockforge.aiplayer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Location;
import org.bukkit.World;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 世界记忆：把地图切成网格，AI 每探索到一个格子就记下"这片地方长什么样"；
 * 地标（村庄、要塞、玩家的家、自己的家、死亡点）单独存 POI。
 * 决策引擎靠"哪些格子还没去过"来决定往哪边走——这就是"有智能地探索"。
 */
public final class WorldMemory {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final class Cell {
        public String key = "";      // world|cx|cz
        public String note = "";     // 印象（生物群系/地形/资源/见闻）
        public long seenAt;
    }
    public static final class Poi {
        public String name = "";     // 村庄 / 小红的房子 / 我的家 / 死亡点
        public String note = "";
        public String world = "";
        public double x, y, z;
        public long updatedAt;
        public Poi() {}
        public Poi(String name, String note, Location l) {
            this.name = name; this.note = note;
            this.world = l.getWorld().getName(); this.x = l.getX(); this.y = l.getY(); this.z = l.getZ();
            this.updatedAt = System.currentTimeMillis();
        }
        public Location location() {
            World w = org.bukkit.Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z);
        }
    }

    private final Path file;
    private int cellSize = 32;
    private final Map<String, Cell> cells = new LinkedHashMap<>();
    private final List<Poi> pois = new ArrayList<>();
    private String home = "";   // world|x|y|z，家门口
    private String bed = "";    // world|x|y|z，床头出生点
    private long exploreTrips;

    public WorldMemory(Path dataDir) {
        try { Files.createDirectories(dataDir); } catch (IOException ignored) {}
        this.file = dataDir.resolve("world-memory.json");
        load();
    }

    public synchronized void setCellSize(int v) { cellSize = Math.max(8, Math.min(256, v)); }
    public synchronized int cellSize() { return cellSize; }

    private String key(Location l) {
        return l.getWorld().getName() + "|" + Math.floorDiv(l.getBlockX(), cellSize) + "|" + Math.floorDiv(l.getBlockZ(), cellSize);
    }

    /** 到过一个格子：更新它的印象。note 为空则只标记"去过"。 */
    public synchronized void visit(Location l, String note) {
        String k = key(l);
        Cell c = cells.computeIfAbsent(k, id -> { Cell n = new Cell(); n.key = id; return n; });
        c.seenAt = System.currentTimeMillis();
        if (note != null && !note.isBlank()) {
            String slim = note.length() > 120 ? note.substring(0, 120) + "…" : note;
            if (!c.note.contains(slim)) c.note = c.note.isBlank() ? slim : c.note + "；" + slim;
            if (c.note.length() > 400) c.note = c.note.substring(c.note.length() - 380);
        }
        saveSoon();
    }

    public synchronized boolean visited(Location l) { return cells.containsKey(key(l)); }

    /** 挑一个"还没去过、离当前最近、但也不贴脸"的格子中心作为探索目标；全探索完返回 null。 */
    public synchronized Location pickFrontier(Location from, int maxCells) {
        World w = from.getWorld();
        int baseCx = Math.floorDiv(from.getBlockX(), cellSize), baseCz = Math.floorDiv(from.getBlockZ(), cellSize);
        record Cand(double dist, int cx, int cz) {}
        List<Cand> cands = new ArrayList<>();
        for (int dx = -maxCells; dx <= maxCells; dx++) for (int dz = -maxCells; dz <= maxCells; dz++) {
            int r2 = dx * dx + dz * dz;
            if (r2 == 0 || r2 > (double) maxCells * maxCells) continue;
            int cx = baseCx + dx, cz = baseCz + dz;
            if (cells.containsKey(w.getName() + "|" + cx + "|" + cz)) continue;
            cands.add(new Cand(r2, cx, cz));
        }
        if (cands.isEmpty()) return null;
        cands.sort(Comparator.comparingDouble(c -> c.dist()));
        Cand pick = cands.get(Math.min(cands.size() - 1, (int) (Math.random() * Math.min(4, cands.size())))); // 前几名里随机，避免走直线
        double cxCenter = (pick.cx() + 0.5) * cellSize, czCenter = (pick.cz() + 0.5) * cellSize;
        return new Location(w, cxCenter, surfaceSafeY(w, cxCenter, czCenter, from.getY()), czCenter);
    }

    /** 目标列的地表高度；区块没加载就先用起点高度，走到位后再校正。 */
    private static double surfaceSafeY(World w, double x, double z, double fallbackY) {
        int cx = (int) Math.floor(x) >> 4, cz = (int) Math.floor(z) >> 4;
        if (!w.isChunkLoaded(cx, cz)) return fallbackY;
        return w.getHighestBlockAt((int) Math.floor(x), (int) Math.floor(z)).getY() + 1.0;
    }

    /** 登记/更新地标（同名即覆盖）。 */
    public synchronized void putPoi(String name, String note, Location l) {
        for (Poi p : pois) {
            if (p.name.equalsIgnoreCase(name)) {
                p.note = note; p.world = l.getWorld().getName(); p.x = l.getX(); p.y = l.getY(); p.z = l.getZ();
                p.updatedAt = System.currentTimeMillis(); saveSoon(); return;
            }
        }
        pois.add(new Poi(name, note, l));
        saveSoon();
    }

    public synchronized List<Poi> poisNear(Location from, double radius) {
        List<Poi> out = new ArrayList<>();
        for (Poi p : pois) {
            Location l = p.location();
            if (l == null || !l.getWorld().equals(from.getWorld())) continue;
            if (Math.hypot(l.getX() - from.getX(), l.getZ() - from.getZ()) <= radius) out.add(p);
        }
        return out;
    }

    public synchronized void setHome(Location l) {
        home = l == null ? "" : l.getWorld().getName() + "|" + l.getX() + "|" + l.getY() + "|" + l.getZ();
        if (l != null) putPoi("我的家", "我亲手建的家，有床、有门、有工作台", l);
        saveSoon();
    }
    public synchronized Location home() { return parseLocation(home); }

    public synchronized void setBed(Location l) {
        bed = encodeLocation(l);
        saveSoon();
    }

    public synchronized Location bed() { return parseLocation(bed); }

    private static String encodeLocation(Location l) {
        return l == null || l.getWorld() == null ? "" : l.getWorld().getName() + "|" + l.getX() + "|" + l.getY() + "|" + l.getZ();
    }

    private static Location parseLocation(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        try {
            String[] parts = encoded.split("\\|");
            World w = org.bukkit.Bukkit.getWorld(parts[0]);
            return w == null ? null : new Location(w, Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3]));
        } catch (Exception ignored) { return null; }
    }

    public synchronized void noteTrip() { exploreTrips++; if (exploreTrips % 10 == 0) saveSoon(); }
    public synchronized long trips() { return exploreTrips; }

    /** 给提示词用的地图简报。 */
    public synchronized String brief(Location current) {
        StringBuilder out = new StringBuilder();
        out.append("已探索 ").append(cells.size()).append(" 个 ").append(cellSize).append(" 格区域（出行 ").append(exploreTrips).append(" 次）。");
        List<Map.Entry<String, Cell>> recent = new ArrayList<>(cells.entrySet());
        int from = Math.max(0, recent.size() - 6);
        out.append("\n  去过的地方（时间近→远）：");
        for (Map.Entry<String, Cell> e : recent.subList(from, recent.size())) {
            Cell c = e.getValue();
            out.append("\n   · ").append(c.key.replace("|", " 格 ")).append("：").append(c.note.isBlank() ? "到过，没细看" : c.note);
        }
        if (!pois.isEmpty()) {
            out.append("\n  地标：");
            for (Poi p : pois) out.append("\n   · ").append(p.name).append("（").append(p.world)
                .append(String.format(Locale.ROOT, " %.0f,%.0f,%.0f", p.x, p.y, p.z)).append("）")
                .append(p.note.isBlank() ? "" : "：" + p.note);
        }
        Location h = home();
        if (h != null) out.append("\n  我的家在 ").append(h.getWorld().getName())
            .append(String.format(Locale.ROOT, " (%.0f, %.0f, %.0f)", h.getX(), h.getY(), h.getZ())).append("。");
        return out.toString();
    }

    public synchronized String cellNote(Location l) {
        Cell c = cells.get(key(l));
        return c == null ? "" : c.note;
    }

    private volatile boolean saveScheduled;
    private void saveSoon() {
        if (saveScheduled) return;
        saveScheduled = true;
        org.bukkit.Bukkit.getScheduler().runTaskLater(org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(WorldMemory.class), () -> {
            saveScheduled = false;
            flush();
        }, 100L);
    }

    public synchronized void flush() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("cellSize", cellSize);
            root.addProperty("home", home);
            root.addProperty("bed", bed);
            root.addProperty("trips", exploreTrips);
            StringBuilder cellsJson = new StringBuilder();
            for (Cell c : cells.values()) {
                JsonObject o = new JsonObject();
                o.addProperty("key", c.key); o.addProperty("note", c.note); o.addProperty("seenAt", c.seenAt);
                if (cellsJson.length() > 0) cellsJson.append(',');
                cellsJson.append(o);
            }
            root.add("cells", JsonParser.parseString("[" + cellsJson + "]"));
            StringBuilder poiJson = new StringBuilder();
            for (Poi p : pois) {
                JsonObject o = (JsonObject) GSON.toJsonTree(p);
                if (poiJson.length() > 0) poiJson.append(',');
                poiJson.append(o);
            }
            root.add("pois", JsonParser.parseString("[" + poiJson + "]"));
            Files.writeString(file, GSON.toJson(root));
        } catch (IOException ignored) {}
    }

    private void load() {
        try {
            if (!Files.exists(file)) return;
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (root.has("cellSize")) cellSize = root.get("cellSize").getAsInt();
            if (root.has("home")) home = root.get("home").getAsString();
            if (root.has("bed")) bed = root.get("bed").getAsString();
            if (root.has("trips")) exploreTrips = root.get("trips").getAsLong();
            if (root.has("cells")) for (var el : root.getAsJsonArray("cells")) {
                Cell c = GSON.fromJson(el, Cell.class);
                if (c != null && c.key != null) cells.put(c.key, c);
            }
            if (root.has("pois")) for (var el : root.getAsJsonArray("pois")) {
                Poi p = GSON.fromJson(el, Poi.class);
                if (p != null && p.name != null) pois.add(p);
            }
        } catch (Exception ignored) { cells.clear(); pois.clear(); }
    }
}
