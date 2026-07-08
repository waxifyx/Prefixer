package xyz.waxify.prefixer.hook;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.waxify.prefixer.Prefixer;
import xyz.waxify.prefixer.model.TagKind;
import xyz.waxify.prefixer.service.PrefixService;

public final class PlaceholderApiHook extends PlaceholderExpansion {
    private final Prefixer plugin;
    private final PrefixService prefixService;

    public PlaceholderApiHook(Prefixer plugin, PrefixService prefixService) {
        this.plugin = plugin;
        this.prefixService = prefixService;
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
            case "prefix" -> prefixService.stringValue(online, TagKind.PREFIX, false);
            case "suffix" -> prefixService.stringValue(online, TagKind.SUFFIX, false);
            case "prefix_fallback" -> prefixService.stringValue(online, TagKind.PREFIX, true);
            case "suffix_fallback" -> prefixService.stringValue(online, TagKind.SUFFIX, true);
            default -> null;
        };
    }
}
