package xyz.waxify.prefixer.service;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.metadata.NodeMetadataKey;
import net.luckperms.api.node.types.ChatMetaNode;
import net.luckperms.api.node.types.PrefixNode;
import net.luckperms.api.node.types.SuffixNode;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.waxify.prefixer.model.StoredTag;
import xyz.waxify.prefixer.model.TagKind;

import java.util.UUID;

public final class LuckPermsService {
    private static final int MANAGED_PRIORITY = Integer.MAX_VALUE;
    private static final NodeMetadataKey<Boolean> MANAGED_KEY = NodeMetadataKey.of("prefixer:managed", Boolean.class);

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
            user.data().add(buildManagedNode(kind, compatibilityValue));
        }
        luckPerms.getUserManager().saveUser(user).join();
        user.getCachedData().metaData().reload().join();
    }

    private void removeManagedNode(User user, TagKind kind, String value) {
        user.data().clear(node -> isManagedNode(node, kind, value));
    }

    private Node buildManagedNode(TagKind kind, String value) {
        if (kind == TagKind.PREFIX) {
            return PrefixNode.builder(value, MANAGED_PRIORITY)
                .withMetadata(MANAGED_KEY, true)
                .build();
        }
        return SuffixNode.builder(value, MANAGED_PRIORITY)
            .withMetadata(MANAGED_KEY, true)
            .build();
    }

    private boolean isManagedNode(Node node, TagKind kind, String previousValue) {
        if (!matchesKind(node, kind)) {
            return false;
        }
        if (node.getMetadata(MANAGED_KEY).orElse(false)) {
            return true;
        }
        if (previousValue == null || previousValue.isBlank() || !(node instanceof ChatMetaNode<?, ?> chatMetaNode)) {
            return false;
        }
        return chatMetaNode.getPriority() == MANAGED_PRIORITY && chatMetaNode.getMetaValue().equals(previousValue);
    }

    private boolean matchesKind(Node node, TagKind kind) {
        return kind == TagKind.PREFIX ? node.getType() == NodeType.PREFIX : node.getType() == NodeType.SUFFIX;
    }

    private User loadUser(UUID playerId) {
        return luckPerms.getUserManager().loadUser(playerId).join();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
