package dev.modmind.omnitools.skills;

import java.util.Locale;

/**
 * Independent mcMMO behaviour contract used by {@link SkillTreeService}.
 *
 * This class contains progression and ability rules only; it does not copy or link to Bukkit or
 * Spigot mcMMO code.  World mutations remain in the server-thread service, which keeps the
 * compatibility layer usable with Fabric and with the existing reward/title integrations.
 */
public final class McmmoSkillModule {
    public static final int LEVEL_CAP = SkillTreeConfig.MCMOO_MAX_LEVEL;
    public static final int FIRST_ABILITY_LEVEL = 100;
    public static final int[] ABILITY_MILESTONES = {100, 250, 500, 750, 1000};
    private final SkillTreeConfig config;

    public McmmoSkillModule(SkillTreeConfig config) {
        this.config = config == null ? SkillTreeConfig.empty() : config;
    }

    public SkillTreeConfig config() {
        return config;
    }

    public boolean enabled() {
        return config.settings().engine() == SkillEngine.MCMOO;
    }

    public int levelCap() {
        return Math.min(LEVEL_CAP, config.settings().maxLevel());
    }

    /** Returns the five mcMMO-style ability stages unlocked by a profession level. */
    public int abilityTier(int professionLevel) {
        int level = Math.max(0, Math.min(levelCap(), professionLevel));
        int tier = 0;
        for (int milestone : ABILITY_MILESTONES) {
            if (level >= milestone) tier++;
            else break;
        }
        return tier;
    }

    /** Maps the five milestones to a 1..10 active/passive ability rank. */
    public int abilityLevel(int professionLevel) {
        return switch (abilityTier(professionLevel)) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 3;
            case 3 -> 5;
            case 4 -> 7;
            default -> 10;
        };
    }

    public boolean abilityUnlocked(int professionLevel) {
        return professionLevel >= FIRST_ABILITY_LEVEL;
    }

    /** Canonical names used by the compatibility layer and by saved progress keys. */
    public static String canonicalSkill(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return LegacySkillAdapter.canonical(normalized);
    }

    public boolean knownSkill(String value) {
        String canonical = canonicalSkill(value);
        return config.tree(canonical).isPresent();
    }

    public static boolean isCollectionSkill(String skillId) {
        return switch (canonicalSkill(skillId)) {
            case "mining", "woodcutting", "herbalism", "excavation" -> true;
            default -> false;
        };
    }

    public static boolean isCombatSkill(String skillId) {
        return switch (canonicalSkill(skillId)) {
            case "swords", "axes", "archery", "acrobatics" -> true;
            default -> false;
        };
    }

    public static boolean isProductionSkill(String skillId) {
        return switch (canonicalSkill(skillId)) {
            case "repair", "alchemy" -> true;
            default -> false;
        };
    }

    public record AbilitySnapshot(String skillId, int professionLevel, int tier, int abilityLevel,
                                  long cooldownRemainingSeconds, long activeRemainingSeconds) {
        public AbilitySnapshot {
            skillId = canonicalSkill(skillId);
            professionLevel = Math.max(0, professionLevel);
            tier = Math.max(0, tier);
            abilityLevel = Math.max(0, abilityLevel);
            cooldownRemainingSeconds = Math.max(0L, cooldownRemainingSeconds);
            activeRemainingSeconds = Math.max(0L, activeRemainingSeconds);
        }
    }
}
