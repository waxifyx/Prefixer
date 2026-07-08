package xyz.waxify.prefixer.storage;

import xyz.waxify.prefixer.model.StoredTag;
import xyz.waxify.prefixer.model.TagKind;

import java.util.Optional;
import java.util.UUID;

public interface TagStorage extends AutoCloseable {
    Iterable<StoredTag> all();

    Optional<StoredTag> find(UUID playerId, TagKind kind);

    void save(StoredTag tag);

    void delete(UUID playerId, TagKind kind);

    @Override
    void close();
}
