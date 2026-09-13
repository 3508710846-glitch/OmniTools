package dev.modmind.omnitools.skills;

import java.util.Locale;
import java.util.Map;

/**
 * Maps the historical professional-tree identifiers to the independent mcMMO skill names.
 * The adapter is deliberately lossy only where the old data has no source information; such
 * data is represented as legacy credit instead of being silently assigned to a random skill.
 */
public final class LegacySkillAdapter {
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("miner", "mining"),
            Map.entry("gathering", "mining"),
            Map.entry("lumberjack", "woodcutting"),
            Map.entry("woodcutter", "woodcutting"),
            Map.entry("farmer", "herbalism"),
            Map.entry("gatherer", "herbalism"),
            Map.entry("excavator", "excavation"),
            Map.entry("warrior", "swords"),
            Map.entry("combat", "swords"),
            Map.entry("guardian", "acrobatics"),
            Map.entry("defense", "acrobatics"),
            Map.entry("hunter", "archery"),
            Map.entry("hunting", "archery"),
            Map.entry("smithing", "repair"),
            Map.entry("crafting", "repair"),
            // The first compatibility release has no separate Support/Fishing trees. Keep their
            // historical progress reachable through the closest bounded core skill instead of
            // silently dropping it; dedicated skills can be added without changing this alias.
            Map.entry("healing", "alchemy"),
            Map.entry("support", "alchemy"),
            Map.entry("exploration", "acrobatics"),
            Map.entry("survival", "acrobatics"));

    private LegacySkillAdapter() {
    }

    public static String canonical(String id) {
        String normalized = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        return ALIASES.getOrDefault(normalized, normalized);
    }

    public static boolean isLegacyAlias(String id) {
        String normalized = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        return ALIASES.containsKey(normalized) && !normalized.equals(ALIASES.get(normalized));
    }

    public static Map<String, String> aliases() {
        return ALIASES;
    }
}
