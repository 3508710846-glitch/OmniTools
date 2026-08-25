package dev.modmind.omnitools.permissions;

import java.util.Locale;
import java.util.Optional;

/** Canonical command actions. Aliases must always use the same action. */
public enum CommandAction {
    CHECKIN_OPEN("checkin.open", CommandRole.PLAYER),
    ONLINE_OPEN("online.open", CommandRole.PLAYER),
    SHOP_OPEN("shop.open", CommandRole.PLAYER),
    TITLE_OPEN("title.open", CommandRole.PLAYER),
    ACHIEVEMENTS_OPEN("achievements.open", CommandRole.PLAYER),
    STORAGE_OPEN("storage.open", CommandRole.ADMIN),
    CURRENCY_BALANCE_SELF("currency.balance.self", CommandRole.PLAYER),
    CURRENCY_BALANCE_OTHER("currency.balance.other", CommandRole.ADMIN),
    CURRENCY_ADD("currency.add", CommandRole.ADMIN),
    CURRENCY_REMOVE("currency.remove", CommandRole.ADMIN),
    CHECKIN_CLEAR("checkin.clear", CommandRole.ADMIN),
    TITLE_GRANT("title.grant", CommandRole.ADMIN),
    TITLE_REVOKE("title.revoke", CommandRole.ADMIN),
    CONFIG_RELOAD("config.reload", CommandRole.ADMIN),
    DIAGNOSE("diagnose", CommandRole.ADMIN),
    COMMAND_MENU_OPEN("command_menu.open", CommandRole.PLAYER),
    COMMAND_MENU_CLOSE("command_menu.close", CommandRole.PLAYER),
    SIDEBAR_TOGGLE("sidebar.toggle", CommandRole.PLAYER),
    SIDEBAR_STATUS("sidebar.status", CommandRole.PLAYER),
    REWARDS_RETRY("rewards.retry", CommandRole.PLAYER),
    REWARDS_ADMIN("rewards.admin", CommandRole.ADMIN);

    private final String id;
    private final CommandRole defaultRole;

    CommandAction(String id, CommandRole defaultRole) {
        this.id = id;
        this.defaultRole = defaultRole;
    }

    public String id() {
        return id;
    }

    public CommandRole defaultRole() {
        return defaultRole;
    }

    public static Optional<CommandAction> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (CommandAction action : values()) {
            if (action.id.equals(normalized)) {
                return Optional.of(action);
            }
        }
        return Optional.empty();
    }
}
