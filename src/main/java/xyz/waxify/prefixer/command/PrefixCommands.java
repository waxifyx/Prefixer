package xyz.waxify.prefixer.command;

import dev.jorel.commandapi.CommandAPICommand;
import dev.jorel.commandapi.CommandTree;
import dev.jorel.commandapi.arguments.GreedyStringArgument;
import dev.jorel.commandapi.arguments.LiteralArgument;
import dev.jorel.commandapi.arguments.StringArgument;
import dev.jorel.commandapi.arguments.TextArgument;
import dev.jorel.commandapi.executors.CommandArguments;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import xyz.waxify.prefixer.Prefixer;
import xyz.waxify.prefixer.model.ParsedCommand;
import xyz.waxify.prefixer.model.TagKind;
import xyz.waxify.prefixer.model.TagType;
import xyz.waxify.prefixer.service.CommandSyntax;
import xyz.waxify.prefixer.service.ImageShadow;
import xyz.waxify.prefixer.service.MessageService;
import xyz.waxify.prefixer.service.PrefixService;

import java.util.Map;

public final class PrefixCommands {
    private PrefixCommands() {
    }

    public static void register(Prefixer plugin, PrefixService prefixService, MessageService messages) {
        registerReload(plugin, prefixService);
        registerKind(plugin, prefixService, messages, "setprefix", TagKind.PREFIX);
        registerKind(plugin, prefixService, messages, "setsuffix", TagKind.SUFFIX);
    }

    private static void registerReload(Prefixer plugin, PrefixService prefixService) {
        new CommandAPICommand("prefixer")
            .withPermission("prefixer.reload")
            .withArguments(new LiteralArgument("reload"))
            .executes((CommandSender sender, CommandArguments args) -> plugin.prefixService().reload(sender))
            .register(plugin);
    }

    private static void registerKind(Prefixer plugin, PrefixService prefixService, MessageService messages, String command, TagKind kind) {
        new CommandTree(command)
            .then(playerBranch(plugin, prefixService, messages, command, kind))
            .then(typeBranch(plugin, prefixService, messages, command, kind, "text", TagType.TEXT, true))
            .then(typeBranch(plugin, prefixService, messages, command, kind, "raw", TagType.RAW, true))
            .then(imageBranch(plugin, prefixService, messages, command, kind, true))
            .then(noneBranch(plugin, prefixService, messages, command, kind, true))
            .then(valueBranch(plugin, prefixService, messages, command, kind, TagType.TEXT, true))
            .register(plugin);
    }

    private static void executeParsed(Prefixer plugin, PrefixService prefixService, MessageService messages, CommandSender sender, TagKind kind, String input) {
        prefixService = plugin.prefixService();
        Player self = requireSelf(sender, messages);
        if (self == null) {
            return;
        }
        try {
            ParsedCommand parsed = CommandSyntax.parse(sender, self, input);
            if (parsed.type() == xyz.waxify.prefixer.model.TagType.IMAGE) {
                plugin.imageService().setImageAsync(sender, parsed.target(), kind, parsed.value(), parsed.fallback(), parsed.shadowColor());
            } else {
                prefixService.setTag(sender, parsed.target(), kind, parsed.type(), parsed.value(), parsed.fallback());
            }
        } catch (IllegalArgumentException ex) {
            String key = switch (ex.getMessage()) {
                case "image" -> "messages.image-command-usage";
                case "invalid-shadow" -> "messages.invalid-shadow-color";
                case "player-not-found" -> "messages.player-not-found";
                case "missing", "usage" -> "messages.invalid-command-usage";
                default -> "messages.invalid-command-usage";
            };
            messages.send(sender, key, "messages.player-not-found".equals(key)
                ? Map.of("player", input.split("\\s+", 2)[0])
                : Map.of());
        }
    }

    private static Player requireSelf(CommandSender sender, MessageService messages) {
        if (sender instanceof Player player) {
            return player;
        }
        messages.send(sender, "messages.player-only", Map.of());
        return null;
    }

