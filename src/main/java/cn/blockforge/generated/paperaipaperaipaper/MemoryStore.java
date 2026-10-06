package cn.blockforge.generated.paperaipaperaipaper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.network.ServerPlayerEntity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MemoryStore {
    public record Turn(String speaker, String text, long time) {}
    public static final class Profile {
        public final Deque<Turn> recent = new ArrayDeque<>();
        public String summary = "";
        public final List<String> tags = new ArrayList<>();
        public final List<String> important = new ArrayList<>();
    }
    private final Path dir;
    private final int maxTurns;
    private final Map<UUID, Profile> profiles = new HashMap<>();

    public MemoryStore(Path dir, int maxTurns) { this.dir = dir; this.maxTurns = Math.max(2, maxTurns); }
    public void add(ServerPlayerEntity player, String speaker, String text) {
        Profile profile = profiles.computeIfAbsent(player.getUuid(), k -> new Profile());
        profile.recent.addLast(new Turn(speaker, text, Instant.now().toEpochMilli()));
        while (profile.recent.size() > maxTurns * 2) profile.recent.removeFirst();
        if (text.length() > 80 || text.contains("重要") || text.contains("记住")) profile.important.add(text);
        save(player.getUuid(), player.getName().getString());
    }
    public Profile get(UUID id) { return profiles.computeIfAbsent(id, k -> new Profile()); }
    public String context(UUID id) {
        StringBuilder b = new StringBuilder();
        for (Turn t : get(id).recent) b.append(t.speaker()).append(": ").append(t.text()).append("\n");
        return b.toString();
    }
    public void clear(UUID id) { profiles.remove(id); try { Files.deleteIfExists(file(id)); } catch (IOException ignored) {} }
    public Path export(UUID id, String name) {
        try { Files.createDirectories(dir.resolve("exports")); Path out = dir.resolve("exports").resolve("memory-" + name + "-" + id + ".json"); Files.writeString(out, AiConfig.GSON.toJson(toJson(get(id))), StandardCharsets.UTF_8); return out; }
        catch (IOException e) { return null; }
    }
    public void load(UUID id) {
        try {
            Path f = file(id); if (!Files.exists(f)) return;
            JsonObject o = com.google.gson.JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            Profile p = get(id); p.summary = o.has("summary") ? o.get("summary").getAsString() : "";
            if (o.has("turns")) for (var el : o.getAsJsonArray("turns")) { JsonObject t = el.getAsJsonObject(); p.recent.addLast(new Turn(t.get("speaker").getAsString(), t.get("text").getAsString(), t.get("time").getAsLong())); }
            while (p.recent.size() > maxTurns * 2) p.recent.removeFirst();
        } catch (Exception ignored) { }
    }
    private void save(UUID id, String ignoredName) { try { Files.createDirectories(dir); Files.writeString(file(id), AiConfig.GSON.toJson(toJson(get(id))), StandardCharsets.UTF_8); } catch (IOException ignored) {} }
    private Path file(UUID id) { return dir.resolve("memory-" + id + ".json"); }
    private JsonObject toJson(Profile p) {
        JsonObject o = new JsonObject(); o.addProperty("summary", p.summary); JsonArray a = new JsonArray();
        for (Turn t : p.recent) { JsonObject j = new JsonObject(); j.addProperty("speaker", t.speaker()); j.addProperty("text", t.text()); j.addProperty("time", t.time()); a.add(j); }
        o.add("turns", a); o.add("tags", AiConfig.GSON.toJsonTree(p.tags)); o.add("important", AiConfig.GSON.toJsonTree(p.important)); return o;
    }
}
