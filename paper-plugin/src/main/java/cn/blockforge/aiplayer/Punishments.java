package cn.blockforge.aiplayer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 处罚与审计存储：禁言、临时封禁、累计违规、审计日志，重启后依然生效。 */
public final class Punishments {
    private static final com.google.gson.Gson GSON = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
    private final Path dir;
    public final Map<UUID, Long> mutes = new HashMap<>();
    public final Map<UUID, Long> tempBans = new HashMap<>();
    public final Map<UUID, Integer> strikes = new HashMap<>();

    public Punishments(Path dir) {
        this.dir = dir;
        try { Files.createDirectories(dir); } catch (IOException ignored) {}
        load();
    }

    public boolean muted(UUID id) {
        Long until = mutes.get(id);
        if (until == null) return false;
        if (Instant.now().toEpochMilli() > until) { mutes.remove(id); save(); return false; }
        return true;
    }

    public boolean banned(UUID id) {
        Long until = tempBans.get(id);
        if (until == null) return false;
        if (Instant.now().toEpochMilli() > until) { tempBans.remove(id); save(); return false; }
        return true;
    }

    public void mute(UUID id, int minutes) { mutes.put(id, Instant.now().plusSeconds(minutes * 60L).toEpochMilli()); save(); }
    public void unmute(UUID id) { mutes.remove(id); save(); }
    public void ban(UUID id, int minutes) { tempBans.put(id, Instant.now().plusSeconds(minutes * 60L).toEpochMilli()); save(); }
    public void pardon(UUID id) { tempBans.remove(id); save(); }
    public int strike(UUID id) { return strikes.merge(id, 1, Integer::sum); }
    public void resetStrikes(UUID id) { strikes.remove(id); save(); }

    public void audit(String line) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("audit.log"), Instant.now() + " " + line + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }

    private void save() {
        try {
            JsonObject o = new JsonObject();
            o.add("mutes", toArray(mutes));
            o.add("bans", toArray(tempBans));
            JsonObject st = new JsonObject();
            strikes.forEach((k, v) -> st.addProperty(k.toString(), v));
            o.add("strikes", st);
            Files.writeString(dir.resolve("punishments.json"), GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    private static JsonArray toArray(Map<UUID, Long> map) {
        JsonArray arr = new JsonArray();
        map.forEach((k, v) -> { JsonObject p = new JsonObject(); p.addProperty("id", k.toString()); p.addProperty("until", v); arr.add(p); });
        return arr;
    }

    private void load() {
        try {
            Path file = dir.resolve("punishments.json");
            if (!Files.exists(file)) return;
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (o.has("mutes")) for (var el : o.getAsJsonArray("mutes")) { JsonObject j = el.getAsJsonObject(); mutes.put(UUID.fromString(j.get("id").getAsString()), j.get("until").getAsLong()); }
            if (o.has("bans")) for (var el : o.getAsJsonArray("bans")) { JsonObject j = el.getAsJsonObject(); tempBans.put(UUID.fromString(j.get("id").getAsString()), j.get("until").getAsLong()); }
            if (o.has("strikes")) for (var entry : o.getAsJsonObject("strikes").entrySet()) strikes.put(UUID.fromString(entry.getKey()), entry.getValue().getAsInt());
        } catch (Exception ignored) {}
    }
}
