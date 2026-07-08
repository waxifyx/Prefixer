package xyz.waxify.prefixer.command;

import dev.jorel.commandapi.CommandAPICommand;
import dev.jorel.commandapi.CommandTree;
import dev.jorel.commandapi.arguments.EntitySelectorArgument;
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
            .withArguments(new LiteralArgument("reload"))
            .executes((CommandSender sender, CommandArguments args) -> prefixService.reload(sender))
            .register(plugin);
    }

    private static void registerKind(Prefixer plugin, PrefixService prefixService, MessageService messages, String command, TagKind kind) {
        new CommandTree(command)
            .then(playerBranch(plugin, prefixService, messages, command, kind))
            .then(typeBranch(plugin, prefixService, messages, command, kind, "text"))
            .then(typeBranch(plugin, prefixService, messages, command, kind, "raw"))
            .then(imageBranch(plugin, prefixService, messages, command, kind))
            .then(noneBranch(plugin, prefixService, messages, command, kind))
            .then(valueBranch(plugin, prefixService, messages, command, kind))
            .register(plugin);
    }

    private static void executeParsed(Prefixer plugin, PrefixService prefixService, MessageService messages, CommandSender sender, TagKind kind, String input) {
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
                case "image" -> "messages.image-source-missing";
                case "invalid-shadow" -> "messages.invalid-shadow-color";
                case "missing", "usage" -> "messages.invalid-command-usage";
                default -> "messages.invalid-command-usage";
            };
            messages.send(sender, key, Map.of());
        }
    }

    private static Player requireSelf(CommandSender sender, MessageService messages) {
        if (sender instanceof Player player) {
            return player;
        }
        messages.send(sender, "messages.player-only", Map.of());
        return null;
    }

    private static EntitySelectorArgument.OnePlayer playerBranch(
        Prefixer plugin,
        PrefixService prefixService,
        MessageService messages,
        String command,
        TagKind kind
    ) {
        EntitySelectorArgument.OnePlayer player = new EntitySelectorArgument.OnePlayer("player");
        player
            .then(typeBranch(plugin, prefixService, messages, command, kind, "text"))
            .then(typeBranch(plugin, prefixService, messages, command, kind, "raw"))
            .then(imageBranch(plugin, prefixService, messages, command, kind))
            .then(noneBranch(plugin, prefixService, messages, command, kind))
            .then(valueBranch(plugin, prefixService, messages, command, kind));
        return player;
    }

    private static LiteralArgument typeBranch(
        Prefixer plugin,
        PrefixService prefixService,
        MessageService messages,
        String command,
        TagKind kind,
        String literal
    ) {
        LiteralArgument type = new LiteralArgument(literal);
        type.then(valueBranch(plugin, prefixService, messages, command, kind));
        return type;
    }

    private static LiteralArgument imageBranch(
        Prefixer plugin,
        PrefixService prefixService,
        MessageService messages,
        String command,
        TagKind kind
    ) {
        LiteralArgument image = new LiteralArgument("image");
        TextArgument source = new TextArgument("source");
        source.then(new GreedyStringArgument("fallback")
            .executes((CommandSender sender, CommandArguments args) -> executeParsed(plugin, prefixService, messages, sender, kind, rawInput(command, args))));
        LiteralArgument shadow = new LiteralArgument("shadow");
        shadow.then(new StringArgument("shadow_color")
            .executes((CommandSender sender, CommandArguments args) -> {
                String shadowColor = (String) args.get("shadow_color");
                if (!ImageShadow.isValidSpec(shadowColor)) {
                    messages.send(sender, "messages.invalid-shadow-color", Map.of());
                    return;
                }
                messages.send(sender, "messages.image-source-missing", Map.of());
            })
            .then(new GreedyStringArgument("fallback")
                .executes((CommandSender sender, CommandArguments args) -> executeParsed(plugin, prefixService, messages, sender, kind, rawInput(command, args)))));
        source.then(shadow);
        image.then(source);
        return image;
    }

    private static LiteralArgument noneBranch(
        Prefixer plugin,
        PrefixService prefixService,
        MessageService messages,
        String command,
        TagKind kind
    ) {
        LiteralArgument none = new LiteralArgument("none");
        none.executes((CommandSender sender, CommandArguments args) -> executeParsed(plugin, prefixService, messages, sender, kind, rawInput(command, args)));
        return none;
    }

    private static GreedyStringArgument valueBranch(
        Prefixer plugin,
        PrefixService prefixService,
        MessageService messages,
        String command,
        TagKind kind
    ) {
        GreedyStringArgument value = new GreedyStringArgument("value");
        value.executes((CommandSender sender, CommandArguments args) -> executeParsed(plugin, prefixService, messages, sender, kind, rawInput(command, args)));
        return value;
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
