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

    public java.util.concurrent.CompletableFuture<Boolean> setTag(CommandSender sender, OfflinePlayer target, TagKind kind, TagType type, String value, String fallback) {
        boolean self = sender instanceof Player player && player.getUniqueId().equals(target.getUniqueId());
        if (!PermissionService.canSet(sender, kind, type, self)) {
            messages.send(sender, "messages.no-permission", Map.of());
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }

        PrefixerConfig.TagSettings settings = kind == TagKind.PREFIX ? config.prefix() : config.suffix();
        Optional<StoredTag> previous = plugin.findCurrentTag(target.getUniqueId(), kind);

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
                    return java.util.concurrent.CompletableFuture.completedFuture(false);
                }
            }
        }

        String normalizedValue = value;
        if (type == TagType.TEXT) {
            boolean usesTextStyle = tagRenderer.usesTextStyle(value);
            if (usesTextStyle && !permissionService.canUseTextStyle(sender, kind, self)) {
                messages.send(sender, "messages.text-style-not-allowed", Map.of("kind", kind.id()));
                return java.util.concurrent.CompletableFuture.completedFuture(false);
            }
            normalizedValue = tagRenderer.plainTextTextStyle(value);
            if (!settings.allowedPattern().matcher(normalizedValue).matches()) {
                messages.send(sender, "messages.regex-failed", Map.of("kind", kind.id()));
                return java.util.concurrent.CompletableFuture.completedFuture(false);
            }
        } else if (type == TagType.RAW) {
            normalizedValue = tagRenderer.plainTextMiniMessage(value);
        }

        if ((type == TagType.TEXT || type == TagType.RAW) && normalizedValue.length() > permissionService.effectiveMaxLength(sender, kind)) {
            messages.send(sender, "messages.max-length", Map.of(
                "kind", kind.id(),
                "length", Integer.toString(permissionService.effectiveMaxLength(sender, kind))
            ));
            return java.util.concurrent.CompletableFuture.completedFuture(false);
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

        final String compatibility;
        try {
            Audience renderingAudience = target.getPlayer() == null ? plugin.getServer().getConsoleSender() : target.getPlayer();
            compatibility = type == TagType.NONE ? "" : tagRenderer.compatibilityLegacy(renderingAudience, pending);
        } catch (Exception ex) {
            plugin.getLogger().severe("Failed to save tag: " + ex.getMessage());
            messages.send(sender, "messages.storage-error", Map.of());
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }

        long editVersion = plugin.beginTagEdit(target.getUniqueId(), kind);
        return plugin.enqueueTagMutation(target.getUniqueId(), kind, () -> {
            if (!plugin.isCurrentImageEdit(target.getUniqueId(), kind, editVersion)) {
                return java.util.concurrent.CompletableFuture.completedFuture(false);
            }
            Optional<StoredTag> current = plugin.findCurrentTag(target.getUniqueId(), kind);
            String lastManagedValue = type == TagType.NONE ? current.map(StoredTag::lastLuckPermsValue).orElse("") : compatibility;
            StoredTag finalTag = new StoredTag(
                pending.playerId(), pending.kind(), pending.type(), pending.value(), pending.fallback(),
                pending.updatedBy(), pending.updatedAtEpochMillis(), lastManagedValue
            );
            return luckPermsService.syncCompatibility(target.getUniqueId(), kind, current.orElse(null), compatibility)
                .handle((ignored, failure) -> plugin.runOnServerThread(() -> {
                    if (failure != null) {
                        plugin.getLogger().severe("Failed to sync LuckPerms tag: " + failure.getMessage());
                        messages.send(sender, "messages.luckperms-error", Map.of());
                        return false;
                    }
                    if (!plugin.isCurrentImageEdit(target.getUniqueId(), kind, editVersion)) {
                        return false;
                    }
                    try {
                        plugin.saveCurrentTag(finalTag);
                        sendSuccess(sender, target, kind, type, value, self);
                        return true;
                    } catch (Exception ex) {
                        plugin.getLogger().severe("Failed to save tag: " + ex.getMessage());
                        messages.send(sender, "messages.storage-error", Map.of());
                        return false;
                    }
                })).thenCompose(java.util.function.Function.identity());
        });
    }

    public Component component(Player player, TagKind kind, boolean fallbackOnly) {
        return plugin.withStateRead(() -> componentUnsafe(player, kind, fallbackOnly));
    }

    private Component componentUnsafe(Player player, TagKind kind, boolean fallbackOnly) {
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
        return plugin.withStateRead(() -> stringValueUnsafe(player, kind, fallbackOnly));
    }

    private String stringValueUnsafe(Player player, TagKind kind, boolean fallbackOnly) {
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

    public java.util.concurrent.CompletableFuture<Boolean> finishImageSet(CommandSender sender, OfflinePlayer target, TagKind kind, ImageTagPayload payload, String fallback, long editVersion) {
        if (!plugin.isCurrentImageEdit(target.getUniqueId(), kind, editVersion)) {
            messages.send(sender, "messages.image-superseded", Map.of("kind", kind.id()));
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }
        boolean self = sender instanceof Player player && player.getUniqueId().equals(target.getUniqueId());
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

        String compatibility = fallback;
        return plugin.enqueueTagMutation(target.getUniqueId(), kind, () -> {
            if (!plugin.isCurrentImageEdit(target.getUniqueId(), kind, editVersion)) {
                return java.util.concurrent.CompletableFuture.completedFuture(false);
            }
            Optional<StoredTag> current = plugin.findCurrentTag(target.getUniqueId(), kind);
            StoredTag finalTag = new StoredTag(
                pending.playerId(), pending.kind(), pending.type(), pending.value(), pending.fallback(),
                pending.updatedBy(), pending.updatedAtEpochMillis(), compatibility
            );
            return luckPermsService.syncCompatibility(target.getUniqueId(), kind, current.orElse(null), compatibility)
                .handle((ignored, failure) -> plugin.runOnServerThread(() -> {
                    if (failure != null) {
                        plugin.getLogger().severe("Failed to sync LuckPerms image tag: " + failure.getMessage());
                        messages.send(sender, "messages.luckperms-error", Map.of());
                        return false;
                    }
                    if (!plugin.isCurrentImageEdit(target.getUniqueId(), kind, editVersion)) {
                        return false;
                    }
                    try {
                        plugin.saveCurrentTag(finalTag);
                        sendSuccess(sender, target, kind, TagType.IMAGE, fallback, self);
                        return true;
                    } catch (Exception ex) {
                        plugin.getLogger().severe("Failed to finalize image tag: " + ex.getMessage());
                        messages.send(sender, "messages.storage-error", Map.of());
                        return false;
                    }
                })).thenCompose(java.util.function.Function.identity());
        });
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
                    default -> tagRenderer.compatibilityLegacy(plugin.getServer().getConsoleSender(), tag);
                };
                String finalCompatibility = compatibility == null ? "" : compatibility;
                plugin.enqueueTagMutation(tag.playerId(), tag.kind(), () -> {
                    if (!plugin.findCurrentTag(tag.playerId(), tag.kind()).filter(tag::equals).isPresent()) {
                        return java.util.concurrent.CompletableFuture.completedFuture(false);
                    }
                    return luckPermsService.syncCompatibility(tag.playerId(), tag.kind(), tag, finalCompatibility)
                        .handle((ignored, failure) -> plugin.runOnServerThread(() -> {
                            if (failure != null) {
                                plugin.getLogger().warning("Failed to reconcile LuckPerms state for " + tag.playerId() + " " + tag.kind() + ": " + failure.getMessage());
                                return false;
                            }
                            if (!plugin.findCurrentTag(tag.playerId(), tag.kind()).filter(tag::equals).isPresent()) {
                                return false;
                            }
                            if (!finalCompatibility.equals(tag.lastLuckPermsValue())) {
                                plugin.saveCurrentTag(new StoredTag(
                                    tag.playerId(), tag.kind(), tag.type(), tag.value(), tag.fallback(),
                                    tag.updatedBy(), tag.updatedAtEpochMillis(), finalCompatibility
                                ));
                            }
                            return true;
                        })).thenCompose(java.util.function.Function.identity());
                });
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

    private void sendSuccess(CommandSender sender, OfflinePlayer target, TagKind kind, TagType type, String value, boolean self) {
        if (type == TagType.NONE) {
            messages.send(sender, self ? "messages.cleared-self" : "messages.cleared-other", Map.of(
                "kind", kind.id(), "player", targetName(target)
            ));
            return;
        }
        messages.send(sender, self ? "messages.set-self" : "messages.set-other", Map.of(
            "kind", kind.id(), "player", targetName(target), "value", value
        ));
    }
}
