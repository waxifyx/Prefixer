package xyz.waxify.prefixer.config;

import org.bukkit.configuration.file.FileConfiguration;
import xyz.waxify.prefixer.model.StorageMethod;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

public record PrefixerConfig(
    StorageMethod storageMethod,
    boolean placeholderApiEnabled,
    boolean miniPlaceholdersEnabled,
    TagSettings prefix,
    TagSettings suffix,
    DiscordSettings discord
) {
    public static PrefixerConfig load(FileConfiguration config) {
        return new PrefixerConfig(
            StorageMethod.from(config.getString("storage-method", "JSON")),
            config.getBoolean("placeholderapi.enabled", true),
            config.getBoolean("miniplaceholders.enabled", true),
            TagSettings.load(config, "prefix", "{prefix}"),
            TagSettings.load(config, "suffix", "{suffix}"),
            DiscordSettings.load(config)
        );
    }

    public record DiscordSettings(boolean enabled, String token, String guildId, java.util.Set<String> userIds, java.util.Set<String> roleIds) {
        private static DiscordSettings load(FileConfiguration config) {
            return new DiscordSettings(
                config.getBoolean("discord.enabled", false),
                config.getString("discord.token", ""),
                config.getString("discord.guild-id", ""),
                java.util.Set.copyOf(config.getStringList("discord.whitelist.users")),
                java.util.Set.copyOf(config.getStringList("discord.whitelist.roles"))
            );
        }

        public boolean configured() {
            return enabled && token != null && !token.isBlank();
        }
    }

    public record TagSettings(String path, String format, Duration defaultCooldown, int maxLength, Pattern allowedPattern) {
        private static TagSettings load(FileConfiguration config, String path, String token) {
            return new TagSettings(
                path,
                config.getString(path + ".format", token),
                parseDuration(config.getString(path + ".default-cooldown", "1h")),
                Math.max(1, config.getInt(path + ".max-length", 8)),
                Pattern.compile(config.getString(path + ".allowed-regex", ".*"))
            );
        }

        public String token() {
            return "{" + path + "}";
        }
    }

    private static Duration parseDuration(String input) {
        if (input == null || input.isBlank()) {
            return Duration.ZERO;
        }
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        long value = Long.parseLong(normalized.substring(0, normalized.length() - 1));
        char unit = normalized.charAt(normalized.length() - 1);
        return switch (unit) {
            case 's' -> Duration.ofSeconds(value);
            case 'm' -> Duration.ofMinutes(value);
            case 'h' -> Duration.ofHours(value);
            case 'd' -> Duration.ofDays(value);
            default -> throw new IllegalArgumentException("Unsupported duration " + input);
        };
    }
}
