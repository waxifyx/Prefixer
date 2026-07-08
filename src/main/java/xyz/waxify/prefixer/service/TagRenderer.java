package xyz.waxify.prefixer.service;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
    private final Prefixer plugin;
    private final PrefixerConfig config;
    private final TagStorage tagStorage;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public TagRenderer(Prefixer plugin, PrefixerConfig config, TagStorage tagStorage) {
        this.plugin = plugin;
        this.config = config;
        this.tagStorage = tagStorage;
    }

    public Optional<StoredTag> find(UUID playerId, TagKind kind) {
        return tagStorage.find(playerId, kind);
    }

    public Component renderStored(Audience audience, StoredTag tag) {
        if (tag.type() == TagType.NONE) {
            return Component.empty();
        }
        if (tag.type() == TagType.IMAGE) {
            ImageTagPayload payload = plugin.imageService().deserialize(tag.value());
            Component component = Component.empty();
            for (ImageTagChunk chunk : payload.chunks()) {
                PlayerHeadObjectContents.ProfileProperty property = chunk.signature() == null || chunk.signature().isBlank()
                    ? PlayerHeadObjectContents.property("textures", chunk.value())
                    : PlayerHeadObjectContents.property("textures", chunk.value(), chunk.signature());
                component = component.append(Component.object(ObjectContents.playerHead()
                    .profileProperties(List.of(property))
                    .hat(true)
                    .build()));
            }
            return component;
        }

        String input = tag.value();
        if (tag.type() == TagType.TEXT) {
            PrefixerConfig.TagSettings settings = tag.kind() == TagKind.PREFIX ? config.prefix() : config.suffix();
            input = settings.format().replace(settings.token(), tag.value());
        }
        if (audience instanceof Pointered pointered) {
            return miniMessage.deserialize(input, pointered, MiniPlaceholdersHook.tagResolver());
        }
        return miniMessage.deserialize(input);
    }

    public String serializeMiniMessage(Audience audience, StoredTag tag) {
        if (tag.type() == TagType.IMAGE) {
            return tag.fallback();
        }
        return miniMessage.serialize(renderStored(audience, tag));
    }

    public String compatibilityPlainText(Audience audience, StoredTag tag) {
        if (tag.type() == TagType.IMAGE) {
            return tag.fallback();
        }
        return PlainTextComponentSerializer.plainText().serialize(renderStored(audience, tag));
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
