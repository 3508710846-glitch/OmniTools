package dev.modmind.omnitools.permissions;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.Locale;

/** Configurable minimum Minecraft command role. */
public enum CommandRole {
    PLAYER(0),
    MODERATOR(1),
    ADMIN(2),
    OWNER(4);

    private final int level;

    CommandRole(int level) {
        this.level = level;
    }

    public int level() {
        return level;
    }

    public boolean allows(CommandSourceStack source) {
        return switch (this) {
            case PLAYER -> Commands.hasPermission(Commands.LEVEL_ALL).test(source);
            case MODERATOR -> Commands.hasPermission(Commands.LEVEL_MODERATORS).test(source);
            case ADMIN -> Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source);
            case OWNER -> Commands.hasPermission(Commands.LEVEL_OWNERS).test(source);
        };
    }

    public static CommandRole parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("command permission role is missing");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("command permission role is missing");
        }
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unknown command permission role: " + value, exception);
        }
    }
}
