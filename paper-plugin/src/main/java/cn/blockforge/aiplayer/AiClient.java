package cn.blockforge.aiplayer;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

public final class AiClient {
    public record Source(String type, String baseUrl, String model, String key) {}
    public record Reply(boolean ok, String text, String error) {}
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public void askAsync(Source source, String system, String user, Consumer<Reply> callback) {
        try {
            HttpRequest request = switch (source.type().toLowerCase()) {
                case "anthropic" -> anthropic(source, system, user);
                case "gemini" -> gemini(source, system, user);
                case "ollama" -> ollama(source, system, user);
                default -> openai(source, system, user);
            };
            http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> response.statusCode() / 100 == 2
                    ? parse(source.type(), response.body())
                    : new Reply(false, "", "HTTP " + response.statusCode() + ": " + truncate(response.body())))
                .exceptionally(error -> new Reply(false, "", error.getClass().getSimpleName() + ": " + error.getMessage()))
                .thenAccept(callback);
        } catch (Exception e) { callback.accept(new Reply(false, "", e.getMessage())); }
    }

    private HttpRequest openai(Source s, String system, String user) {
        JsonObject body = new JsonObject(); body.addProperty("model", s.model()); body.addProperty("max_tokens", 300);
        JsonArray messages = new JsonArray(); messages.add(message("system", system)); messages.add(message("user", user)); body.add("messages", messages);
        return request(trim(s.baseUrl()) + "/chat/completions", s.key(), body).header("Authorization", "Bearer " + s.key()).build();
    }
    private HttpRequest anthropic(Source s, String system, String user) {
        JsonObject body = new JsonObject(); body.addProperty("model", s.model()); body.addProperty("max_tokens", 300); body.addProperty("system", system);
        JsonArray messages = new JsonArray(); messages.add(message("user", user)); body.add("messages", messages);
        return request(trim(s.baseUrl()) + "/v1/messages", s.key(), body).header("x-api-key", s.key()).header("anthropic-version", "2023-06-01").build();
    }
    private HttpRequest gemini(Source s, String system, String user) {
        JsonObject body = new JsonObject(); JsonArray contents = new JsonArray(); JsonObject content = new JsonObject(); JsonArray parts = new JsonArray();
        JsonObject part = new JsonObject(); part.addProperty("text", system + "\n玩家消息：" + user); parts.add(part); content.add("parts", parts); contents.add(content); body.add("contents", contents);
        return request(trim(s.baseUrl()) + "/v1beta/models/" + s.model() + ":generateContent?key=" + s.key(), s.key(), body).build();
    }
    private HttpRequest ollama(Source s, String system, String user) {
        JsonObject body = new JsonObject(); body.addProperty("model", s.model()); body.addProperty("stream", false);
        JsonArray messages = new JsonArray(); messages.add(message("system", system)); messages.add(message("user", user)); body.add("messages", messages);
        return request(trim(s.baseUrl()) + "/api/chat", "", body).build();
    }
    private HttpRequest.Builder request(String url, String ignored, JsonObject body) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(45)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
    }
    private static JsonObject message(String role, String text) { JsonObject o = new JsonObject(); o.addProperty("role", role); o.addProperty("content", text); return o; }
    private Reply parse(String type, String raw) {
        try {
            JsonObject o = JsonParser.parseString(raw).getAsJsonObject();
            String text = switch (type.toLowerCase()) {
                case "ollama" -> o.getAsJsonObject("message").get("content").getAsString();
                case "gemini" -> o.getAsJsonArray("candidates").get(0).getAsJsonObject().getAsJsonObject("content").getAsJsonArray("parts").get(0).getAsJsonObject().get("text").getAsString();
                case "anthropic" -> o.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
                default -> o.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
            };
            return new Reply(true, text.trim(), "");
        } catch (Exception e) { return new Reply(false, "", "无法解析 AI 响应：" + truncate(raw)); }
    }
    private static String trim(String value) { return value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }
    private static String truncate(String value) { return value == null ? "" : value.length() > 180 ? value.substring(0, 180) + "..." : value; }
}
