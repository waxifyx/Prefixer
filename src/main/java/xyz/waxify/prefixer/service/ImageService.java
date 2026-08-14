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
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;

public final class ImageService {
    private static final Gson GSON = new Gson();
    private static final String IMAGE_FETCH_USER_AGENT = "Mozilla/5.0 (compatible; Prefixer/0.0.1; +https://github.com/)";
    public static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_IMAGE_WIDTH = 256;

    private final Prefixer plugin;
    private final MineSkinService mineSkinService;
    private final MessageService messages;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    public ImageService(Prefixer plugin, MineSkinService mineSkinService, MessageService messages) {
        this.plugin = plugin;
        this.mineSkinService = mineSkinService;
        this.messages = messages;
    }

    public CompletableFuture<Void> setImageAsync(CommandSender sender, OfflinePlayer target, TagKind kind, String source, String fallback, String shadowColor) {
        messages.send(sender, "messages.image-processing", Map.of("kind", kind.id()));
        long editVersion = plugin.beginImageEdit(target.getUniqueId(), kind);
        CompletableFuture<Void> completion = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try {
                if (!mineSkinService.isConfigured()) {
                    throw new UserVisibleException("messages.mineskin-misconfigured");
                }
                String normalizedShadowColor = ImageShadow.normalize(shadowColor);
                BufferedImage image = loadAndValidate(source);

                List<ImageTagChunk> chunks = new ArrayList<>();
                for (int x = 0; x < image.getWidth(); x += 8) {
                    BufferedImage chunk = image.getSubimage(x, 0, 8, 8);
                    String hash = hashChunk(chunk);
                    synchronized (plugin.chunkLock(hash)) {
                        ImageTagChunk cached = plugin.withChunkCache(cache -> cache.find(hash).orElse(null));
                        if (cached == null) {
                            cached = mineSkinService.uploadChunk(buildSkinPng(chunk));
                            ImageTagChunk uploaded = cached;
                            plugin.withChunkCache(cache -> {
                                cache.save(hash, uploaded);
                                return null;
                            });
                        }
                        chunks.add(cached);
                    }
                }

                ImageTagPayload payload = new ImageTagPayload(image.getWidth(), List.copyOf(chunks), normalizedShadowColor);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (plugin.prefixService().finishImageSet(sender, target, kind, payload, fallback, editVersion)) {
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

    public CompletableFuture<Void> setImageAsyncDiscord(SlashCommandInteractionEvent event, OfflinePlayer target, TagKind kind, String source, String fallback, String shadowColor) {
        return setImageAsync(new DiscordCommandSender(event), target, kind, source, fallback, shadowColor)
            .thenRun(() -> event.getHook().sendMessage("Done.").queue())
            .exceptionally(ex -> null);
    }

    public String serialize(ImageTagPayload payload) {
        return GSON.toJson(payload);
    }

    public ImageTagPayload deserialize(String payload) {
        return GSON.fromJson(payload, ImageTagPayload.class);
    }

    private BufferedImage loadAndValidate(String source) {
        try {
            BufferedImage image = decode(loadBytes(source));
            validate(image);
            return image;
        } catch (IllegalArgumentException | IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new UserVisibleException("messages.image-fetch-failed");
        }
    }

    private byte[] loadBytes(String source) throws IOException, InterruptedException {
        if (source.startsWith("http://") || source.startsWith("https://")) {
            return loadRemoteBytes(URI.create(source));
        }

        Path path = Path.of(source);
        if (!path.isAbsolute()) {
            path = Path.of(".").resolve(source).normalize();
        }
        if (Files.size(path) > MAX_IMAGE_BYTES) {
            throw new IOException("Source image exceeds " + MAX_IMAGE_BYTES + " bytes");
        }
        try (InputStream input = Files.newInputStream(path)) {
            return readLimited(input);
        }
    }

    private BufferedImage decode(byte[] imageBytes) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(imageBytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new UserVisibleException("messages.image-fetch-failed");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (reader.getHeight(0) != 8 || reader.getWidth(0) < 8 || reader.getWidth(0) > MAX_IMAGE_WIDTH || reader.getWidth(0) % 8 != 0) {
                    throw new UserVisibleException("messages.image-invalid-dimensions");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    private byte[] readLimited(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_IMAGE_BYTES + 1);
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new IOException("Source image exceeds " + MAX_IMAGE_BYTES + " bytes");
        }
        return bytes;
    }

    private byte[] loadRemoteBytes(URI uri) throws IOException, InterruptedException {
        for (int redirects = 0; redirects <= 5; redirects++) {
            validateRemoteUri(uri);
            HttpRequest request = HttpRequest.newBuilder(uri)
                .header("User-Agent", IMAGE_FETCH_USER_AGENT)
                .header("Accept", "image/*,*/*;q=0.8")
                .timeout(java.time.Duration.ofSeconds(30))
                .GET()
                .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() >= 300 && response.statusCode() < 400) {
                    String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new IOException("Source image redirect has no location"));
                    uri = uri.resolve(location);
                    continue;
                }
                if (response.statusCode() >= 400) {
                    throw new IOException("Source image download failed with HTTP " + response.statusCode());
                }
                if (response.headers().firstValueAsLong("Content-Length").orElse(0L) > MAX_IMAGE_BYTES) {
                    throw new IOException("Source image exceeds " + MAX_IMAGE_BYTES + " bytes");
                }
                return readLimited(body);
            }
        }
        throw new IOException("Source image exceeded redirect limit");
    }

    private void validateRemoteUri(URI uri) throws IOException {
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("Source image redirect must use HTTP or HTTPS");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IOException("Source image URL has no host");
        }
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (isPrivateAddress(address)) {
                throw new IOException("Source image URL must not target a private address");
            }
        }
    }

    private boolean isPrivateAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        if (address instanceof Inet6Address) {
            byte first = address.getAddress()[0];
            return (first & 0xFE) == 0xFC;
        }
        return false;
    }

    private void validate(BufferedImage image) {
        if (image.getHeight() != 8 || image.getWidth() < 8 || image.getWidth() > MAX_IMAGE_WIDTH || image.getWidth() % 8 != 0) {
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
