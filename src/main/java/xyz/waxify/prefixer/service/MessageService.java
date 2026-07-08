package xyz.waxify.prefixer.service;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.waxify.prefixer.discord.DiscordCommandSender;
import xyz.waxify.prefixer.hook.MiniPlaceholdersHook;

import java.io.File;
import java.util.Map;

public final class MessageService {
    private final JavaPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private YamlConfiguration messages;

    public MessageService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        messages = YamlConfiguration.loadConfiguration(file);
    }

    public void send(CommandSender sender, String path, Map<String, String> placeholders) {
        if (sender instanceof DiscordCommandSender discordSender) {
            discordSender.sendPlainMessage(renderPlain(path, sender, placeholders));
            return;
        }
        sender.sendMessage(render(path, sender, placeholders));
    }

    public String renderPlain(String path, Object audience, Map<String, String> placeholders) {
        return PlainTextComponentSerializer.plainText().serialize(render(path, audience, placeholders));
    }

    public Component render(String path, Object audience, Map<String, String> placeholders) {
        String prefix = messages.getString("prefix", "");
        String body = messages.getString(path, path);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            body = body.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        String joined = prefix + body;
        if (audience instanceof Pointered pointered) {
            return miniMessage.deserialize(joined, pointered, MiniPlaceholdersHook.tagResolver());
        }
        return miniMessage.deserialize(joined);
    }
}
