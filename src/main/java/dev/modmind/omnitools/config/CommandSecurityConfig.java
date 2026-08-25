package dev.modmind.omnitools.config;

import com.google.gson.JsonParseException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Shared restrictions for configurable command-menu and reward command execution. */
public record CommandSecurityConfig(List<String> allowedRoots, int maxCommandLength, int cooldownTicks) {
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
            if (!(normalized.equals("*") || ROOT.matcher(normalized).matches())) {
                throw new JsonParseException("global.command_security.allowed_roots contains an invalid root: " + root);
            }
            roots.add(normalized);
        }
        allowedRoots = List.copyOf(roots);
    }

    /** New installations permit no configurable commands until the owner explicitly lists roots. */
    public static CommandSecurityConfig defaults() {
        return new CommandSecurityConfig(List.of(), 1_024, 0);
    }

    /** Only used by the root migrator to preserve an already deployed configuration. */
    public static CommandSecurityConfig legacyCompatible() {
        return new CommandSecurityConfig(List.of("*"), 1_024, 0);
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
        return allowedRoots.contains("*") || allowedRoots.contains(root);
    }
}
