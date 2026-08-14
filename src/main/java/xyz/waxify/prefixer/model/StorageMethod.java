package xyz.waxify.prefixer.model;

import java.util.Locale;

public enum StorageMethod {
    JSON,
    SQLITE;

    public static StorageMethod from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("storage-method must be JSON or SQLITE");
        }
        try {
            return StorageMethod.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("storage-method must be JSON or SQLITE", ex);
        }
    }
}
