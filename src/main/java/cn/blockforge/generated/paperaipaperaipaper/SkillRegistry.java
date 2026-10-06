package cn.blockforge.generated.paperaipaperaipaper;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** 白名单技能：管理员登记的命令模板，参数校验 + 冷却 + 权限等级 + 执行前确认。 */
public final class SkillRegistry {
    public record Param(String name, String regex, String sample) {
        public boolean matches(String value) { return value.matches(regex); }
    }
    public static final class Skill {
        public final String name;
        public final String commandTemplate;
        public final int permissionLevel;
        public final int cooldownSeconds;
        public final AtomicLong lastUse = new AtomicLong(0);
        public Skill(String name, String commandTemplate, int permissionLevel, int cooldownSeconds) {
            this.name = name; this.commandTemplate = commandTemplate;
            this.permissionLevel = permissionLevel; this.cooldownSeconds = cooldownSeconds;
        }
        public long remainingCooldown() {
            long elapsed = (System.currentTimeMillis() - lastUse.get()) / 1000;
            return elapsed < cooldownSeconds ? cooldownSeconds - elapsed : 0;
        }
    }
    public record Pending(String skillName, String finalCommand, String requester, long expiresAt) {}

    private final Path file;
    private final Map<String, Skill> skills = new LinkedHashMap<>();
    private Pending pending;

    public SkillRegistry(Path dir) {
        try { Files.createDirectories(dir); } catch (IOException ignored) {}
        this.file = dir.resolve("skills.json");
        load();
        if (skills.isEmpty()) seedDefaults();
    }

    private void seedDefaults() {
        add("weather_clear", "weather clear", 2, 60);
        add("who_online", "list", 0, 10);
        save();
    }

    public void add(String name, String template, int level, int cooldownSeconds) {
        skills.put(name, new Skill(name, template, level, cooldownSeconds));
        save();
    }

    public boolean remove(String name) { boolean r = skills.remove(name) != null; if (r) save(); return r; }

    public Skill get(String name) { return skills.get(name); }

    public Map<String, Skill> all() { return skills; }

    /** 校验 AI 提议的命令是否命中白名单模板；参数必须符合模板段的类型。 */
    public String resolveInvocation(String proposed) {
        String[] parts = proposed.trim().split("\\s+");
        if (parts.length == 0) return null;
        Skill s = skills.get(parts[0]);
        if (s == null) return null;
        String cmd = s.commandTemplate;
        for (int i = 1; i < parts.length; i++) {
            String kv = parts[i];
            int eq = kv.indexOf('=');
            if (eq <= 0) return null;
            String key = kv.substring(0, eq), value = kv.substring(eq + 1);
            if (!key.matches("[A-Za-z0-9_]+")) return null;
            if (!value.matches("[A-Za-z0-9_:.\\-]{1,48}")) return null; // 基础字符白名单，防止注入命令分隔符
            cmd = cmd.replace("{" + key + "}", value);
        }
        if (cmd.contains("{")) return null; // 仍有未填参数
        return cmd;
    }

    public synchronized Pending pending() { return pending; }

    public synchronized void setPending(Pending p) { pending = p; }

    public synchronized void clearPending() { pending = null; }

    private void load() {
        try {
            if (!Files.exists(file)) return;
            for (var el : JsonParser.parseString(Files.readString(file)).getAsJsonArray()) {
                JsonObject o = el.getAsJsonObject();
                skills.put(o.get("name").getAsString(),
                    new Skill(o.get("name").getAsString(), o.get("template").getAsString(),
                        o.get("level").getAsInt(), o.get("cooldown").getAsInt()));
            }
        } catch (Exception ignored) { }
    }

    private void save() {
        try {
            var arr = new com.google.gson.JsonArray();
            for (Skill s : skills.values()) {
                JsonObject o = new JsonObject();
                o.addProperty("name", s.name); o.addProperty("template", s.commandTemplate);
                o.addProperty("level", s.permissionLevel); o.addProperty("cooldown", s.cooldownSeconds);
                arr.add(o);
            }
            Files.writeString(file, AiConfig.GSON.toJson(arr), StandardCharsets.UTF_8);
        } catch (IOException ignored) { }
    }
}
