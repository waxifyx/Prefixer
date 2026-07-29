package xyz.waxify.prefixer.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import xyz.waxify.prefixer.model.ImageTagChunk;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class JsonChunkCacheStorage implements ChunkCacheStorage {
    private static final Type MAP_TYPE = new TypeToken<Map<String, ImageTagChunk>>() {}.getType();

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path path;
    private final Map<String, ImageTagChunk> cache;

    public JsonChunkCacheStorage(Path path) {
        this.path = path;
        this.cache = new HashMap<>(load());
    }

    @Override
    public synchronized Optional<ImageTagChunk> find(String hash) {
        return Optional.ofNullable(cache.get(hash));
    }

    @Override
    public synchronized void save(String hash, ImageTagChunk chunk) {
        cache.put(hash, chunk);
        flush();
    }

    @Override
    public void close() {
    }

    private Map<String, ImageTagChunk> load() {
        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                Files.writeString(path, "{}", StandardCharsets.UTF_8);
                return Map.of();
            }
            Map<String, ImageTagChunk> loaded = gson.fromJson(Files.readString(path, StandardCharsets.UTF_8), MAP_TYPE);
            return loaded == null ? Map.of() : loaded;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load JSON chunk cache", ex);
        }
    }

    private void flush() {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(temporary, gson.toJson(cache, MAP_TYPE), StandardCharsets.UTF_8);
            moveIntoPlace(temporary);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write JSON chunk cache", ex);
        }
    }

    private void moveIntoPlace(Path temporary) throws IOException {
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
