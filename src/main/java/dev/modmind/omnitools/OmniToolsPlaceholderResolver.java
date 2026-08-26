package dev.modmind.omnitools;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.entitlement.TimedEntitlement;

import java.util.Locale;
import java.util.Set;

/** Read-only Placeholder API values backed by the current server state. */
public final class OmniToolsPlaceholderResolver {
    public static final Set<String> IDS = Set.of(
            "balance", "balance_formatted", "checkin_today", "checkin_today_rank",
            "checkin_total_days", "checkin_streak_days", "checkin_month_days", "online_today_seconds",
            "online_today_minutes", "online_today_hms", "title_id", "title", "title_plain",
            "title_effects_enabled", "title_remaining_days", "title_remaining_hours", "title_remaining_hms",
            "title_is_temporary", "title_is_equipped", "achievements_unlocked", "achievements_claimed",
            "achievements_total");

    private OmniToolsPlaceholderResolver() {
    }

    public static Component resolve(ServerPlayer player, String argument) {
        String id = argument == null ? "" : argument.trim().toLowerCase(Locale.ROOT);
        if (player == null) {
            return fallback(id);
        }
        return switch (id) {
            case "balance" -> value(Long.toString(CheckinData.get(player).getBalance(player.getUUID())));
            case "balance_formatted" -> value(formatGrouped(CheckinData.get(player).getBalance(player.getUUID())));
            case "checkin_today", "checkin_today_rank", "checkin_total_days", "checkin_streak_days",
                    "checkin_month_days" -> checkinValue(player, id);
            case "online_today_seconds", "online_today_minutes", "online_today_hms" -> onlineValue(player, id);
            case "title_id", "title", "title_plain", "title_effects_enabled", "title_remaining_days",
                    "title_remaining_hours", "title_remaining_hms", "title_is_temporary", "title_is_equipped" ->
                    titleValue(player, id);
            case "achievements_unlocked", "achievements_claimed", "achievements_total" -> achievementValue(player, id);
            default -> fallback(id);
        };
    }

    private static Component checkinValue(ServerPlayer player, String id) {
        if (!ModMindEntry.isModuleEnabled(ModuleId.DAILY_CHECKIN)) {
            return fallback(id);
        }
        CheckinData.PlayerStats stats = CheckinData.get(player).getStats(player.getUUID(),
                CheckinData.today(player.level().getServer()).toEpochDay());
        return switch (id) {
            case "checkin_today" -> value(Boolean.toString(stats.signedToday()));
            case "checkin_today_rank" -> value(stats.signedToday() ? Integer.toString(stats.todayOrdinal()) : "0");
            case "checkin_total_days" -> value(Integer.toString(stats.totalDays()));
            case "checkin_streak_days" -> value(Integer.toString(stats.streakDays()));
            case "checkin_month_days" -> value(Integer.toString(stats.monthlyDays()));
            default -> fallback(id);
        };
    }

    private static Component onlineValue(ServerPlayer player, String id) {
        if (!ModMindEntry.isModuleEnabled(ModuleId.ONLINE_REWARD)) {
            return fallback(id);
        }
        long seconds = Math.max(0L, ModMindEntry.onlineTimeRewardService().getTodayOnlineTime(player) / 1000L);
        return switch (id) {
            case "online_today_seconds" -> value(Long.toString(seconds));
            case "online_today_minutes" -> value(Long.toString(seconds / 60L));
            case "online_today_hms" -> value(formatHms(seconds));
            default -> fallback(id);
        };
    }

    private static Component titleValue(ServerPlayer player, String id) {
        if (!ModMindEntry.isModuleEnabled(ModuleId.TITLES)) {
            return fallback(id);
        }
        return switch (id) {
            case "title_effects_enabled" -> value(Boolean.toString(
                    ModMindEntry.isModuleEnabled(ModuleId.TITLE_EFFECTS)
                            && ModMindEntry.titleConfig().effectsEnabled(player.getUUID())));
            default -> selectedTitleValue(player, id);
        };
    }

    private static Component selectedTitleValue(ServerPlayer player, String id) {
        var selected = ModMindEntry.titleConfig().selectedTitle(player.getUUID());
        String selectedId = selected.map(TitleConfig.TitleDefinition::id).orElse("");
        TimedEntitlement entitlement = selectedId.isEmpty() ? null
                : ModMindEntry.titleConfig().entitlement(player.getUUID(), selectedId).orElse(null);
        return switch (id) {
            case "title_id" -> value(selectedId);
            case "title" -> selected.map(TitleConfig.TitleDefinition::displayComponent).orElseGet(() -> value(""));
            case "title_plain" -> value(selected.map(TitleConfig.TitleDefinition::plainDisplay).orElse(""));
            case "title_remaining_days" -> value(Long.toString(remainingSeconds(entitlement) / 86_400L));
            case "title_remaining_hours" -> value(Long.toString(remainingSeconds(entitlement) / 3_600L));
            case "title_remaining_hms" -> value(formatHms(remainingSeconds(entitlement)));
            case "title_is_temporary" -> value(Boolean.toString(entitlement != null && !entitlement.isPermanent()));
            case "title_is_equipped" -> value(Boolean.toString(!selectedId.isEmpty()));
            default -> fallback(id);
        };
    }

    private static long remainingSeconds(TimedEntitlement entitlement) {
        return entitlement == null || entitlement.isPermanent() ? 0L
                : Math.max(0L, entitlement.remainingActiveTicks() / 20L);
    }

    private static Component achievementValue(ServerPlayer player, String id) {
        if (!ModMindEntry.isModuleEnabled(ModuleId.ACHIEVEMENTS)) {
            return fallback(id);
        }
        return switch (id) {
            case "achievements_unlocked" -> value(Integer.toString(ModMindEntry.achievementService().unlockedCount(player)));
            case "achievements_claimed" -> value(Integer.toString(ModMindEntry.achievementService().claimedCount(player)));
            case "achievements_total" -> value(Integer.toString(
                    ModMindEntry.achievementService().config().achievements().size()));
            default -> fallback(id);
        };
    }

    private static Component fallback(String id) {
        return switch (id) {
            case "checkin_today", "title_effects_enabled", "title_is_temporary", "title_is_equipped" ->
                    value("false");
            case "title_id", "title", "title_plain", "online_today_hms", "title_remaining_hms" ->
                    value(id.endsWith("hms") ? "00:00:00" : "");
            default -> value("0");
        };
    }

    private static Component value(String value) {
        return Component.literal(value);
    }

    private static String formatGrouped(long value) {
        String digits = Long.toString(Math.max(0L, value));
        int firstGroup = digits.length() % 3;
        StringBuilder result = new StringBuilder(digits.length() + (digits.length() - 1) / 3);
        for (int index = 0; index < digits.length(); index++) {
            if (index > 0 && (index - firstGroup) % 3 == 0) {
                result.append(',');
            }
            result.append(digits.charAt(index));
        }
        return result.toString();
    }

    private static String formatHms(long seconds) {
        long hours = seconds / 3_600L;
        long minutes = (seconds % 3_600L) / 60L;
        long remainingSeconds = seconds % 60L;
        StringBuilder result = new StringBuilder(8);
        appendPadded(result, hours).append(':');
        appendPadded(result, minutes).append(':');
        appendPadded(result, remainingSeconds);
        return result.toString();
    }

    private static StringBuilder appendPadded(StringBuilder builder, long value) {
        if (value < 10L) {
            builder.append('0');
        }
        return builder.append(value);
    }
}
