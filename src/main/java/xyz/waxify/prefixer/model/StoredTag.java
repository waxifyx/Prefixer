package xyz.waxify.prefixer.model;

import java.util.UUID;

public record StoredTag(
    UUID playerId,
    TagKind kind,
    TagType type,
    String value,
    String fallback,
    UUID updatedBy,
    long updatedAtEpochMillis,
    String lastLuckPermsValue
) {
}
