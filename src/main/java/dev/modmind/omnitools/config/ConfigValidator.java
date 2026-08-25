package dev.modmind.omnitools.config;

import dev.modmind.omnitools.AchievementConfig;
import dev.modmind.omnitools.TitleConfig;
import dev.modmind.omnitools.TitleEffectConfig;
import dev.modmind.omnitools.sidebar.SidebarConfig;
import dev.modmind.omnitools.achievement.AchievementCondition;
import dev.modmind.omnitools.achievement.AllCondition;
import dev.modmind.omnitools.achievement.AnyCondition;
import dev.modmind.omnitools.achievement.NotCondition;
import dev.modmind.omnitools.achievement.StatCondition;
import dev.modmind.omnitools.achievement.SumCondition;

import java.util.HashSet;
import java.util.Set;

/** Cross-module validation performed before publishing a configuration snapshot. */
public final class ConfigValidator {
    private ConfigValidator() {
    }

    public static void validate(OmniToolsConfigSnapshot snapshot) {
        if (snapshot.root() == null || snapshot.rewards() == null || snapshot.shop() == null
                || snapshot.titles() == null || snapshot.titleEffects() == null
                || snapshot.cloudStorage() == null || snapshot.achievements() == null
                || snapshot.commandMenus() == null
                || snapshot.sidebar() == null
                || snapshot.commandPermissions() == null) {
            throw new IllegalArgumentException("omnitools configuration snapshot is incomplete");
        }
        validateSidebar(snapshot.sidebar());
        for (AchievementConfig.AchievementDefinition achievement : snapshot.achievements().achievements()) {
            if (achievement.condition() == null || achievement.requirements().isEmpty()) {
                throw new IllegalArgumentException("achievement " + achievement.id()
                        + " has no validated condition");
            }
            validateCondition(achievement.condition(), achievement.id(), 0);
            if (!containsPositiveStatistic(achievement.condition())) {
                throw new IllegalArgumentException("achievement " + achievement.id()
                        + " must contain at least one positive statistic condition");
            }
        }
        Set<String> effects = new HashSet<>();
        for (TitleEffectConfig.EffectDefinition definition : snapshot.titleEffects().definitions()) {
            effects.add(definition.id());
            if (definition.type() == TitleEffectConfig.EffectType.PERMISSION
                    && !isAllowedPermission(definition.permission())) {
                throw new IllegalArgumentException("title effect permission is not allowed: " + definition.permission());
            }
        }
        if (snapshot.enabled(ModuleId.TITLE_EFFECTS)) {
            for (TitleConfig.TitleDefinition title : snapshot.titles().definitions()) {
                for (String effectId : title.effects()) {
                    if (!effects.contains(effectId)) {
                        throw new IllegalArgumentException("title " + title.id()
                                + " references unknown effect " + effectId);
                    }
                }
            }
        }
        if (snapshot.enabled(ModuleId.TITLE_EFFECTS) && !snapshot.enabled(ModuleId.TITLES)
                && !snapshot.titleEffects().definitions().isEmpty()) {
            throw new IllegalArgumentException("title_effects requires titles to be enabled");
        }
        if (snapshot.enabled(ModuleId.ACHIEVEMENTS) && snapshot.enabled(ModuleId.TITLES)) {
            for (AchievementConfig.AchievementDefinition achievement : snapshot.achievements().achievements()) {
                for (String titleId : achievement.rewards().titles()) {
                    if (snapshot.titles().definition(titleId).isEmpty()) {
                        throw new IllegalArgumentException("achievement " + achievement.id()
                                + " references unknown title " + titleId);
                    }
                }
            }
        }
        if (snapshot.enabled(ModuleId.CLOUD_STORAGE) && !snapshot.enabled(ModuleId.PERMISSIONS)) {
            // The built-in cloud_storage atom and administrator bypass are always available.
        }
    }

    private static void validateSidebar(SidebarConfig sidebar) {
        if (sidebar.refreshIntervalTicks() < SidebarConfig.MIN_REFRESH_INTERVAL_TICKS
                || sidebar.refreshIntervalTicks() > SidebarConfig.MAX_REFRESH_INTERVAL_TICKS) {
            throw new IllegalArgumentException("sidebar.refresh_interval_ticks is out of range");
        }
        if (sidebar.lines().size() > SidebarConfig.MAX_LINES) {
            throw new IllegalArgumentException("sidebar.lines contains too many entries");
        }
    }

    private static void validateCondition(AchievementCondition condition, String achievementId, int depth) {
        if (depth >= 8) {
            throw new IllegalArgumentException("achievement " + achievementId
                    + " condition nesting exceeds 8 levels");
        }
        if (condition instanceof StatCondition stat) {
            if (stat.requirements().isEmpty() || stat.atLeast() < 1L) {
                throw new IllegalArgumentException("achievement " + achievementId
                        + " contains an invalid stat condition");
            }
            return;
        }
        if (condition instanceof SumCondition sum) {
            if (sum.requirements().isEmpty() || sum.atLeast() < 1L || sum.unit() == null) {
                throw new IllegalArgumentException("achievement " + achievementId
                        + " contains an invalid sum condition");
            }
            return;
        }
        if (condition instanceof AllCondition all) {
            if (all.children().isEmpty()) {
                throw new IllegalArgumentException("achievement " + achievementId
                        + " contains an empty all condition");
            }
            for (AchievementCondition child : all.children()) {
                validateCondition(child, achievementId, depth + 1);
            }
            return;
        }
        if (condition instanceof AnyCondition any) {
            if (any.children().isEmpty()) {
                throw new IllegalArgumentException("achievement " + achievementId
                        + " contains an empty any condition");
            }
            for (AchievementCondition child : any.children()) {
                validateCondition(child, achievementId, depth + 1);
            }
            return;
        }
        if (condition instanceof NotCondition not) {
            validateCondition(not.child(), achievementId, depth + 1);
            return;
        }
        throw new IllegalArgumentException("achievement " + achievementId + " contains unknown condition type");
    }

    private static boolean isAllowedPermission(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String permission = value.trim().toLowerCase(java.util.Locale.ROOT);
        return permission.equals("omnitools:cloud_storage") || permission.startsWith("omnitools:command.");
    }

    private static boolean containsPositiveStatistic(AchievementCondition condition) {
        if (condition instanceof StatCondition || condition instanceof SumCondition) {
            return true;
        }
        if (condition instanceof AllCondition all) {
            return all.children().stream().anyMatch(ConfigValidator::containsPositiveStatistic);
        }
        if (condition instanceof AnyCondition any) {
            return any.children().stream().anyMatch(ConfigValidator::containsPositiveStatistic);
        }
        // A negated cumulative statistic is not a positive progress source and cannot
        // be the only root condition of an achievement.
        if (condition instanceof NotCondition) {
            return false;
        }
        return false;
    }
}
