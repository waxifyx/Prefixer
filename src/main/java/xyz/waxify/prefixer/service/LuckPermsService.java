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
import java.util.concurrent.CompletableFuture;

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
        User user = luckPerms.getUserManager().getUser(playerId);
        if (user == null) {
            return "";
        }
        return kind == TagKind.PREFIX
            ? nullToEmpty(user.getCachedData().getMetaData().getPrefix())
            : nullToEmpty(user.getCachedData().getMetaData().getSuffix());
    }

    public CompletableFuture<Void> syncCompatibility(UUID playerId, TagKind kind, StoredTag previous, String compatibilityValue) {
        return luckPerms.getUserManager().loadUser(playerId).thenCompose(user -> {
            removeManagedNode(user, kind, previous == null ? "" : previous.lastLuckPermsValue());
            if (!compatibilityValue.isBlank()) {
                user.data().add(buildManagedNode(kind, compatibilityValue));
            }
            return luckPerms.getUserManager().saveUser(user)
                .thenCompose(ignored -> user.getCachedData().metaData().reload()
                    .exceptionally(failure -> {
                        plugin.getLogger().warning("LuckPerms metadata refresh failed: " + failure.getMessage());
                        return null;
                    }));
        });
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

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
