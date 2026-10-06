package cn.blockforge.generated.paperaipaperaipaper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AiConfig {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public final Map<String, Source> sources = new LinkedHashMap<>();
    public final Map<String, String> channelSources = new LinkedHashMap<>();
    public String nickname = "小助手";
    public String tone = "友善、简洁、会说明理由";
    public String traits = "耐心,守序,中文优先";
    public int replyCooldownSeconds = 8;
    public int replyChancePercent = 100;
    public int memoryTurns = 12;
    public boolean requireSkillConfirmation = true;
    public int temporaryBanMinutes = 30;
    public String avatarName = "小助手";
    public final java.util.List<String> bannedWords = new java.util.ArrayList<>();

    public static final class Source {
        public String type = "openai_compatible";
        public String baseUrl = "https://api.openai.com/v1";
        public String model = "gpt-4o-mini";
        public String encryptedKey = "";
        public boolean enabled = false;
        public Source() {}
        public Source(String type, String baseUrl, String model) { this.type = type; this.baseUrl = baseUrl; this.model = model; }
    }

    public static AiConfig load(Path dir) {
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve("config.json");
            if (!Files.exists(file)) {
                AiConfig c = defaults();
                c.save(dir);
                return c;
            }
            AiConfig c = GSON.fromJson(Files.readString(file), AiConfig.class);
            if (c == null) c = defaults();
            if (c.sources == null) c.sources.clear();
            if (c.channelSources == null) c.channelSources.clear();
            return c;
        } catch (IOException | RuntimeException e) {
            return defaults();
        }
    }

    public void save(Path dir) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("config.json"), GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException ignored) { }
    }

    public static AiConfig defaults() {
        AiConfig c = new AiConfig();
        c.sources.put("default", new Source("openai_compatible", "https://api.openai.com/v1", "gpt-4o-mini"));
        c.channelSources.put("public", "default");
        c.channelSources.put("private", "default");
        c.channelSources.put("mention", "default");
        return c;
    }

    public JsonObject summary() {
        JsonObject o = new JsonObject();
        o.addProperty("sources", sources.size());
        o.addProperty("nickname", nickname);
        o.addProperty("replyCooldownSeconds", replyCooldownSeconds);
        o.addProperty("replyChancePercent", replyChancePercent);
        o.addProperty("memoryTurns", memoryTurns);
        o.addProperty("keyStorage", "AES-GCM encrypted");
        return o;
    }
}
