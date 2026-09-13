package dev.modmind.omnitools;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.entitlement.TimedEntitlement;

import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;

/** Read-only Placeholder API values backed by the current server state. */
public final class OmniToolsPlaceholderResolver {
    private static final Set<String> CANONICAL_SKILLS = Set.of(
            "mining", "woodcutting", "herbalism", "excavation", "swords",
            "axes", "archery", "acrobatics", "repair", "alchemy");
    public static final Set<String> IDS = buildIds();

    private static Set<String> buildIds() {
        Set<String> ids = new LinkedHashSet<>(Set.of(
            "balance", "balance_formatted", "checkin_today", "checkin_today_rank",
            "checkin_total_days", "checkin_streak_days", "checkin_month_days", "online_today_seconds",
            "online_today_minutes", "online_today_hms", "title_id", "title", "title_plain",
            "title_effects_enabled", "title_remaining_days", "title_remaining_hours", "title_remaining_hms",
            "title_is_temporary", "title_is_equipped", "achievements_unlocked", "achievements_claimed",
            "achievements_total", "skill_engine", "skill_power_level"));
        for (String skill : CANONICAL_SKILLS) {
            ids.add("skill_level_" + skill);
            ids.add("skill_xp_" + skill);
            ids.add("skill_xp_total_" + skill);
            ids.add("skill_ability_level_" + skill);
            ids.add("skill_ability_cooldown_" + skill);
            ids.add("skill_ability_active_" + skill);
        }
        return Set.copyOf(ids);
    }

    private OmniToolsPlaceholderResolver() {
    }

    /** Supports built-ins and dynamic skill ids used by TextTemplateRenderer. */
    public static boolean supports(String argument) {
        String id = argument == null ? "" : argument.trim().toLowerCase(Locale.ROOT);
        if (id.startsWith("omnitools:")) id = id.substring("omnitools:".length());
        if (IDS.contains(id)) return true;
        return skillPlaceholder(id) != null;
    }

    public static Component resolve(ServerPlayer player, String argument) {
        String id = argument == null ? "" : argument.trim().toLowerCase(Locale.ROOT);
        if (player == null) {
            return fallback(id);
        }
        Component skillValue = skillPlaceholderValue(player, id);
        if (skillValue != null) return skillValue;
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
            case "skill_engine" -> skillEngineValue(player);
            case "skill_power_level" -> skillPowerValue(player);
            default -> fallback(id);
        };
    }

    private static String skillPlaceholder(String id) {
        String[] prefixes = {"skill_level_", "skill_xp_", "skill_xp_total_",
                "skill_ability_level_", "skill_ability_cooldown_", "skill_ability_active_"};
        for (String prefix : prefixes) {
            if (id.startsWith(prefix) && id.length() > prefix.length()) return prefix;
        }
        return null;
    }

    private static Component skillPlaceholderValue(ServerPlayer player, String id) {
        String prefix = skillPlaceholder(id);
        if (prefix == null || !ModMindEntry.isModuleEnabled(ModuleId.SKILLS)) return null;
        String skill = id.substring(prefix.length());
        if (ModMindEntry.skillTreeService().config().tree(skill).isEmpty()) return fallback(id);
        var service = ModMindEntry.skillTreeService();
        var progress = service.progress(player, skill);
        return switch (prefix) {
            case "skill_level_" -> value(Integer.toString(progress.level()));
            case "skill_xp_" -> value(Long.toString(progress.currentXp()));
            case "skill_xp_total_" -> value(Long.toString(progress.totalXp()));
            case "skill_ability_level_" -> value(Integer.toString(service.abilitySnapshot(player, skill).abilityLevel()));
            case "skill_ability_cooldown_" -> value(Long.toString(service.getAbilityCooldown(player, skill)));
            case "skill_ability_active_" -> value(Long.toString(service.abilitySnapshot(player, skill).activeRemainingSeconds()));
            default -> fallback(id);
        };
    }

    private static Component skillEngineValue(ServerPlayer player) {
        if (!ModMindEntry.isModuleEnabled(ModuleId.SKILLS)) return fallback("skill_engine");
        return value(ModMindEntry.skillTreeService().engine().serializedName());
    }

    private static Component skillPowerValue(ServerPlayer player) {
        if (!ModMindEntry.isModuleEnabled(ModuleId.SKILLS)) return fallback("skill_power_level");
        return value(Integer.toString(ModMindEntry.skillTreeService().getPowerLevel(player)));
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
