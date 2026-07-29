package xyz.waxify.prefixer.service;

import com.google.gson.Gson;
import net.kyori.adventure.text.Component;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import xyz.waxify.prefixer.Prefixer;
import xyz.waxify.prefixer.discord.DiscordCommandSender;
import xyz.waxify.prefixer.model.ImageTagChunk;
import xyz.waxify.prefixer.model.ImageTagPayload;
import xyz.waxify.prefixer.model.StoredTag;
import xyz.waxify.prefixer.model.TagKind;
import xyz.waxify.prefixer.model.TagType;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import xyz.waxify.prefixer.storage.ChunkCacheStorage;

public final class ImageService {
    private static final Gson GSON = new Gson();
    private static final String IMAGE_FETCH_USER_AGENT = "Mozilla/5.0 (compatible; Prefixer/0.0.1; +https://github.com/)";

    private final Prefixer plugin;
    private final MineSkinService mineSkinService;
    private final MessageService messages;
    private final PrefixService prefixService;
    private final ChunkCacheStorage chunkCacheStorage;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    public ImageService(Prefixer plugin, MineSkinService mineSkinService, MessageService messages, PrefixService prefixService, ChunkCacheStorage chunkCacheStorage) {
        this.plugin = plugin;
        this.mineSkinService = mineSkinService;
        this.messages = messages;
        this.prefixService = prefixService;
        this.chunkCacheStorage = chunkCacheStorage;
    }

    public CompletableFuture<Void> setImageAsync(CommandSender sender, OfflinePlayer target, TagKind kind, String source, String fallback, String shadowColor) {
        messages.send(sender, "messages.image-processing", Map.of("kind", kind.id()));
        CompletableFuture<Void> completion = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try {
                if (!mineSkinService.isConfigured()) {
                    throw new UserVisibleException("messages.mineskin-misconfigured");
                }
                String normalizedShadowColor = ImageShadow.normalize(shadowColor);
                byte[] imageBytes = loadBytes(source);
                BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(imageBytes));
                if (image == null) {
                    throw new UserVisibleException("messages.image-fetch-failed");
                }
                validate(image);

                List<ImageTagChunk> chunks = new ArrayList<>();
                for (int x = 0; x < image.getWidth(); x += 8) {
                    BufferedImage chunk = image.getSubimage(x, 0, 8, 8);
                    String hash = hashChunk(chunk);
                    ImageTagChunk cached = chunkCacheStorage.find(hash).orElse(null);
                    if (cached != null) {
                        chunks.add(cached);
                        continue;
                    }
                    ImageTagChunk uploaded = mineSkinService.uploadChunk(buildSkinPng(chunk));
                    chunkCacheStorage.save(hash, uploaded);
                    chunks.add(uploaded);
                }

                ImageTagPayload payload = new ImageTagPayload(image.getWidth(), List.copyOf(chunks), normalizedShadowColor);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (prefixService.finishImageSet(sender, target, kind, payload, fallback)) {
                        completion.complete(null);
                    } else {
                        completion.completeExceptionally(new IllegalStateException("Failed to save image tag"));
                    }
                });
            } catch (IllegalArgumentException ex) {
                if ("invalid-shadow".equals(ex.getMessage())) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> messages.send(sender, "messages.invalid-shadow-color", Map.of()));
                    completion.completeExceptionally(ex);
                    return;
                }
                completion.completeExceptionally(ex);
            } catch (UserVisibleException ex) {
                plugin.getServer().getScheduler().runTask(plugin, () -> messages.send(sender, ex.messageKey(), Map.of()));
                completion.completeExceptionally(ex);
            } catch (Exception ex) {
                plugin.getLogger().severe("Failed to process image tag: " + ex.getMessage());
                plugin.getServer().getScheduler().runTask(plugin, () -> messages.send(sender, "messages.storage-error", Map.of()));
                completion.completeExceptionally(ex);
            }
        });
        return completion;
    }

    public void setImageAsyncDiscord(SlashCommandInteractionEvent event, OfflinePlayer target, TagKind kind, String source, String fallback, String shadowColor) {
        setImageAsync(new DiscordCommandSender(event), target, kind, source, fallback, shadowColor)
            .thenRun(() -> event.getHook().sendMessage("Done.").queue())
            .exceptionally(ex -> null);
    }

    public String serialize(ImageTagPayload payload) {
        return GSON.toJson(payload);
    }

    public ImageTagPayload deserialize(String payload) {
        return GSON.fromJson(payload, ImageTagPayload.class);
    }

    private byte[] loadBytes(String source) throws IOException, InterruptedException {
        if (source.startsWith("http://") || source.startsWith("https://")) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(source))
                .header("User-Agent", IMAGE_FETCH_USER_AGENT)
                .header("Accept", "image/*,*/*;q=0.8")
                .GET()
                .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                throw new IOException("Source image download failed with HTTP " + response.statusCode());
            }
            return response.body();
        }

        Path path = Path.of(source);
        if (!path.isAbsolute()) {
            path = Path.of(".").resolve(source).normalize();
        }
        return Files.readAllBytes(path);
    }

    private void validate(BufferedImage image) {
        if (image.getHeight() != 8 || image.getWidth() < 8 || image.getWidth() % 8 != 0) {
            throw new UserVisibleException("messages.image-invalid-dimensions");
        }
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (((image.getRGB(x, y) >>> 24) & 0xFF) != 0xFF) {
                    throw new UserVisibleException("messages.image-has-transparency");
                }
            }
        }
    }

    private byte[] buildSkinPng(BufferedImage chunk) throws IOException {
        BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 64; y += 8) {
            for (int x = 0; x < 64; x += 8) {
                for (int dy = 0; dy < 8; dy++) {
                    for (int dx = 0; dx < 8; dx++) {
                        skin.setRGB(x + dx, y + dy, chunk.getRGB(dx, dy) & 0x00FFFFFF);
                    }
                }
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(skin, "PNG", output);
        return output.toByteArray();
    }

    private String hashChunk(BufferedImage chunk) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int rgb = chunk.getRGB(x, y);
                    digest.update((byte) (rgb >>> 24));
                    digest.update((byte) (rgb >>> 16));
                    digest.update((byte) (rgb >>> 8));
                    digest.update((byte) rgb);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static final class UserVisibleException extends RuntimeException {
        private final String messageKey;

        private UserVisibleException(String messageKey) {
            this.messageKey = messageKey;
        }

        private String messageKey() {
            return messageKey;
        }
    }
}
