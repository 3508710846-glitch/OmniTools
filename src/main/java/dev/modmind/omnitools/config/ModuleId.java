package dev.modmind.omnitools.config;

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
}
