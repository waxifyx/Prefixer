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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class DiscordBot extends ListenerAdapter {
    private final Prefixer plugin;
    private final Object lifecycleLock = new Object();
    private JDA jda;
    private boolean stopped;
    private boolean refreshScheduled;
    private ScheduledExecutorService refreshScheduler;
    private ScheduledFuture<?> refreshTask;

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
        synchronized (lifecycleLock) {
            stopped = false;
        }
        final JDA built;
        final JDA stale;
        try {
            built = JDABuilder.createLight(discord.token())
                .addEventListeners(this)
                .build();
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Failed to start Discord bot: " + ex.getMessage());
            return;
        }
        synchronized (lifecycleLock) {
            if (stopped) {
                stale = built;
            } else {
                this.jda = built;
                stale = null;
            }
        }
        if (stale != null) {
            stale.removeEventListener(this);
            // Non-blocking shutdown: never wait for gateway teardown on the caller thread.
            stale.shutdown();
        }
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        JDA readyJda = event.getJDA();
        PrefixerConfig.DiscordSettings discord = plugin.pluginConfig().discord();
        if (!discord.configured()) {
            return;
        }
        synchronized (lifecycleLock) {
            if (stopped) {
                return;
            }
            registerCommands(readyJda);
            scheduleRefreshLocked();
        }
    }

    private void registerCommands(JDA api) {
        PrefixerConfig.DiscordSettings discord = plugin.pluginConfig().discord();
        if (!discord.configured()) {
            return;
        }
        if (!discord.guildId().isBlank()) {
            var guild = api.getGuildById(discord.guildId());
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
            api.upsertCommand(command("setprefix")).queue(
                ignored -> plugin.getLogger().info("Upserted Prefixer Discord command setprefix globally; Discord may take up to one hour to show it."),
                failure -> plugin.getLogger().warning("Failed to register Discord command setprefix: " + failure.getMessage()));
            api.upsertCommand(command("setsuffix")).queue(
                ignored -> plugin.getLogger().info("Upserted Prefixer Discord command setsuffix globally; Discord may take up to one hour to show it."),
                failure -> plugin.getLogger().warning("Failed to register Discord command setsuffix: " + failure.getMessage()));
        }
    }

    // Caller must hold lifecycleLock.
    private void scheduleRefreshLocked() {
        if (stopped) {
            return;
        }
        if (!plugin.pluginConfig().discord().configured()) {
            return;
        }
        if (refreshScheduled) {
            return;
        }
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Prefixer-Discord-Commands");
            thread.setDaemon(true);
            return thread;
        });
        refreshScheduler = scheduler;
        refreshScheduled = true;
        try {
            refreshTask = scheduler.scheduleAtFixedRate(() -> {
                try {
                    // Serialize submission with stop so a stale tick cannot enqueue after shutdown.
                    synchronized (lifecycleLock) {
                        if (stopped) {
                            return;
                        }
                        if (jda == null) {
                            return;
                        }
                        registerCommands(jda);
                    }
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning("Failed to refresh Discord commands: " + ex.getMessage());
                }
            }, 1, 1, TimeUnit.HOURS);
        } catch (RuntimeException ex) {
            refreshTask = null;
            refreshScheduler = null;
            refreshScheduled = false;
            scheduler.shutdownNow();
            plugin.getLogger().warning("Failed to schedule Discord command refresh: " + ex.getMessage());
        }
    }

    @Override
    public void onStatusChange(@NotNull StatusChangeEvent event) {
        // Asynchronous login failure surfaces here: JDABuilder.build() only throws for
        // synchronous errors, while an invalid token or rejected identify transitions the
        // JDA status to FAILED_TO_LOGIN on a gateway thread instead.
        synchronized (lifecycleLock) {
            if (stopped) {
                return;
            }
        }
        if (event.getNewStatus() == JDA.Status.FAILED_TO_LOGIN) {
            plugin.getLogger().warning("Discord bot failed to log in; check the configured token and gateway status.");
        }
    }

    public void stop() {
        final ScheduledFuture<?> task;
        final ScheduledExecutorService scheduler;
        final JDA current;
        synchronized (lifecycleLock) {
            stopped = true;
            task = refreshTask;
            refreshTask = null;
            scheduler = refreshScheduler;
            refreshScheduler = null;
            refreshScheduled = false;
            current = jda;
            jda = null;
        }
        if (task != null) {
            task.cancel(false);
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
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
