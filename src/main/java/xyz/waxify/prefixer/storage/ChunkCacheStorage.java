package xyz.waxify.prefixer.storage;

import xyz.waxify.prefixer.model.ImageTagChunk;

import java.util.Optional;

public interface ChunkCacheStorage extends AutoCloseable {
    Optional<ImageTagChunk> find(String hash);

    void save(String hash, ImageTagChunk chunk);

    @Override
    void close();
}
