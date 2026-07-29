package xyz.waxify.prefixer.service;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import xyz.waxify.prefixer.Prefixer;
import xyz.waxify.prefixer.config.PrefixerConfig;
import xyz.waxify.prefixer.model.StoredTag;
import xyz.waxify.prefixer.model.TagKind;
import xyz.waxify.prefixer.model.TagType;
import xyz.waxify.prefixer.model.ImageTagPayload;
import xyz.waxify.prefixer.storage.TagStorage;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class PrefixService {
    private final Prefixer plugin;
    private final PrefixerConfig config;
    private final TagStorage tagStorage;
    private final TagRenderer tagRenderer;
    private final LuckPermsService luckPermsService;
    private final PermissionService permissionService;
    private final MessageService messages;

    public PrefixService(
        Prefixer plugin,
        PrefixerConfig config,
        TagStorage tagStorage,
        TagRenderer tagRenderer,
        LuckPermsService luckPermsService,
        PermissionService permissionService,
        MessageService messages
    ) {
        this.plugin = plugin;
        this.config = config;
        this.tagStorage = tagStorage;
        this.tagRenderer = tagRenderer;
        this.luckPermsService = luckPermsService;
        this.permissionService = permissionService;
        this.messages = messages;
    }

    public void reload(CommandSender sender) {
        plugin.reloadPluginState();
        messages.send(sender, "messages.reload-success", Map.of());
    }

    public boolean setTag(CommandSender sender, OfflinePlayer target, TagKind kind, TagType type, String value, String fallback) {
        boolean self = sender instanceof Player player && player.getUniqueId().equals(target.getUniqueId());
        if (!permissionService.canSet(sender, kind, type, self)) {
            messages.send(sender, "messages.no-permission", Map.of());
            return false;
        }

        PrefixerConfig.TagSettings settings = kind == TagKind.PREFIX ? config.prefix() : config.suffix();
        Optional<StoredTag> previous = tagStorage.find(target.getUniqueId(), kind);

        if (previous.isPresent()) {
            Duration cooldown = permissionService.effectiveCooldown(sender, kind);
            if (!cooldown.isZero()) {
                long ageMillis = Instant.now().toEpochMilli() - previous.get().updatedAtEpochMillis();
                if (ageMillis < cooldown.toMillis()) {
                    Duration remaining = cooldown.minusMillis(ageMillis);
                    messages.send(sender, "messages.cooldown-active", Map.of(
                        "duration", formatDuration(remaining),
                        "kind", kind.id()
                    ));
                    return false;
                }
            }
        }

        String normalizedValue = value;
        if (type == TagType.TEXT) {
            boolean usesTextStyle = tagRenderer.usesTextStyle(value);
            if (usesTextStyle && !permissionService.canUseTextStyle(sender, kind, self)) {
                messages.send(sender, "messages.text-style-not-allowed", Map.of("kind", kind.id()));
                return false;
            }
            normalizedValue = tagRenderer.plainTextTextStyle(value);
            if (!settings.allowedPattern().matcher(normalizedValue).matches()) {
                messages.send(sender, "messages.regex-failed", Map.of("kind", kind.id()));
                return false;
            }
        } else if (type == TagType.RAW) {
            normalizedValue = tagRenderer.plainTextMiniMessage(value);
        }

        if ((type == TagType.TEXT || type == TagType.RAW) && normalizedValue.length() > permissionService.effectiveMaxLength(sender, kind)) {
            messages.send(sender, "messages.max-length", Map.of(
                "kind", kind.id(),
                "length", Integer.toString(permissionService.effectiveMaxLength(sender, kind))
            ));
            return false;
        }

        StoredTag pending = new StoredTag(
            target.getUniqueId(),
            kind,
            type,
            value,
            fallback == null ? "" : fallback,
            actorUuid(sender, target),
            Instant.now().toEpochMilli(),
            ""
        );

        try {
            Audience renderingAudience = target.getPlayer() == null ? plugin.getServer().getConsoleSender() : target.getPlayer();
            String compatibility = type == TagType.NONE
                ? ""
                : tagRenderer.compatibilityPlainText(renderingAudience, pending);
            luckPermsService.syncCompatibility(target.getUniqueId(), kind, previous.orElse(null), compatibility);
            String lastManagedValue = type == TagType.NONE
                ? previous.map(StoredTag::lastLuckPermsValue).orElse("")
                : compatibility;

            StoredTag finalTag = new StoredTag(
                pending.playerId(),
                pending.kind(),
                pending.type(),
                pending.value(),
                pending.fallback(),
                pending.updatedBy(),
                pending.updatedAtEpochMillis(),
                lastManagedValue
            );
            tagStorage.save(finalTag);
        } catch (Exception ex) {
            plugin.getLogger().severe("Failed to save tag: " + ex.getMessage());
            messages.send(sender, "messages.storage-error", Map.of());
            return false;
        }

        if (type == TagType.NONE) {
            messages.send(sender, self ? "messages.cleared-self" : "messages.cleared-other", Map.of(
                "kind", kind.id(),
                "player", targetName(target)
            ));
            return true;
        }

        messages.send(sender, self ? "messages.set-self" : "messages.set-other", Map.of(
            "kind", kind.id(),
            "player", targetName(target),
            "value", value
        ));
        return true;
    }

    public Component component(Player player, TagKind kind, boolean fallbackOnly) {
        Optional<StoredTag> tag = tagStorage.find(player.getUniqueId(), kind)
            .filter(storedTag -> storedTag.type() != TagType.NONE);

        if (tag.isEmpty()) {
            return tagRenderer.renderLuckPermsValue(player, luckPermsService.currentValue(player.getUniqueId(), kind));
        }
        if (fallbackOnly) {
            return tagRenderer.renderLuckPermsValue(player, tagRenderer.luckPermsFallbackOrCurrent(player.getUniqueId(), kind, luckPermsService));
        }
        return tagRenderer.renderStored(player, tag.get());
    }

    public String stringValue(Player player, TagKind kind, boolean fallbackOnly) {
        Optional<StoredTag> tag = tagStorage.find(player.getUniqueId(), kind)
            .filter(storedTag -> storedTag.type() != TagType.NONE);

        if (tag.isEmpty()) {
            return luckPermsService.currentValue(player.getUniqueId(), kind);
        }
        if (fallbackOnly) {
            return tagRenderer.luckPermsFallbackOrCurrent(player.getUniqueId(), kind, luckPermsService);
        }
        return tagRenderer.serializeMiniMessage(player, tag.get());
    }

    public TagRenderer renderer() {
        return tagRenderer;
    }

    public void finishImageSet(CommandSender sender, OfflinePlayer target, TagKind kind, ImageTagPayload payload, String fallback) {
        boolean self = sender instanceof Player player && player.getUniqueId().equals(target.getUniqueId());
        Optional<StoredTag> previous = tagStorage.find(target.getUniqueId(), kind);
        StoredTag pending = new StoredTag(
            target.getUniqueId(),
            kind,
            TagType.IMAGE,
            plugin.imageService().serialize(payload),
            fallback,
            actorUuid(sender, target),
            Instant.now().toEpochMilli(),
            ""
        );

        try {
            String compatibility = fallback;
            luckPermsService.syncCompatibility(target.getUniqueId(), kind, previous.orElse(null), compatibility);
            tagStorage.save(new StoredTag(
                pending.playerId(),
                pending.kind(),
                pending.type(),
                pending.value(),
                pending.fallback(),
                pending.updatedBy(),
                pending.updatedAtEpochMillis(),
                compatibility
            ));
            messages.send(sender, self ? "messages.set-self" : "messages.set-other", Map.of(
                "kind", kind.id(),
                "player", targetName(target),
                "value", fallback
            ));
        } catch (Exception ex) {
            plugin.getLogger().severe("Failed to finalize image tag: " + ex.getMessage());
            messages.send(sender, "messages.storage-error", Map.of());
        }
    }

    private UUID actorUuid(CommandSender sender, OfflinePlayer target) {
        return sender instanceof Player player ? player.getUniqueId() : target.getUniqueId();
    }

    private String targetName(OfflinePlayer target) {
        return target.getName() == null ? target.getUniqueId().toString() : target.getName();
    }

    public void reconcileLuckPermsState() {
        for (StoredTag tag : tagStorage.all()) {
            try {
                String compatibility = switch (tag.type()) {
                    case NONE -> "";
                    case IMAGE -> tag.fallback();
                    default -> tag.lastLuckPermsValue();
                };
                luckPermsService.syncCompatibility(tag.playerId(), tag.kind(), tag, compatibility == null ? "" : compatibility);
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to reconcile LuckPerms state for " + tag.playerId() + " " + tag.kind() + ": " + ex.getMessage());
            }
        }
    }

    private String formatDuration(Duration duration) {
        long seconds = Math.max(1L, duration.toSeconds());
        long days = seconds / 86400;
        if (days > 0) {
            return days + "d";
        }
        long hours = seconds / 3600;
        if (hours > 0) {
            return hours + "h";
        }
        long minutes = seconds / 60;
        if (minutes > 0) {
            return minutes + "m";
        }
        return seconds + "s";
    }
}
