package xyz.waxify.prefixer.hook;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.waxify.prefixer.Prefixer;
import xyz.waxify.prefixer.model.TagKind;

public final class PlaceholderApiHook extends PlaceholderExpansion {
    private final Prefixer plugin;

    public PlaceholderApiHook(Prefixer plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "prefixer";
    }

    @Override
    public @NotNull String getAuthor() {
        return "waxify";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (!(player instanceof Player online)) {
            return "";
        }
        return switch (params.toLowerCase()) {
            case "prefix" -> plugin.prefixService().stringValue(online, TagKind.PREFIX, false);
            case "suffix" -> plugin.prefixService().stringValue(online, TagKind.SUFFIX, false);
            case "prefix_fallback" -> plugin.prefixService().stringValue(online, TagKind.PREFIX, true);
            case "suffix_fallback" -> plugin.prefixService().stringValue(online, TagKind.SUFFIX, true);
            default -> null;
        };
    }
}
