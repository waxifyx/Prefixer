package xyz.waxify.prefixer.service;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import xyz.waxify.prefixer.Prefixer;
import xyz.waxify.prefixer.config.PrefixerConfig;
import xyz.waxify.prefixer.hook.MiniPlaceholdersHook;
import xyz.waxify.prefixer.model.ImageTagChunk;
import xyz.waxify.prefixer.model.ImageTagPayload;
import xyz.waxify.prefixer.model.StoredTag;
import xyz.waxify.prefixer.model.TagKind;
import xyz.waxify.prefixer.model.TagType;
import xyz.waxify.prefixer.storage.TagStorage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class TagRenderer {
    private static final LegacyComponentSerializer LEGACY_AMPERSAND = LegacyComponentSerializer.builder()
        .character('&')
        .hexColors()
        .useUnusualXRepeatedCharacterHexFormat()
        .build();
    private static final LegacyComponentSerializer LEGACY_AMPERSAND_HEX = LegacyComponentSerializer.builder()
        .character('&')
        .hexColors()
        .build();
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.builder()
        .character('§')
        .hexColors()
        .useUnusualXRepeatedCharacterHexFormat()
        .build();

    private final Prefixer plugin;
    private final PrefixerConfig config;
    private final TagStorage tagStorage;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final MiniMessage textStyleMiniMessage = MiniMessage.builder()
        .tags(net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.builder()
            .resolver(StandardTags.color())
            .resolver(StandardTags.decorations())
            .resolver(StandardTags.gradient())
            .resolver(StandardTags.transition())
            .resolver(StandardTags.rainbow())
            .resolver(StandardTags.shadowColor())
            .resolver(StandardTags.reset())
            .build())
        .build();

    public TagRenderer(Prefixer plugin, PrefixerConfig config, TagStorage tagStorage) {
        this.plugin = plugin;
        this.config = config;
        this.tagStorage = tagStorage;
    }

    public Optional<StoredTag> find(UUID playerId, TagKind kind) {
        return tagStorage.find(playerId, kind);
    }

    public Component renderMiniMessage(Audience audience, String input) {
        if (miniPlaceholdersEnabled() && audience instanceof Pointered pointered) {
            return miniMessage.deserialize(input, pointered, MiniPlaceholdersHook.tagResolver());
        }
        return miniMessage.deserialize(input);
    }

    private Component renderMiniMessage(String input) {
        return miniMessage.deserialize(input);
    }

    public Component renderTextStyle(Audience audience, String input) {
        if (input.indexOf('&') >= 0) {
            return LEGACY_AMPERSAND.deserialize(input);
        }
        if (miniPlaceholdersEnabled() && audience instanceof Pointered pointered) {
            return textStyleMiniMessage.deserialize(input, pointered, MiniPlaceholdersHook.tagResolver());
        }
        return textStyleMiniMessage.deserialize(input);
    }

    private Component renderTextStyle(String input) {
        if (input.indexOf('&') >= 0) {
            return LEGACY_AMPERSAND.deserialize(input);
        }
        return textStyleMiniMessage.deserialize(input);
    }

    public boolean usesTextStyle(String input) {
        return !PlainTextComponentSerializer.plainText().serialize(renderTextStyle(input)).equals(input);
    }

    public String plainTextTextStyle(String input) {
        return PlainTextComponentSerializer.plainText().serialize(renderTextStyle(input));
    }

    public String plainTextMiniMessage(String input) {
        return PlainTextComponentSerializer.plainText().serialize(renderMiniMessage(input));
    }

    public Component renderLuckPermsValue(Audience audience, String input) {
        if (input.indexOf('&') >= 0) {
            return LEGACY_AMPERSAND.deserialize(input);
        }
        if (input.indexOf('§') >= 0) {
            return LEGACY_SECTION.deserialize(input);
        }
        return renderMiniMessage(audience, input);
    }

    public Component renderStored(Audience audience, StoredTag tag) {
        if (tag.type() == TagType.NONE) {
            return Component.empty();
        }
        if (tag.type() == TagType.IMAGE) {
            ImageTagPayload payload = plugin.imageService().deserialize(tag.value());
            ShadowColor shadowColor = ImageShadow.shadowColor(payload.shadowColor());
            Component component = Component.empty();
            for (ImageTagChunk chunk : payload.chunks()) {
                PlayerHeadObjectContents.ProfileProperty property = chunk.signature() == null || chunk.signature().isBlank()
                    ? PlayerHeadObjectContents.property("textures", chunk.value())
                    : PlayerHeadObjectContents.property("textures", chunk.value(), chunk.signature());
                Component head = Component.object(ObjectContents.playerHead()
                    .profileProperties(List.of(property))
                    .hat(true)
                    .build());
                if (shadowColor != null) {
                    head = head.shadowColor(shadowColor);
                }
                component = component.append(head);
            }
            return component;
        }

        String input = tag.value();
        if (tag.type() == TagType.TEXT) {
            PrefixerConfig.TagSettings settings = tag.kind() == TagKind.PREFIX ? config.prefix() : config.suffix();
            String format = settings.format().replace(settings.token(), "<prefixer_value>");
            return renderMiniMessage(audience, format, Placeholder.component("prefixer_value", renderTextStyle(audience, tag.value())));
        }
        return renderMiniMessage(audience, input);
    }

    private Component renderMiniMessage(Audience audience, String input, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver resolver) {
        if (miniPlaceholdersEnabled() && audience instanceof Pointered pointered) {
            return miniMessage.deserialize(input, pointered, MiniPlaceholdersHook.tagResolver(), resolver);
        }
        return miniMessage.deserialize(input, resolver);
    }

    private boolean miniPlaceholdersEnabled() {
        return config.miniPlaceholdersEnabled()
            && plugin.getServer().getPluginManager().getPlugin("MiniPlaceholders") != null;
    }

    public String serializeMiniMessage(Audience audience, StoredTag tag) {
        if (tag.type() == TagType.IMAGE) {
            return tag.fallback();
        }
        return miniMessage.serialize(renderStored(audience, tag));
    }

    public String compatibilityLegacy(Audience audience, StoredTag tag) {
        if (tag.type() == TagType.IMAGE) {
            return tag.fallback();
        }
        String serialized = LEGACY_AMPERSAND_HEX.serialize(renderStored(audience, tag));
        return serialized.isEmpty() ? "" : serialized + "&r";
    }

    public String luckPermsFallbackOrCurrent(UUID playerId, TagKind kind, LuckPermsService luckPermsService) {
        Optional<StoredTag> stored = find(playerId, kind);
        if (stored.isPresent()) {
            String fallback = stored.get().fallback();
            if (!fallback.isBlank()) {
                return fallback;
            }
        }
        return luckPermsService.currentValue(playerId, kind);
    }
}
