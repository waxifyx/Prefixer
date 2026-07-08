package xyz.waxify.prefixer.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import xyz.waxify.prefixer.Prefixer;
import xyz.waxify.prefixer.model.ImageTagChunk;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

public final class MineSkinService {
    private static final Gson GSON = new Gson();

    private final Prefixer plugin;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    public MineSkinService(Prefixer plugin) {
        this.plugin = plugin;
    }

    public boolean isConfigured() {
        return !plugin.getConfig().getStringList("mineskin.api-keys").isEmpty();
    }

    public ImageTagChunk uploadChunk(byte[] pngBytes) throws IOException, InterruptedException {
        String apiKey = plugin.getConfig().getStringList("mineskin.api-keys").getFirst();
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("url", "data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes));
        requestBody.addProperty("visibility", "unlisted");

        HttpRequest submit = HttpRequest.newBuilder(URI.create("https://api.mineskin.org/v2/queue"))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(requestBody)))
            .build();

        HttpResponse<String> submitResponse = httpClient.send(submit, HttpResponse.BodyHandlers.ofString());
        if (submitResponse.statusCode() >= 400) {
            throw new IOException("MineSkin queue submit failed with HTTP " + submitResponse.statusCode());
        }

        String jobId = parseJobId(submitResponse.body());
        if (jobId == null || jobId.isBlank()) {
            ImageTagChunk immediate = parseChunk(submitResponse.body());
            if (immediate != null) {
                return immediate;
            }
            throw new IOException("MineSkin queue submit returned no job id");
        }

        for (int i = 0; i < 40; i++) {
            Thread.sleep(1500L);
            HttpRequest poll = HttpRequest.newBuilder(URI.create("https://api.mineskin.org/v2/queue/" + jobId))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
            HttpResponse<String> pollResponse = httpClient.send(poll, HttpResponse.BodyHandlers.ofString());
            if (pollResponse.statusCode() >= 400) {
                throw new IOException("MineSkin queue poll failed with HTTP " + pollResponse.statusCode());
            }
            ImageTagChunk parsed = parseChunk(pollResponse.body());
            if (parsed != null) {
                return parsed;
            }
            String status = parseStatus(pollResponse.body());
            if (status != null && (status.contains("failed") || status.contains("error"))) {
                throw new IOException("MineSkin generation failed");
            }
        }
        throw new IOException("MineSkin generation timed out");
    }

    private String parseJobId(String body) {
        JsonObject json = GSON.fromJson(body, JsonObject.class);
        if (json == null) {
            return null;
        }
        if (json.has("jobId")) {
            return json.get("jobId").getAsString();
        }
        if (json.has("job") && json.get("job").isJsonObject() && json.getAsJsonObject("job").has("id")) {
            return json.getAsJsonObject("job").get("id").getAsString();
        }
        if (json.has("id")) {
            return json.get("id").getAsString();
        }
        return null;
    }

    private String parseStatus(String body) {
        JsonObject json = GSON.fromJson(body, JsonObject.class);
        if (json == null) {
            return null;
        }
        if (json.has("status")) {
            return json.get("status").getAsString().toLowerCase();
        }
        if (json.has("job") && json.get("job").isJsonObject() && json.getAsJsonObject("job").has("status")) {
            return json.getAsJsonObject("job").get("status").getAsString().toLowerCase();
        }
        return null;
    }

    private ImageTagChunk parseChunk(String body) {
        JsonObject json = GSON.fromJson(body, JsonObject.class);
        if (json == null || !json.has("skin")) {
            return null;
        }
        JsonObject skin = json.getAsJsonObject("skin");
        if (!skin.has("texture")) {
            return null;
        }
        JsonObject texture = skin.getAsJsonObject("texture");
        if (!texture.has("data")) {
            return null;
        }
        JsonObject data = texture.getAsJsonObject("data");
        if (!data.has("value")) {
            return null;
        }
        String value = data.get("value").getAsString();
        String signature = data.has("signature") && !data.get("signature").isJsonNull() ? data.get("signature").getAsString() : "";
        return new ImageTagChunk(value, signature);
    }
}
