package xyz.waxify.prefixer.service;

import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.PermissionAttachmentInfo;
import xyz.waxify.prefixer.config.PrefixerConfig;
import xyz.waxify.prefixer.model.TagKind;
import xyz.waxify.prefixer.model.TagType;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PermissionService {
    private static final Pattern COOLDOWN_PATTERN = Pattern.compile("^prefixer\\.(setprefix|setsuffix)\\.cooldown\\.(\\d+)([smhd])$");
    private static final Pattern MAX_LENGTH_PATTERN = Pattern.compile("^prefixer\\.(setprefix|setsuffix)\\.maxlength\\.(\\d+)$");

    private final PrefixerConfig config;

    public PermissionService(PrefixerConfig config) {
        this.config = config;
    }

    public boolean canSet(CommandSender sender, TagKind kind, TagType type, boolean self) {
        String root = "prefixer." + kind.permissionRoot();
        if (self) {
            return sender.hasPermission(root + ".self")
                || sender.hasPermission(root + ".self." + type.permissionSegment());
        }
        return sender.hasPermission(root + ".others." + type.permissionSegment());
    }

    public Duration effectiveCooldown(CommandSender sender, TagKind kind) {
        String root = "prefixer." + kind.permissionRoot() + ".cooldown";
        if (!(sender instanceof Permissible permissible)) {
            return defaultCooldown(kind);
        }
        if (permissible.hasPermission(root + ".bypass")) {
            return Duration.ZERO;
        }

        Duration best = null;
        for (PermissionAttachmentInfo info : permissible.getEffectivePermissions()) {
            if (!info.getValue()) {
                continue;
            }
            Matcher matcher = COOLDOWN_PATTERN.matcher(info.getPermission());
            if (!matcher.matches() || !matcher.group(1).equals(kind.permissionRoot())) {
                continue;
            }
            Duration candidate = parseDuration(matcher.group(2), matcher.group(3).charAt(0));
            if (best == null || candidate.compareTo(best) < 0) {
                best = candidate;
            }
        }
        return best != null ? best : defaultCooldown(kind);
    }

    public int effectiveMaxLength(CommandSender sender, TagKind kind) {
        String root = "prefixer." + kind.permissionRoot() + ".maxlength";
        if (!(sender instanceof Permissible permissible)) {
            return defaultMaxLength(kind);
        }
        if (permissible.hasPermission(root + ".bypass")) {
            return Integer.MAX_VALUE;
        }

        int best = -1;
        for (PermissionAttachmentInfo info : permissible.getEffectivePermissions()) {
            if (!info.getValue()) {
                continue;
            }
            Matcher matcher = MAX_LENGTH_PATTERN.matcher(info.getPermission());
            if (!matcher.matches() || !matcher.group(1).equals(kind.permissionRoot())) {
                continue;
            }
            best = Math.max(best, Integer.parseInt(matcher.group(2)));
        }
        return best >= 0 ? best : defaultMaxLength(kind);
    }

    private Duration defaultCooldown(TagKind kind) {
        return kind == TagKind.PREFIX ? config.prefix().defaultCooldown() : config.suffix().defaultCooldown();
    }

    private int defaultMaxLength(TagKind kind) {
        return kind == TagKind.PREFIX ? config.prefix().maxLength() : config.suffix().maxLength();
    }

    private Duration parseDuration(String value, char unit) {
        long parsed = Long.parseLong(value);
        return switch (unit) {
            case 's' -> Duration.ofSeconds(parsed);
            case 'm' -> Duration.ofMinutes(parsed);
            case 'h' -> Duration.ofHours(parsed);
            case 'd' -> Duration.ofDays(parsed);
            default -> Duration.ZERO;
        };
    }
}
