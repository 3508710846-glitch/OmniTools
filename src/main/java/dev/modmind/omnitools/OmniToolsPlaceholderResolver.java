package dev.modmind.omnitools;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import dev.modmind.omnitools.config.ModuleId;

import java.util.Locale;
import java.util.Set;

/** Read-only Placeholder API values backed by the current server state. */
public final class OmniToolsPlaceholderResolver {
    public static final Set<String> IDS = Set.of(
            "balance", "balance_formatted", "checkin_today", "checkin_today_rank",
            "checkin_total_days", "checkin_streak_days", "checkin_month_days", "online_today_seconds",
            "online_today_minutes", "online_today_hms", "title_id", "title", "title_plain",
            "title_effects_enabled", "achievements_unlocked", "achievements_claimed", "achievements_total");

    private OmniToolsPlaceholderResolver() {
    }

    public static Component resolve(ServerPlayer player, String argument) {
        String id = argument == null ? "" : argument.trim().toLowerCase(Locale.ROOT);
        if (player == null || !ModMindEntry.configSnapshot().placeholderApiEnabled()) {
            return fallback(id);
        }
        return switch (id) {
            case "balance" -> value(Long.toString(CheckinData.get(player).getBalance(player.getUUID())));
            case "balance_formatted" -> value(String.format(Locale.ROOT, "%,d",
                    CheckinData.get(player).getBalance(player.getUUID())));
            case "checkin_today", "checkin_today_rank", "checkin_total_days", "checkin_streak_days",
                    "checkin_month_days" -> checkinValue(player, id);
            case "online_today_seconds", "online_today_minutes", "online_today_hms" -> onlineValue(player, id);
            case "title_id", "title", "title_plain", "title_effects_enabled" -> titleValue(player, id);
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
            case "online_today_hms" -> value(String.format(Locale.ROOT, "%02d:%02d:%02d",
                    seconds / 3600L, (seconds % 3600L) / 60L, seconds % 60L));
            default -> fallback(id);
        };
    }

    private static Component titleValue(ServerPlayer player, String id) {
        if (!ModMindEntry.isModuleEnabled(ModuleId.TITLES)) {
            return fallback(id);
        }
        var selected = ModMindEntry.titleConfig().selectedTitle(player.getUUID());
        return switch (id) {
            case "title_id" -> value(selected.map(TitleConfig.TitleDefinition::id).orElse(""));
            case "title" -> selected.map(TitleConfig.TitleDefinition::displayComponent).orElseGet(() -> value(""));
            case "title_plain" -> value(selected.map(TitleConfig.TitleDefinition::plainDisplay).orElse(""));
            case "title_effects_enabled" -> value(Boolean.toString(
                    ModMindEntry.isModuleEnabled(ModuleId.TITLE_EFFECTS)
                            && ModMindEntry.titleConfig().effectsEnabled(player.getUUID())));
            default -> fallback(id);
        };
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
            case "checkin_today", "title_effects_enabled" -> value("false");
            case "title_id", "title", "title_plain", "online_today_hms" -> value(id.equals("online_today_hms")
                    ? "00:00:00" : "");
            default -> value("0");
        };
    }

    private static Component value(String value) {
        return Component.literal(value);
    }
}
