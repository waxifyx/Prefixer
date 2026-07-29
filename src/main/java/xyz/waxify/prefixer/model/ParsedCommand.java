package xyz.waxify.prefixer.model;

import org.bukkit.OfflinePlayer;

public record ParsedCommand(OfflinePlayer target, TagType type, String value, String fallback, String shadowColor) {
}
