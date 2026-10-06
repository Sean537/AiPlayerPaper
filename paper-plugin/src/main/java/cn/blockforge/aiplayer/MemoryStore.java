package cn.blockforge.aiplayer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 记忆库。每个玩家一份档案：对话轮次、行为事件（上线/说话/建造/挖掘/死亡）、
 * 由 AI 定期"沉淀"出来的人物画像（habits/喜好/作品）。世界知识见 {@link WorldMemory}。
 */
public final class MemoryStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public record Turn(String speaker, String text, long time) {}
    public record Event(String text, long time) {}

    public static final class Profile {
        public Deque<Turn> turns = new ArrayDeque<>();
        public String summary = "";
        /** AI 沉淀的人物画像：性格、习惯、喜好、近期作品。*/
        public String facts = "";
        /** 行为事件流水（有容量上限）。*/
        public List<Event> events = new ArrayList<>();
        public long firstSeen;
        public long lastSeen;
        public long placedCount;
        public long brokenCount;
        public long deathCount;
        /** 最近一次建造活动的位置（world|x|y|z），AI 会去参观"作品"。*/
        public String lastBuildSite = "";
        public long lastConsolidatedAt;
    }

    private final Path dir;
    private final int maxTurns;
    private final java.util.Map<UUID, Profile> cache = new java.util.HashMap<>();
    private final Set<UUID> dirty = new LinkedHashSet<>();
    private volatile String aiSelfFacts = "";

    public MemoryStore(Path dir, int maxTurns) { this.dir = dir; this.maxTurns = Math.max(2, maxTurns); }

    public synchronized Profile get(UUID id) { return cache.computeIfAbsent(id, this::load); }

    public synchronized void add(UUID id, String speaker, String text) {
        Profile p = get(id);
        p.turns.addLast(new Turn(speaker, text, System.currentTimeMillis()));
        while (p.turns.size() > maxTurns * 2) p.turns.removeFirst();
        p.lastSeen = System.currentTimeMillis();
        if (p.firstSeen == 0) p.firstSeen = p.lastSeen;
        mark(id);
    }

    /** 记一条行为事件（说话、建造、挖掘、死亡、上线…）。高频事件由调用方自行节流。 */
    public synchronized void addEvent(UUID id, String text) {
        Profile p = get(id);
        p.events.add(new Event(text, System.currentTimeMillis()));
        while (p.events.size() > 80) p.events.remove(0);
        p.lastSeen = System.currentTimeMillis();
        if (p.firstSeen == 0) p.firstSeen = p.lastSeen;
        mark(id);
    }

    public synchronized String context(UUID id) {
        StringBuilder out = new StringBuilder();
        for (Turn t : get(id).turns) out.append(t.speaker()).append(": ").append(t.text()).append("\n");
        return out.toString();
    }

    /** 给提示词用的一页纸档案：画像 + 最近事件 + 最近对话。 */
    public synchronized String brief(UUID id, String name) {
        Profile p = get(id);
        StringBuilder out = new StringBuilder();
        out.append("〔").append(name).append("〕");
        long days = (System.currentTimeMillis() - p.firstSeen) / 86_400_000L;
        out.append("认识").append(days).append("天，");
        out.append(p.lastSeen > 0 ? "上次见面" + ago(p.lastSeen) : "还没见过").append("；");
        if (p.placedCount > 0 || p.brokenCount > 0)
            out.append("他放置过 ").append(p.placedCount).append(" 个方块、挖掉过 ").append(p.brokenCount).append(" 个方块；");
        if (!p.facts.isBlank()) out.append("\n  画像：").append(p.facts);
        List<Event> recent = p.events.size() > 6 ? p.events.subList(p.events.size() - 6, p.events.size()) : p.events;
        if (!recent.isEmpty()) {
            out.append("\n  最近动态：");
            for (Event e : recent) out.append("[" ).append(ago(e.time())).append("] ").append(e.text()).append("；");
        }
        List<Turn> turns = new ArrayList<>(p.turns);
        if (!turns.isEmpty()) {
            int from = Math.max(0, turns.size() - 4);
            out.append("\n  最近对话：");
            for (Turn t : turns.subList(from, turns.size())) out.append(t.speaker()).append("：").append(t.text()).append("｜");
        }
        return out.toString();
    }

    public synchronized boolean needsConsolidation(UUID id, long intervalMs) {
        Profile p = get(id);
        return System.currentTimeMillis() - p.lastConsolidatedAt > intervalMs
            && (p.events.size() >= 5 || p.turns.size() >= 6);
    }

    public synchronized String rawMaterial(UUID id, String name) {
        Profile p = get(id);
        StringBuilder out = new StringBuilder("玩家 ").append(name).append(" 的档案：\n");
        if (!p.facts.isBlank()) out.append("旧画像：").append(p.facts).append("\n");
        int evFrom = Math.max(0, p.events.size() - 20);
        for (Event e : p.events.subList(evFrom, p.events.size()))
            out.append("事件 ").append(e.text()).append("\n");
        List<Turn> turns = new ArrayList<>(p.turns);
        int tFrom = Math.max(0, turns.size() - 16);
        for (Turn t : turns.subList(tFrom, turns.size()))
            out.append(t.speaker()).append("：").append(t.text()).append("\n");
        return out.toString();
    }

    public synchronized void setFacts(UUID id, String facts) {
        Profile p = get(id);
        p.facts = facts == null ? "" : facts.trim();
        p.lastConsolidatedAt = System.currentTimeMillis();
        mark(id);
    }

    public synchronized long placed(UUID id) { return get(id).placedCount; }
    public synchronized long broken(UUID id) { return get(id).brokenCount; }

    public synchronized void bumpPlaced(UUID id, String siteKey) {
        Profile p = get(id); p.placedCount++; p.lastBuildSite = siteKey; mark(id);
    }
    public synchronized void bumpBroken(UUID id) {
        Profile p = get(id); p.brokenCount++; mark(id);
    }
    public synchronized void bumpDeath(UUID id) {
        Profile p = get(id); p.deathCount++; mark(id);
    }

    /** AI 自己的经历总结（也进记忆，让"它"知道自己做过什么）。 */
    public synchronized void noteSelf(String text) {
        aiSelfFacts = (aiSelfFacts.length() > 1200 ? aiSelfFacts.substring(aiSelfFacts.length() - 900) : aiSelfFacts)
            + (text == null || text.isBlank() ? "" : "\n- " + text);
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("self-memory.txt"), aiSelfFacts);
        } catch (IOException ignored) {}
    }
    public synchronized String selfFacts() { return aiSelfFacts; }

    /** 把攒了 4 个及以上未沉淀动态的玩家挑出来做记忆沉淀。 */
    public synchronized List<UUID> consolidationCandidates(long intervalMs) {
        List<UUID> out = new ArrayList<>();
        cache.forEach((id, p) -> { if (needsConsolidation(id, intervalMs)) out.add(id); });
        return out;
    }

    public synchronized void clear(UUID id) {
        cache.remove(id); dirty.remove(id);
        try { Files.deleteIfExists(file(id)); } catch (IOException ignored) {}
    }

    /** 主线程调用：把脏档案交给异步落盘。 */
    public synchronized void mark(UUID id) { dirty.add(id); }

    public synchronized java.util.Map<UUID, String> snapshotNames() {
        java.util.Map<UUID, String> out = new java.util.HashMap<>();
        cache.forEach((id, p) -> out.put(id, p.facts));
        return out;
    }

    /** 异步线程调用：写盘。 */
    public void flushDirty() {
        java.util.Map<UUID, Profile> toWrite = new java.util.HashMap<>();
        synchronized (this) {
            for (UUID id : dirty) { Profile p = cache.get(id); if (p != null) toWrite.put(id, p); }
            dirty.clear();
        }
        for (var e : toWrite.entrySet()) save(e.getKey(), e.getValue());
    }

    private Profile load(UUID id) {
        try {
            if (Files.exists(file(id))) {
                Profile p = GSON.fromJson(Files.readString(file(id)), Profile.class);
                if (p != null) {
                    if (p.turns == null) p.turns = new ArrayDeque<>();
                    if (p.events == null) p.events = new ArrayList<>();
                    if (p.facts == null) p.facts = "";
                    return p;
                }
            }
        } catch (Exception ignored) {}
        Profile fresh = new Profile();
        fresh.firstSeen = 0;
        return fresh;
    }

    private void save(UUID id, Profile p) {
        try { Files.createDirectories(dir); Files.writeString(file(id), GSON.toJson(p)); } catch (IOException ignored) {}
    }

    private Path file(UUID id) { return dir.resolve("memory-" + id + ".json"); }

    private static String ago(long time) {
        long s = (System.currentTimeMillis() - time) / 1000;
        if (s < 60) return s + "秒前";
        if (s < 3600) return (s / 60) + "分钟前";
        if (s < 86400) return (s / 3600) + "小时前";
        return (s / 86400) + "天前";
    }
}
