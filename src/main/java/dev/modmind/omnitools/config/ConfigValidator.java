package dev.modmind.omnitools.config;

import dev.modmind.omnitools.AchievementConfig;
import dev.modmind.omnitools.TitleConfig;
import dev.modmind.omnitools.TitleEffectConfig;

import java.util.HashSet;
import java.util.Set;

/** Cross-module validation performed before publishing a configuration snapshot. */
public final class ConfigValidator {
    private ConfigValidator() {
    }

    public static void validate(OmniToolsConfigSnapshot snapshot) {
        if (snapshot.root() == null || snapshot.rewards() == null || snapshot.shop() == null
                || snapshot.titles() == null || snapshot.titleEffects() == null
                || snapshot.cloudStorage() == null || snapshot.achievements() == null) {
            throw new IllegalArgumentException("omnitools configuration snapshot is incomplete");
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

    private static boolean isAllowedPermission(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String permission = value.trim().toLowerCase(java.util.Locale.ROOT);
        return permission.equals("omnitools:cloud_storage") || permission.startsWith("omnitools:command.");
    }
}
