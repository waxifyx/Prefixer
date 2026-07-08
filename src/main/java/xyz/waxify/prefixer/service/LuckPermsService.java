package xyz.waxify.prefixer.service;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.PrefixNode;
import net.luckperms.api.node.types.SuffixNode;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.waxify.prefixer.model.StoredTag;
import xyz.waxify.prefixer.model.TagKind;

import java.util.UUID;

public final class LuckPermsService {
    private static final int MANAGED_PRIORITY = Integer.MAX_VALUE;

    private final JavaPlugin plugin;
    private final LuckPerms luckPerms;

    public LuckPermsService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.luckPerms = LuckPermsProvider.get();
    }

    public String currentValue(UUID playerId, TagKind kind) {
        User user = loadUser(playerId);
        return kind == TagKind.PREFIX
            ? nullToEmpty(user.getCachedData().getMetaData().getPrefix())
            : nullToEmpty(user.getCachedData().getMetaData().getSuffix());
    }

    public void syncCompatibility(UUID playerId, TagKind kind, StoredTag previous, String compatibilityValue) {
        User user = loadUser(playerId);
        removeManagedNode(user, kind, previous == null ? "" : previous.lastLuckPermsValue());
        if (!compatibilityValue.isBlank()) {
            if (kind == TagKind.PREFIX) {
                user.data().add(PrefixNode.builder(compatibilityValue, MANAGED_PRIORITY).build());
            } else {
                user.data().add(SuffixNode.builder(compatibilityValue, MANAGED_PRIORITY).build());
            }
        }
        luckPerms.getUserManager().saveUser(user).join();
    }

    private void removeManagedNode(User user, TagKind kind, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (kind == TagKind.PREFIX) {
            user.data().remove(PrefixNode.builder(value, MANAGED_PRIORITY).build());
        } else {
            user.data().remove(SuffixNode.builder(value, MANAGED_PRIORITY).build());
        }
    }

    private User loadUser(UUID playerId) {
        return luckPerms.getUserManager().loadUser(playerId).join();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
