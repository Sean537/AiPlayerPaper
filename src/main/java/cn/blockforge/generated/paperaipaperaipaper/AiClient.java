package cn.blockforge.generated.paperaipaperaipaper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** 多源 AI 客户端：支持 OpenAI / Anthropic / Gemini / DeepSeek / Ollama / OpenAI 兼容源。 */
public final class AiClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public record Reply(boolean ok, String text, String error) {}

    public Reply ask(AiConfig.Source source, String apiKey, String persona, String memory, String channel, String userMessage) {
        try {
            String system = persona + "\n【聊天频道】" + channel + "\n【你与该玩家最近的记忆】\n" + (memory.isBlank() ? "（暂无）" : memory)
                + "\n【技能调用规则】如需执行服务器命令，请只输出一行 SKILL: <技能名> [参数=值 ...]，且只能使用管理员登记的白名单技能；否则输出普通聊天文本。";
            HttpRequest request = switch (source.type) {
                case "anthropic" -> anthropic(source, apiKey, system, userMessage);
                case "gemini" -> gemini(source, apiKey, system, userMessage);
                case "ollama" -> ollama(source, system, userMessage);
                default -> openaiCompatible(source, apiKey, system, userMessage);
            };
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                return new Reply(false, null, "HTTP " + response.statusCode() + ": " + truncate(response.body()));
            }
            return parse(source.type, response.body());
        } catch (Exception e) {
            return new Reply(false, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private HttpRequest openaiCompatible(AiConfig.Source s, String key, String system, String user) {
        JsonObject body = new JsonObject();
        body.addProperty("model", s.model);
        JsonArray messages = new JsonArray();
        messages.add(role("system", system)); messages.add(role("user", user));
        body.add("messages", messages);
        body.addProperty("max_tokens", 300);
        return HttpRequest.newBuilder(URI.create(trimSlash(s.baseUrl) + "/chat/completions"))
            .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + key)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
    }

    private HttpRequest anthropic(AiConfig.Source s, String key, String system, String user) {
        JsonObject body = new JsonObject();
        body.addProperty("model", s.model);
        body.addProperty("max_tokens", 300);
        body.addProperty("system", system);
        JsonArray messages = new JsonArray(); messages.add(role("user", user));
        body.add("messages", messages);
        return HttpRequest.newBuilder(URI.create(trimSlash(s.baseUrl) + "/v1/messages"))
            .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
            .header("x-api-key", key).header("anthropic-version", "2023-06-01")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
    }

    private HttpRequest gemini(AiConfig.Source s, String key, String system, String user) {
        JsonObject body = new JsonObject();
        JsonArray contents = new JsonArray();
        JsonObject part = new JsonObject();
        JsonArray parts = new JsonArray();
        JsonObject text = new JsonObject(); text.addProperty("text", system + "\n玩家消息：" + user);
        parts.add(text); part.add("parts", parts); contents.add(part);
        body.add("contents", contents);
        return HttpRequest.newBuilder(URI.create(trimSlash(s.baseUrl) + "/v1beta/models/" + s.model + ":generateContent?key=" + key))
            .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
    }

    private HttpRequest ollama(AiConfig.Source s, String system, String user) {
        JsonObject body = new JsonObject();
        body.addProperty("model", s.model);
        JsonArray messages = new JsonArray();
        messages.add(role("system", system)); messages.add(role("user", user));
        body.add("messages", messages);
        body.addProperty("stream", false);
        return HttpRequest.newBuilder(URI.create(trimSlash(s.baseUrl) + "/api/chat"))
            .timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
    }

    private static JsonObject role(String r, String c) { JsonObject o = new JsonObject(); o.addProperty("role", r); o.addProperty("content", c); return o; }
    private static String trimSlash(String u) { return u.endsWith("/") ? u.substring(0, u.length() - 1) : u; }
    private static String truncate(String s) { return s == null ? "" : (s.length() > 200 ? s.substring(0, 200) + "..." : s); }

    private Reply parse(String type, String raw) {
        JsonObject o = com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
        try {
            if (type.equals("ollama")) return new Reply(true, o.getAsJsonObject("message").get("content").getAsString().trim(), null);
            if (type.equals("gemini")) return new Reply(true, o.getAsJsonArray("candidates").get(0).getAsJsonObject()
                .getAsJsonObject("content").getAsJsonArray("parts").get(0).getAsJsonObject().get("text").getAsString().trim(), null);
            if (type.equals("anthropic")) return new Reply(true, o.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString().trim(), null);
            return new Reply(true, o.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString().trim(), null);
        } catch (Exception e) {
            return new Reply(false, null, "无法解析响应: " + truncate(raw));
        }
    }
}
