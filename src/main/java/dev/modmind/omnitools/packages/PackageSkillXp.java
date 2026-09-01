package dev.modmind.omnitools.packages;

import java.util.List;
import java.util.Locale;

/** Configured skill-XP reward for a virtual package. */
public record PackageSkillXp(String id, long amount, Mode mode, List<String> treeIds, boolean applyTitleXpBonus) {
    public PackageSkillXp {
        id = normalizeId(id, "skill XP id");
        if (amount < 1L) {
            throw new IllegalArgumentException("Package skill XP amount must be positive");
        }
        mode = mode == null ? Mode.FIXED : mode;
        treeIds = List.copyOf(treeIds == null ? List.of() : treeIds.stream()
                .map(treeId -> normalizeId(treeId, "skill tree id")).toList());
        if (treeIds.isEmpty()) {
            throw new IllegalArgumentException("Package skill XP must contain at least one tree");
        }
        if (mode == Mode.FIXED && treeIds.size() != 1) {
            throw new IllegalArgumentException("Fixed package skill XP must contain exactly one tree");
        }
        if (treeIds.stream().distinct().count() != treeIds.size()) {
            throw new IllegalArgumentException("Package skill XP contains duplicate tree ids");
        }
    }

    public PackageSkillXp(String id, long amount, Mode mode, List<String> treeIds) {
        this(id, amount, mode, treeIds, false);
    }

    private static String normalizeId(String value, String label) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_.-]{1,64}")) {
            throw new IllegalArgumentException("Invalid " + label + ": " + value);
        }
        return normalized;
    }

    public enum Mode {
        FIXED, RANDOM, PLAYER_CHOICE;

        public static Mode parse(String value) {
            try {
                return valueOf((value == null ? "fixed" : value.trim()).toUpperCase(Locale.ROOT));
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Package skill XP mode must be fixed, random, or player_choice");
            }
        }

        public String serializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
