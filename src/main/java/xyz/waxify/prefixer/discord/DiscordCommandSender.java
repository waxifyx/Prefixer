package xyz.waxify.prefixer.discord;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

public final class DiscordCommandSender implements CommandSender {
    private final SlashCommandInteractionEvent event;

    public DiscordCommandSender(SlashCommandInteractionEvent event) {
        this.event = event;
    }

    public void sendPlainMessage(@NotNull String message) {
        event.getHook().sendMessage(message).queue();
        org.bukkit.Bukkit.getLogger().info("[Prefixer/Discord] " + event.getUser().getName() + ": " + message);
    }

    @Override
    public void sendMessage(@NotNull String message) {
        sendPlainMessage(message);
    }

    @Override
    public void sendMessage(@NotNull String... messages) {
        for (String message : messages) {
            sendMessage(message);
        }
    }

    @Override
    public void sendMessage(@Nullable UUID sender, @NotNull String message) {
        sendMessage(message);
    }

    @Override
    public void sendMessage(@Nullable UUID sender, @NotNull String... messages) {
        sendMessage(messages);
    }

    @Override
    public @NotNull Server getServer() {
        return org.bukkit.Bukkit.getServer();
    }

    @Override
    public @NotNull String getName() {
        return event.getUser().getName();
    }

    @Override
    public boolean isPermissionSet(@NotNull String name) {
        return false;
    }

    @Override
    public boolean isPermissionSet(@NotNull Permission perm) {
        return false;
    }

    @Override
    public boolean hasPermission(@NotNull String name) {
        return false;
    }

    @Override
    public boolean hasPermission(@NotNull Permission perm) {
        return false;
    }

    @Override
    public @NotNull PermissionAttachment addAttachment(@NotNull Plugin plugin, @NotNull String name, boolean value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull PermissionAttachment addAttachment(@NotNull Plugin plugin) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable PermissionAttachment addAttachment(@NotNull Plugin plugin, @NotNull String name, boolean value, int ticks) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable PermissionAttachment addAttachment(@NotNull Plugin plugin, int ticks) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeAttachment(@NotNull PermissionAttachment attachment) {
    }

    @Override
    public void recalculatePermissions() {
    }

    @Override
    public @NotNull Set<PermissionAttachmentInfo> getEffectivePermissions() {
        return Collections.emptySet();
    }

    @Override
    public boolean isOp() {
        return false;
    }

    @Override
    public void setOp(boolean value) {
    }

    @Override
    public @NotNull net.kyori.adventure.text.Component name() {
        return net.kyori.adventure.text.Component.text(getName());
    }

    @Override
    public @NotNull Spigot spigot() {
        return new Spigot();
    }
}