    private static StringArgument playerBranch(
        Prefixer plugin,
        PrefixService prefixService,
        MessageService messages,
        String command,
        TagKind kind
    ) {
        StringArgument player = new StringArgument("player");
        player.withRequirement(sender -> canAny(sender, kind, false));
        player
            .then(typeBranch(plugin, prefixService, messages, command, kind, "text", TagType.TEXT, false))
            .then(typeBranch(plugin, prefixService, messages, command, kind, "raw", TagType.RAW, false))
            .then(imageBranch(plugin, prefixService, messages, command, kind, false))
            .then(noneBranch(plugin, prefixService, messages, command, kind, false));
        return player;
    }

    private static LiteralArgument typeBranch(
        Prefixer plugin,
        PrefixService prefixService,
        MessageService messages,
        String command,
        TagKind kind,
        String literal,
        TagType type,
        boolean self
    ) {
        LiteralArgument branch = new LiteralArgument(literal);
        branch.withRequirement(sender -> canAccess(sender, kind, type, self));
        branch.then(valueBranch(plugin, prefixService, messages, command, kind, type, self));
        return branch;
    }

    private static LiteralArgument imageBranch(
        Prefixer plugin,
        PrefixService prefixService,
        MessageService messages,
        String command,
        TagKind kind,
        boolean self
    ) {
        LiteralArgument image = new LiteralArgument("image");
        image.withRequirement(sender -> canAccess(sender, kind, TagType.IMAGE, self));
        TextArgument source = new TextArgument("source");
        LiteralArgument fallback = new LiteralArgument("fallback");
        fallback.then(new GreedyStringArgument("fallback_text")
            .executes((CommandSender sender, CommandArguments args) -> executeParsed(plugin, prefixService, messages, sender, kind, rawInput(command, args))));
        LiteralArgument shadow = new LiteralArgument("shadow");
        shadow.then(new TextArgument("shadow_color")
            .executes((CommandSender sender, CommandArguments args) -> {
                String shadowColor = (String) args.get("shadow_color");
                if (!ImageShadow.isValidSpec(shadowColor)) {
                    messages.send(sender, "messages.invalid-shadow-color", Map.of());
                    return;
                }
                messages.send(sender, "messages.image-command-usage", Map.of());
            })
            .then(new LiteralArgument("fallback")
                .then(new GreedyStringArgument("fallback_text")
                    .executes((CommandSender sender, CommandArguments args) -> executeParsed(plugin, prefixService, messages, sender, kind, rawInput(command, args))))));
        source.then(fallback);
        source.then(shadow);
        image.then(source);
        return image;
    }

    private static LiteralArgument noneBranch(
        Prefixer plugin,
        PrefixService prefixService,
        MessageService messages,
        String command,
        TagKind kind,
        boolean self
    ) {
        LiteralArgument none = new LiteralArgument("none");
        none.withRequirement(sender -> canAccess(sender, kind, TagType.NONE, self));
        none.executes((CommandSender sender, CommandArguments args) -> executeParsed(plugin, prefixService, messages, sender, kind, rawInput(command, args)));
        return none;
    }

    private static GreedyStringArgument valueBranch(
        Prefixer plugin,
        PrefixService prefixService,
        MessageService messages,
        String command,
        TagKind kind,
        TagType type,
        boolean self
    ) {
        GreedyStringArgument value = new GreedyStringArgument("value");
        value.withRequirement(sender -> canAccess(sender, kind, type, self));
        value.executes((CommandSender sender, CommandArguments args) -> executeParsed(plugin, prefixService, messages, sender, kind, rawInput(command, args)));
        return value;
    }

    private static boolean canAny(CommandSender sender, TagKind kind, boolean self) {
        return canAccess(sender, kind, TagType.TEXT, self)
            || canAccess(sender, kind, TagType.RAW, self)
            || canAccess(sender, kind, TagType.IMAGE, self)
            || canAccess(sender, kind, TagType.NONE, self);
    }

    private static boolean canAccess(CommandSender sender, TagKind kind, TagType type, boolean self) {
        String root = "prefixer." + kind.permissionRoot();
        if (self) {
            return sender instanceof Player && (sender.hasPermission(root + ".self") || sender.hasPermission(root + ".self." + type.permissionSegment()));
        }
        return sender.hasPermission(root + ".others." + type.permissionSegment());
    }

    private static String rawInput(String command, CommandArguments args) {
        String fullInput = args.fullInput();
        int space = fullInput.indexOf(' ');
        if (space < 0 || space + 1 >= fullInput.length()) {
            return "";
        }
        return fullInput.substring(space + 1);
    }
}
