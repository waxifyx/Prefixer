package xyz.waxify.prefixer.command;

import com.destroystokyo.paper.profile.PlayerProfile;
import dev.jorel.commandapi.CommandTree;
import dev.jorel.commandapi.arguments.Argument;
import dev.jorel.commandapi.arguments.GreedyStringArgument;
import dev.jorel.commandapi.arguments.LiteralArgument;
import dev.jorel.commandapi.arguments.PlayerProfileArgument;
import dev.jorel.commandapi.arguments.TextArgument;
import dev.jorel.commandapi.executors.CommandArguments;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import xyz.waxify.prefixer.Prefixer;
import xyz.waxify.prefixer.model.TagKind;
import xyz.waxify.prefixer.model.TagType;
import xyz.waxify.prefixer.service.ImageShadow;
import xyz.waxify.prefixer.service.PermissionService;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PrefixCommands {
    private final Prefixer plugin;

    private PrefixCommands(Prefixer plugin) {
        this.plugin = plugin;
    }

    public static void register(Prefixer plugin) {
        PrefixCommands commands = new PrefixCommands(plugin);
        new CommandTree("prefixer")
            .then(new LiteralArgument("reload")
                .withPermission("prefixer.reload")
                .executes((CommandSender sender, CommandArguments args) -> plugin.prefixService().reload(sender)))
            .register(plugin);
        commands.registerKind("setprefix", TagKind.PREFIX);
        commands.registerKind("setsuffix", TagKind.SUFFIX);
    }

    private void registerKind(String command, TagKind kind) {
        new CommandTree(command)
            .then(new PlayerProfileArgument("player")
                .then(valueBranch(kind, TagType.TEXT))
                .then(valueBranch(kind, TagType.RAW))
                .then(imageBranch(kind))
                .then(new LiteralArgument("none")
                    .withRequirement(sender -> canAccess(sender, kind, TagType.NONE))
                    .executes((CommandSender sender, CommandArguments args) -> setTag(sender, args, kind, TagType.NONE))))
            .register(plugin);
    }

    private Argument<?> valueBranch(TagKind kind, TagType type) {
        return new LiteralArgument(type.name().toLowerCase(Locale.ROOT))
            .withRequirement(sender -> canAccess(sender, kind, type))
            .then(new GreedyStringArgument("value")
                .executes((CommandSender sender, CommandArguments args) -> setTag(sender, args, kind, type)));
    }

    private Argument<?> imageBranch(TagKind kind) {
        return new LiteralArgument("image")
            .withRequirement(sender -> canAccess(sender, kind, TagType.IMAGE))
            .then(new TextArgument("source")
                .then(fallbackBranch(kind))
                .then(new LiteralArgument("shadow")
                    .then(new TextArgument("shadow_color")
                        .then(fallbackBranch(kind)))));
    }

    private Argument<?> fallbackBranch(TagKind kind) {
        return new LiteralArgument("fallback")
            .then(new GreedyStringArgument("fallback_text")
                .executes((CommandSender sender, CommandArguments args) -> setImage(sender, args, kind)));
    }

    private void setTag(CommandSender sender, CommandArguments args, TagKind kind, TagType type) {
        OfflinePlayer target = resolveTarget(sender, args);
        if (target == null) {
            return;
        }
        String value = type == TagType.NONE ? "" : (String) args.get("value");
        plugin.prefixService().setTag(sender, target, kind, type, value, "");
    }

    private void setImage(CommandSender sender, CommandArguments args, TagKind kind) {
        OfflinePlayer target = resolveTarget(sender, args);
        if (target == null) {
            return;
        }
        boolean self = sender instanceof Player player && player.getUniqueId().equals(target.getUniqueId());
        if (!PermissionService.canSet(sender, kind, TagType.IMAGE, self)) {
            plugin.messages().send(sender, "messages.no-permission", Map.of());
            return;
        }

        String shadow = (String) args.get("shadow_color");
        if (!ImageShadow.isValidSpec(shadow)) {
            plugin.messages().send(sender, "messages.invalid-shadow-color", Map.of());
            return;
        }
        plugin.imageService().setImageAsync(sender, target, kind, (String) args.get("source"),
            (String) args.get("fallback_text"), ImageShadow.normalize(shadow));
    }

    private OfflinePlayer resolveTarget(CommandSender sender, CommandArguments args) {
        List<?> profiles = (List<?>) args.get("player");
        if (profiles.size() != 1) {
            plugin.messages().send(sender, "messages.single-player-required", Map.of());
            return null;
        }
        PlayerProfile profile = (PlayerProfile) profiles.getFirst();
        if (profile.getId() == null) {
            plugin.messages().send(sender, "messages.player-not-found", Map.of("player", args.getRaw("player")));
            return null;
        }
        return Bukkit.getOfflinePlayer(profile.getId());
    }

    private boolean canAccess(CommandSender sender, TagKind kind, TagType type) {
        return (sender instanceof Player && PermissionService.canSet(sender, kind, type, true))
            || PermissionService.canSet(sender, kind, type, false);
    }
}
