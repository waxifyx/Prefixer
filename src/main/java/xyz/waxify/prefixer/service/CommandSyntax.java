package xyz.waxify.prefixer.service;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import xyz.waxify.prefixer.model.ParsedCommand;
import xyz.waxify.prefixer.model.TagType;

import java.util.ArrayList;
import java.util.List;

public final class CommandSyntax {
    private CommandSyntax() {
    }

    public static ParsedCommand parse(CommandSender sender, Player self, String input) {
        List<Token> tokens = tokenize(input);
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("missing");
        }

        Player target = self;
        int index = 0;

        if (!tokens.getFirst().quoted() && tokens.size() >= 1 && !isType(tokens.getFirst().value())) {
            Player resolved = resolvePlayer(sender, tokens.getFirst().value());
            if (resolved != null) {
                target = resolved;
                index = 1;
            }
        }

        TagType type = TagType.TEXT;
        if (index < tokens.size() && !tokens.get(index).quoted() && isType(tokens.get(index).value())) {
            type = TagType.from(tokens.get(index).value());
            index++;
        }

        if (type == TagType.NONE) {
            if (index != tokens.size()) {
                throw new IllegalArgumentException("usage");
            }
            return new ParsedCommand(target, type, "", "", null);
        }

        if (type == TagType.IMAGE) {
            if (tokens.size() - index < 3) {
                throw new IllegalArgumentException("image");
            }
            String source = tokens.get(index).value();
            int nextIndex = index + 1;
            if (!tokens.get(nextIndex).quoted() && tokens.get(nextIndex).value().equalsIgnoreCase("shadow")) {
                if (tokens.size() - index < 5) {
                    throw new IllegalArgumentException("image");
                }
                if (!ImageShadow.isValidSpec(tokens.get(index + 2).value())) {
                    throw new IllegalArgumentException("invalid-shadow");
                }
                if (tokens.get(index + 3).quoted() || !tokens.get(index + 3).value().equalsIgnoreCase("fallback")) {
                    throw new IllegalArgumentException("image");
                }
                return new ParsedCommand(target, type, source, fallback, ImageShadow.normalize(tokens.get(index + 2).value()));
                String fallback = join(tokens, index + 4);
            }

            if (tokens.get(nextIndex).quoted() || !tokens.get(nextIndex).value().equalsIgnoreCase("fallback")) {
                throw new IllegalArgumentException("image");
            }
            return new ParsedCommand(target, type, source, fallback, null);
            String fallback = join(tokens, index + 2);
        }

        if (index >= tokens.size()) {
            throw new IllegalArgumentException("missing");
        }

        StringBuilder value = new StringBuilder();
        for (int i = index; i < tokens.size(); i++) {
            if (i > index) {
                value.append(' ');
            }
            value.append(tokens.get(i).value());
        }
        return new ParsedCommand(target, type, value.toString(), "", null);
    }

    private static String join(List<Token> tokens, int start) {
        if (start >= tokens.size()) {
            throw new IllegalArgumentException("image");
        }
        StringBuilder value = new StringBuilder();
        for (int i = start; i < tokens.size(); i++) {
            if (i > start) {
                value.append(' ');
            }
            value.append(tokens.get(i).value());
        }
        return value.toString();
    }

    private static boolean isType(String token) {
        try {
            TagType.from(token);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Player resolvePlayer(CommandSender sender, String token) {
        Player exact = Bukkit.getPlayerExact(token);
        if (exact != null) {
            return exact;
        }
        if (token.startsWith("@")) {
            try {
                List<Entity> entities = Bukkit.selectEntities(sender, token);
                if (entities.size() == 1 && entities.getFirst() instanceof Player player) {
                    return player;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }

    private static List<Token> tokenize(String input) {
        List<Token> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean tokenQuoted = false;
        char quote = 0;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (quoted) {
                if (c == quote) {
                    quoted = false;
                } else if (c == '\\' && i + 1 < input.length() && input.charAt(i + 1) == quote) {
                    current.append(quote);
                    i++;
                } else {
                    current.append(c);
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quoted = true;
                tokenQuoted = true;
                quote = c;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (!current.isEmpty() || tokenQuoted) {
                    tokens.add(new Token(current.toString(), tokenQuoted));
                    current.setLength(0);
                    tokenQuoted = false;
                }
                continue;
            }
            current.append(c);
        }

        if (quoted) {
            throw new IllegalArgumentException("usage");
        }
        if (!current.isEmpty() || tokenQuoted) {
            tokens.add(new Token(current.toString(), tokenQuoted));
        }
        return tokens;
    }

    private record Token(String value, boolean quoted) {
    }
}
