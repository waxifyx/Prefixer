package xyz.waxify.prefixer.hook;

import io.github.miniplaceholders.api.Expansion;
import io.github.miniplaceholders.api.MiniPlaceholders;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import xyz.waxify.prefixer.Prefixer;
import xyz.waxify.prefixer.model.TagKind;

public final class MiniPlaceholdersHook {
    private final Prefixer plugin;
    private Expansion expansion;

    public MiniPlaceholdersHook(Prefixer plugin) {
        this.plugin = plugin;
    }

    public void register() {
        if (expansion != null) {
            return;
        }
        Expansion pending = Expansion.builder("prefixer")
            .audiencePlaceholder(Player.class, "prefix", (player, ctx, queue) -> tag(player, TagKind.PREFIX, false))
            .audiencePlaceholder(Player.class, "suffix", (player, ctx, queue) -> tag(player, TagKind.SUFFIX, false))
            .audiencePlaceholder(Player.class, "prefix_fallback", (player, ctx, queue) -> tag(player, TagKind.PREFIX, true))
            .audiencePlaceholder(Player.class, "suffix_fallback", (player, ctx, queue) -> tag(player, TagKind.SUFFIX, true))
            .author("waxify")
            .version(plugin.getPluginMeta().getVersion())
            .build();
        pending.register();
        expansion = pending;
    }

    public void unregister() {
        Expansion current = expansion;
        if (current == null) {
            return;
        }
        try {
            if (current.registered()) {
                current.unregister();
            }
        } finally {
            expansion = null;
        }
    }

    public static TagResolver tagResolver() {
        return MiniPlaceholders.audiencePlaceholders();
    }

    private Tag tag(Player player, TagKind kind, boolean fallbackOnly) {
        Component value = plugin.pluginConfig().miniPlaceholdersEnabled()
            ? plugin.prefixService().component(player, kind, fallbackOnly)
            : Component.empty();
        return Tag.selfClosingInserting(value);
    }
}
