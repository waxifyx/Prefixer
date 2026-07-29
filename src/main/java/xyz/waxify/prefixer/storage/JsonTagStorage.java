package xyz.waxify.prefixer.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import xyz.waxify.prefixer.model.StoredTag;
import xyz.waxify.prefixer.model.TagKind;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class JsonTagStorage implements TagStorage {
    private static final Type LIST_TYPE = new TypeToken<List<StoredTag>>() {}.getType();

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path path;
    private final List<StoredTag> tags;

    public JsonTagStorage(Path path) {
        this.path = path;
        this.tags = new ArrayList<>(loadTags(path));
    }

    @Override
    public synchronized Optional<StoredTag> find(UUID playerId, TagKind kind) {
        return tags.stream().filter(tag -> tag.playerId().equals(playerId) && tag.kind() == kind).findFirst();
    }

    @Override
    public synchronized Iterable<StoredTag> all() {
        return List.copyOf(tags);
    }

    @Override
    public synchronized void save(StoredTag tag) {
        delete(tag.playerId(), tag.kind());
        tags.add(tag);
        flush();
    }

    @Override
    public synchronized void delete(UUID playerId, TagKind kind) {
        tags.removeIf(tag -> tag.playerId().equals(playerId) && tag.kind() == kind);
        flush();
    }

    @Override
    public void close() {
    }

    private List<StoredTag> loadTags(Path path) {
        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                Files.writeString(path, "[]", StandardCharsets.UTF_8);
                return List.of();
            }
            String json = Files.readString(path, StandardCharsets.UTF_8);
            List<StoredTag> loaded = gson.fromJson(json, LIST_TYPE);
            return loaded == null ? List.of() : loaded;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load JSON tag storage", ex);
        }
    }

    private void flush() {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(temporary, gson.toJson(tags, LIST_TYPE), StandardCharsets.UTF_8);
            moveIntoPlace(temporary);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write JSON tag storage", ex);
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
