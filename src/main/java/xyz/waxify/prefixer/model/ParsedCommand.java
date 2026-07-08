package xyz.waxify.prefixer.model;

import org.bukkit.entity.Player;

public record ParsedCommand(Player target, TagType type, String value, String fallback) {
}
