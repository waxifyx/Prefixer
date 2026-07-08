package xyz.waxify.prefixer.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.waxify.prefixer.Prefixer;
import xyz.waxify.prefixer.config.PrefixerConfig;
import xyz.waxify.prefixer.model.TagKind;
import xyz.waxify.prefixer.model.TagType;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

public final class DiscordBot extends ListenerAdapter {
    private final Prefixer plugin;
    private JDA jda;

    public DiscordBot(Prefixer plugin) {
        this.plugin = plugin;
    }

    public void start() {
        PrefixerConfig.DiscordSettings discord = plugin.pluginConfig().discord();
        if (!discord.configured()) {
            plugin.getLogger().info("Discord integration disabled or token missing.");
            return;
        }
        this.jda = JDABuilder.createLight(discord.token())
            .addEventListeners(this)
            .build();

        try {
            jda.awaitReady();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning("Interrupted while waiting for Discord ready state");
            return;
        }

        if (!discord.guildId().isBlank() && jda.getGuildById(discord.guildId()) != null) {
            jda.getGuildById(discord.guildId()).updateCommands().addCommands(command("setprefix"), command("setsuffix")).queue();
            plugin.getLogger().info("Synced Prefixer Discord commands to guild " + discord.guildId());
        } else {
            jda.updateCommands().addCommands(command("setprefix"), command("setsuffix")).queue();
            plugin.getLogger().info("Synced Prefixer Discord commands globally; Discord may take up to one hour to show them.");
        }
    }

    public void stop() {
        if (jda != null) {
            jda.shutdown();
            try {
                jda.awaitShutdown(Duration.ofSeconds(10));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            if (jda.getStatus() != net.dv8tion.jda.api.JDA.Status.SHUTDOWN) {
                jda.shutdownNow();
            }
            jda = null;
        }
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!event.getName().equals("setprefix") && !event.getName().equals("setsuffix")) {
            return;
        }
        PrefixerConfig.DiscordSettings discord = plugin.pluginConfig().discord();
        if (!allowed(event.getMember(), discord)) {
            event.reply(plugin.messages().renderPlain("messages.discord-not-whitelisted", event.getUser(), java.util.Map.of())).setEphemeral(true).queue();
            return;
        }

        event.deferReply(true).queue();
        Bukkit.getScheduler().runTask(plugin, () -> handle(event));
    }

    private void handle(SlashCommandInteractionEvent event) {
        String playerName = event.getOption("player", (String) null, option -> option.getAsString());
        Player target = playerName == null || playerName.isBlank()
            ? Bukkit.getPlayerExact(event.getUser().getName())
            : Bukkit.getPlayerExact(playerName);
        if (target == null) {
            event.getHook().sendMessage(plugin.messages().renderPlain("messages.discord-player-not-found", event.getUser(), java.util.Map.of("player", String.valueOf(playerName)))).queue();
            return;
        }

        TagKind kind = event.getName().equals("setprefix") ? TagKind.PREFIX : TagKind.SUFFIX;
        String typeInput = event.getOption("type", "text", option -> option.getAsString());
        TagType type = TagType.from(typeInput);
        String value = event.getOption("value", "", option -> option.getAsString());
        String fallback = event.getOption("fallback", "", option -> option.getAsString());
        String shadow = event.getOption("shadow", (String) null, option -> option.getAsString());

        if (type == TagType.IMAGE) {
            var attachment = event.getOption("image", null, option -> option.getAsAttachment());
            if (attachment != null) {
                try (InputStream input = attachment.getProxy().download().join()) {
                    Path temp = Files.createTempFile("prefixer-discord-", "-" + attachment.getFileName());
                    Files.copy(input, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    plugin.imageService().setImageAsyncDiscord(event, target, kind, temp.toString(), fallback, shadow);
                } catch (Exception ex) {
                    event.getHook().sendMessage("Failed to download Discord attachment.").queue();
                }
                return;
            }
            plugin.imageService().setImageAsyncDiscord(event, target, kind, value, fallback, shadow);
            return;
        }

        boolean ok = plugin.prefixService().setTag(new DiscordCommandSender(event), target, kind, type, value, fallback);
        if (ok) {
            event.getHook().sendMessage("Done.").queue();
        }
    }

    private boolean allowed(Member member, PrefixerConfig.DiscordSettings discord) {
        if (member == null) {
            return false;
        }
        if (discord.userIds().contains(member.getId())) {
            return true;
        }
        Set<String> allowedRoles = discord.roleIds();
        for (Role role : member.getRoles()) {
            if (allowedRoles.contains(role.getId())) {
                return true;
            }
        }
        return false;
    }

    private net.dv8tion.jda.api.interactions.commands.build.SlashCommandData command(String name) {
        return Commands.slash(name, "Manage Prefixer tags")
            .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR))
            .addOption(OptionType.STRING, "player", "Target Minecraft player name", false)
            .addOption(OptionType.STRING, "type", "text, raw, image, none", false)
            .addOption(OptionType.STRING, "value", "Value or image source", false)
            .addOption(OptionType.STRING, "fallback", "Fallback text for image", false)
            .addOption(OptionType.STRING, "shadow", "Image shadow: none, #RRGGBB, or #RRGGBBAA", false)
            .addOption(OptionType.ATTACHMENT, "image", "Discord image attachment", false);
    }
}
