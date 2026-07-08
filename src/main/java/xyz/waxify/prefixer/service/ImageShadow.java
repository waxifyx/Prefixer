package xyz.waxify.prefixer.service;

import net.kyori.adventure.text.format.ShadowColor;

import java.util.Locale;
import java.util.regex.Pattern;

public final class ImageShadow {
    private static final Pattern HEX_RGB = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Pattern HEX_RGBA = Pattern.compile("^#[0-9a-fA-F]{8}$");
    private static final String NONE = "#00000000";

    private ImageShadow() {
    }

    public static boolean isValidSpec(String input) {
        try {
            normalize(input);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static String normalize(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String trimmed = input.trim();
        if (trimmed.equalsIgnoreCase("default")) {
            return null;
        }
        if (trimmed.equalsIgnoreCase("none")) {
            return NONE;
        }
        if (HEX_RGB.matcher(trimmed).matches()) {
            return trimmed.toUpperCase(Locale.ROOT) + "FF";
        }
        if (HEX_RGBA.matcher(trimmed).matches()) {
            return trimmed.toUpperCase(Locale.ROOT);
        }
        throw new IllegalArgumentException("invalid-shadow");
    }

    public static ShadowColor shadowColor(String input) {
        String normalized = normalize(input);
        if (normalized == null) {
            return null;
        }
        ShadowColor shadowColor = ShadowColor.fromHexString(normalized);
        if (shadowColor == null) {
            throw new IllegalArgumentException("invalid-shadow");
        }
        return shadowColor;
    }
}
