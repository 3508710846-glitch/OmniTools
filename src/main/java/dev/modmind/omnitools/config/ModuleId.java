package dev.modmind.omnitools.config;

import java.util.Locale;
import java.util.Optional;

/** Stable identifiers used by the root configuration and runtime gates. */
public enum ModuleId {
    DAILY_CHECKIN("daily_checkin"),
    CDK("cdk"),
    ONLINE_REWARD("online_reward"),
    SHOP("shop"),
    TITLES("titles"),
    TITLE_EFFECTS("title_effects"),
    ACHIEVEMENTS("achievements"),
    CLOUD_STORAGE("cloud_storage"),
    PERMISSIONS("permissions"),
    COMMAND_MENU("command_menu"),
    SIDEBAR("sidebar");

    private final String id;

    ModuleId(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static Optional<ModuleId> find(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ModuleId module : values()) {
            if (module.id.equals(normalized)) {
                return Optional.of(module);
            }
        }
        return Optional.empty();
    }
}
