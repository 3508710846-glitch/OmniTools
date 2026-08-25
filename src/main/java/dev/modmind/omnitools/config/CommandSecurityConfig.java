package dev.modmind.omnitools.config;

import com.google.gson.JsonParseException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Shared restrictions for configurable command-menu and reward command execution. */
public record CommandSecurityConfig(List<String> allowedRoots, int maxCommandLength, int cooldownTicks) {
    public static final String PERMISSIVE_ROOT = "*";
    public static final int DEFAULT_MAX_COMMAND_LENGTH = 1_024;
    public static final int DEFAULT_COOLDOWN_TICKS = 10;
    public static final int LEGACY_COOLDOWN_TICKS = 0;
    private static final Pattern ROOT = Pattern.compile("[a-z0-9:_-]{1,64}");

    public CommandSecurityConfig {
        if (maxCommandLength < 1 || maxCommandLength > 16_384) {
            throw new JsonParseException("global.command_security.max_command_length must be between 1 and 16384");
        }
        if (cooldownTicks < 0 || cooldownTicks > 72_000) {
            throw new JsonParseException("global.command_security.cooldown_ticks must be between 0 and 72000");
        }
        Set<String> roots = new LinkedHashSet<>();
        for (String root : allowedRoots == null ? List.<String>of() : allowedRoots) {
            String normalized = root == null ? "" : root.trim().toLowerCase(Locale.ROOT);
            if (!(normalized.equals(PERMISSIVE_ROOT) || ROOT.matcher(normalized).matches())) {
                throw new JsonParseException("global.command_security.allowed_roots contains an invalid root: " + root);
            }
            roots.add(normalized);
        }
        allowedRoots = List.copyOf(roots);
    }

    /** New installations permit no configurable commands until the owner explicitly lists roots. */
    public static CommandSecurityConfig defaults() {
        return new CommandSecurityConfig(List.of(), DEFAULT_MAX_COMMAND_LENGTH, DEFAULT_COOLDOWN_TICKS);
    }

    /** Only used by the root migrator to preserve an already deployed configuration. */
    public static CommandSecurityConfig legacyCompatible() {
        return new CommandSecurityConfig(List.of(PERMISSIVE_ROOT), DEFAULT_MAX_COMMAND_LENGTH,
                LEGACY_COOLDOWN_TICKS);
    }

    /** A wildcard root preserves legacy behavior but permits every command root. */
    public boolean isPermissive() {
        return allowedRoots.contains(PERMISSIVE_ROOT);
    }

    public boolean allows(String command) {
        if (command == null || command.isBlank() || command.length() > maxCommandLength
                || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
            return false;
        }
        String normalized = command.trim();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        int separator = normalized.indexOf(' ');
        String root = (separator < 0 ? normalized : normalized.substring(0, separator)).toLowerCase(Locale.ROOT);
        return isPermissive() || allowedRoots.contains(root);
    }
}
