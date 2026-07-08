package xyz.waxify.prefixer.model;

import java.util.Locale;

public enum TagType {
    TEXT,
    RAW,
    IMAGE,
    NONE;

    public static TagType from(String value) {
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "TEXT" -> TEXT;
            case "RAW" -> RAW;
            case "IMAGE" -> IMAGE;
            case "NONE" -> NONE;
            default -> throw new IllegalArgumentException("Unknown tag type " + value);
        };
    }

    public String permissionSegment() {
        return switch (this) {
            case TEXT -> "text";
            case RAW -> "raw";
            case IMAGE -> "images";
            case NONE -> "self";
        };
    }
}
