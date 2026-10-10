package cn.blockforge.aiplayer;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
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

    /* ---------------- 图像识别（多模态） ---------------- */

    /**
     * 发一张图片去视觉 LLM 识别。image 为空或没拿到图像时，自动退回纯文本提问
     * （把场景描述当作用户消息），这样大脑永远能拿到"视觉输入"，即使没网或对方没皮肤。
     */
    public void askVisionAsync(Source source, String system, String user, Vision.ImageCapture image, Consumer<Reply> callback) {
        if (image == null || image.base64() == null || image.base64().isBlank()) {
            askAsync(source, system, user, callback);
            return;
        }
        try {
            HttpRequest request = switch (source.type().toLowerCase(Locale.ROOT)) {
                case "anthropic" -> anthropicVision(source, system, user, image);
                case "gemini" -> geminiVision(source, system, user, image);
                default -> openaiVision(source, system, user, image);
            };
            http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> response.statusCode() / 100 == 2
                    ? parse(source.type(), response.body())
                    : new Reply(false, "", "HTTP " + response.statusCode() + ": " + truncate(response.body())))
                .exceptionally(error -> new Reply(false, "", error.getClass().getSimpleName() + ": " + error.getMessage()))
                .thenAccept(callback);
        } catch (Exception e) { callback.accept(new Reply(false, "", e.getMessage())); }
    }

    private HttpRequest openaiVision(Source s, String system, String user, Vision.ImageCapture img) {
        JsonObject body = new JsonObject(); body.addProperty("model", s.model()); body.addProperty("max_tokens", 300);
        JsonArray messages = new JsonArray(); messages.add(message("system", system));
        JsonObject userMsg = new JsonObject(); userMsg.addProperty("role", "user");
        JsonArray content = new JsonArray();
        JsonObject textPart = new JsonObject(); textPart.addProperty("type", "text"); textPart.addProperty("text", user); content.add(textPart);
        JsonObject imgPart = new JsonObject(); imgPart.addProperty("type", "image_url");
        JsonObject imgUrl = new JsonObject(); imgUrl.addProperty("url", "data:" + img.mimeType() + ";base64," + img.base64()); imgPart.add("image_url", imgUrl); content.add(imgPart);
        userMsg.add("content", content); messages.add(userMsg); body.add("messages", messages);
        return request(trim(s.baseUrl()) + "/chat/completions", s.key(), body).header("Authorization", "Bearer " + s.key()).build();
    }
    private HttpRequest anthropicVision(Source s, String system, String user, Vision.ImageCapture img) {
        JsonObject body = new JsonObject(); body.addProperty("model", s.model()); body.addProperty("max_tokens", 300); body.addProperty("system", system);
        JsonArray messages = new JsonArray();
        JsonObject userMsg = new JsonObject(); userMsg.addProperty("role", "user");
        JsonArray content = new JsonArray();
        JsonObject textPart = new JsonObject(); textPart.addProperty("type", "text"); textPart.addProperty("text", user); content.add(textPart);
        JsonObject imgPart = new JsonObject(); imgPart.addProperty("type", "image");
        JsonObject source = new JsonObject(); source.addProperty("type", "base64"); source.addProperty("media_type", img.mimeType()); source.addProperty("data", img.base64());
        imgPart.add("source", source); content.add(imgPart);
        userMsg.add("content", content); messages.add(userMsg); body.add("messages", messages);
        return request(trim(s.baseUrl()) + "/v1/messages", s.key(), body).header("x-api-key", s.key()).header("anthropic-version", "2023-06-01").build();
    }
    private HttpRequest geminiVision(Source s, String system, String user, Vision.ImageCapture img) {
        JsonObject body = new JsonObject(); JsonArray contents = new JsonArray(); JsonObject content = new JsonObject(); JsonArray parts = new JsonArray();
        JsonObject textPart = new JsonObject(); textPart.addProperty("text", system + "\n玩家消息：" + user); parts.add(textPart);
        JsonObject imgPart = new JsonObject(); JsonObject inline = new JsonObject();
        inline.addProperty("mime_type", img.mimeType()); inline.addProperty("data", img.base64()); imgPart.add("inline_data", inline); parts.add(imgPart);
        content.add("parts", parts); contents.add(content); body.add("contents", contents);
        return request(trim(s.baseUrl()) + "/v1beta/models/" + s.model() + ":generateContent?key=" + s.key(), s.key(), body).build();
    }
}
