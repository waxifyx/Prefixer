package xyz.waxify.prefixer.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.StatusChangeEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import xyz.waxify.prefixer.Prefixer;
import xyz.waxify.prefixer.config.PrefixerConfig;
import xyz.waxify.prefixer.model.TagKind;
import xyz.waxify.prefixer.model.TagType;
import xyz.waxify.prefixer.service.ImageService;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public final class DiscordBot extends ListenerAdapter {
    private final Prefixer plugin;
    private volatile JDA jda;
    private volatile boolean stopped;

    public DiscordBot(Prefixer plugin) {
        this.plugin = plugin;
    }

    public void start() {
        PrefixerConfig.DiscordSettings discord = plugin.pluginConfig().discord();
        if (!discord.configured()) {
            plugin.getLogger().info("Discord integration disabled or token missing.");
            return;
        }
        // Non-blocking: login proceeds asynchronously; commands register on ReadyEvent.
        // Reset first so this instance's ReadyEvent is accepted even after a prior stop().
        stopped = false;
        final JDA built;
        try {
            built = JDABuilder.createLight(discord.token())
                .addEventListeners(this)
                .build();
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Failed to start Discord bot: " + ex.getMessage());
            return;
        }
        this.jda = built;
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        // Guard on stopped only. The event carries its own JDA instance, so comparing
        // against the field could drop the sole ReadyEvent if it arrives before the
        // assignment in start() becomes visible on this thread.
        if (stopped) {
            return;
        }
        JDA readyJda = event.getJDA();
        PrefixerConfig.DiscordSettings discord = plugin.pluginConfig().discord();
        if (!discord.configured()) {
            return;
        }
        if (!discord.guildId().isBlank()) {
            var guild = readyJda.getGuildById(discord.guildId());
            if (guild == null) {
                plugin.getLogger().warning("Configured Discord guild " + discord.guildId() + " is unavailable; skipping command registration.");
                return;
            }
            guild.upsertCommand(command("setprefix")).queue(
                ignored -> plugin.getLogger().info("Upserted Prefixer Discord command setprefix to guild " + discord.guildId()),
                failure -> plugin.getLogger().warning("Failed to register Discord command setprefix: " + failure.getMessage()));
            guild.upsertCommand(command("setsuffix")).queue(
                ignored -> plugin.getLogger().info("Upserted Prefixer Discord command setsuffix to guild " + discord.guildId()),
                failure -> plugin.getLogger().warning("Failed to register Discord command setsuffix: " + failure.getMessage()));
        } else {
            readyJda.upsertCommand(command("setprefix")).queue(
                ignored -> plugin.getLogger().info("Upserted Prefixer Discord command setprefix globally; Discord may take up to one hour to show it."),
                failure -> plugin.getLogger().warning("Failed to register Discord command setprefix: " + failure.getMessage()));
            readyJda.upsertCommand(command("setsuffix")).queue(
                ignored -> plugin.getLogger().info("Upserted Prefixer Discord command setsuffix globally; Discord may take up to one hour to show it."),
                failure -> plugin.getLogger().warning("Failed to register Discord command setsuffix: " + failure.getMessage()));
        }
    }

    @Override
    public void onStatusChange(@NotNull StatusChangeEvent event) {
        // Asynchronous login failure surfaces here: JDABuilder.build() only throws for
        // synchronous errors, while an invalid token or rejected identify transitions the
        // JDA status to FAILED_TO_LOGIN on a gateway thread instead.
        if (stopped) {
            return;
        }
        if (event.getNewStatus() == JDA.Status.FAILED_TO_LOGIN) {
            plugin.getLogger().warning("Discord bot failed to log in; check the configured token and gateway status.");
        }
    }

    public void stop() {
        stopped = true;
        JDA current = jda;
        jda = null;
        if (current != null) {
            current.removeEventListener(this);
            // Non-blocking shutdown: never wait for gateway teardown on the caller thread.
            current.shutdown();
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
        String playerName = event.getOption("player", (String) null, OptionMapping::getAsString);
        OfflinePlayer target = playerName == null || playerName.isBlank()
            ? null
            : Bukkit.getOfflinePlayerIfCached(playerName);
        if (target == null) {
            event.getHook().sendMessage(plugin.messages().renderPlain("messages.discord-player-not-found", event.getUser(), java.util.Map.of("player", String.valueOf(playerName)))).queue();
            return;
        }

        TagKind kind = event.getName().equals("setprefix") ? TagKind.PREFIX : TagKind.SUFFIX;
        String typeInput = event.getOption("type", "text", option -> option.getAsString());
        TagType type;
        try {
            type = TagType.from(typeInput);
        } catch (IllegalArgumentException ex) {
            event.getHook().sendMessage("Invalid tag type.").queue();
            return;
        }
        String value = event.getOption("value", "", option -> option.getAsString());
        String fallback = event.getOption("fallback", "", option -> option.getAsString());
        OptionMapping shadowOption = event.getOption("shadow");
        String shadow = shadowOption == null ? null : shadowOption.getAsString();

        if (type == TagType.IMAGE) {
            var attachment = event.getOption("image", null, option -> option.getAsAttachment());
            if (attachment != null) {
                if (attachment.getSize() > ImageService.MAX_IMAGE_BYTES) {
                    event.getHook().sendMessage("Image attachment exceeds the 8 MiB limit.").queue();
                    return;
                }
                attachment.getProxy().download().whenComplete((input, error) -> {
                    if (error != null) {
                        event.getHook().sendMessage("Failed to download Discord attachment.").queue();
                        return;
                    }
                    try (InputStream downloaded = input) {
                        Path temp = Files.createTempFile("prefixer-discord-", "-" + attachment.getFileName());
                        try (OutputStream output = Files.newOutputStream(temp)) {
                            copyLimited(downloaded, output);
                        } catch (Exception ex) {
                            Files.deleteIfExists(temp);
                            throw ex;
                        }
                        plugin.imageService().setImageAsyncDiscord(event, target, kind, temp.toString(), fallback, shadow)
                            .whenCompleteAsync((ignored, failure) -> {
                                try {
                                    Files.deleteIfExists(temp);
                                } catch (Exception ex) {
                                    plugin.getLogger().warning("Failed to delete Discord image temporary file: " + ex.getMessage());
                                }
                            });
                    } catch (Exception ex) {
                        event.getHook().sendMessage("Failed to download Discord attachment.").queue();
                    }
                });
                return;
            }
            plugin.imageService().setImageAsyncDiscord(event, target, kind, value, fallback, shadow);
            return;
        }

        plugin.prefixService().setTag(new DiscordCommandSender(event), target, kind, type, value, fallback);
    }

    private void copyLimited(InputStream input, OutputStream output) throws java.io.IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        for (int read; (read = input.read(buffer)) != -1; ) {
            total += read;
            if (total > ImageService.MAX_IMAGE_BYTES) {
                throw new java.io.IOException("Image attachment exceeds the 8 MiB limit");
            }
            output.write(buffer, 0, read);
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
            .addOption(OptionType.STRING, "player", "Target Minecraft player name", true)
            .addOptions(new OptionData(OptionType.STRING, "type", "Tag type", false)
                .addChoice("Text", "text")
                .addChoice("Raw", "raw")
                .addChoice("Image", "image")
                .addChoice("None", "none"))
            .addOption(OptionType.STRING, "value", "Value or image source", false)
            .addOption(OptionType.STRING, "fallback", "Fallback text for image", false)
            .addOption(OptionType.STRING, "shadow", "Image shadow: none, #RRGGBB, or #RRGGBBAA", false)
            .addOption(OptionType.ATTACHMENT, "image", "Discord image attachment", false);
    }
}
