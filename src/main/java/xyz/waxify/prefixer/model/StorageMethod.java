package xyz.waxify.prefixer.model;

import java.util.Locale;

public enum StorageMethod {
    JSON,
    SQLITE;

    public static StorageMethod from(String value) {
        if (value == null) {
            return JSON;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "SQLITE" -> SQLITE;
            default -> JSON;
        };
    }
}
