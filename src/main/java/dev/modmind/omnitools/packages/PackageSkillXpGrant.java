package dev.modmind.omnitools.packages;

import dev.modmind.omnitools.reward.RewardDefinition;

import java.util.List;
import java.util.Locale;

/** Immutable, persisted package skill-XP promise with a resolved target when one is available. */
public record PackageSkillXpGrant(String id, long amount, PackageSkillXp.Mode mode, List<TreeOption> options,
                                  String resolvedTreeId, boolean applyTitleXpBonus) {
    public PackageSkillXpGrant {
        id = normalizeId(id, "skill XP id");
        if (amount < 1L) {
            throw new IllegalArgumentException("Package skill XP amount must be positive");
        }
        mode = mode == null ? PackageSkillXp.Mode.FIXED : mode;
        options = List.copyOf(options == null ? List.of() : options);
        if (options.isEmpty()) {
            throw new IllegalArgumentException("Package skill XP must contain at least one tree option");
        }
        if (mode == PackageSkillXp.Mode.FIXED && options.size() != 1) {
            throw new IllegalArgumentException("Fixed package skill XP must contain exactly one tree option");
        }
        if (options.stream().map(TreeOption::treeId).distinct().count() != options.size()) {
            throw new IllegalArgumentException("Package skill XP contains duplicate tree options");
        }
        final String requestedResolvedTreeId = resolvedTreeId == null ? "" : resolvedTreeId.trim().toLowerCase(Locale.ROOT);
        if (!requestedResolvedTreeId.isBlank() && options.stream().noneMatch(option -> option.treeId().equals(requestedResolvedTreeId))) {
            throw new IllegalArgumentException("Resolved package skill XP tree is not an option");
        }
        if (mode == PackageSkillXp.Mode.FIXED && requestedResolvedTreeId.isBlank()) {
            resolvedTreeId = options.getFirst().treeId();
        } else {
            resolvedTreeId = requestedResolvedTreeId;
        }
    }

    public PackageSkillXpGrant(String id, long amount, PackageSkillXp.Mode mode, List<TreeOption> options,
                               String resolvedTreeId) {
        this(id, amount, mode, options, resolvedTreeId, false);
    }

    public boolean requiresPlayerChoice() {
        return mode == PackageSkillXp.Mode.PLAYER_CHOICE && resolvedTreeId.isBlank();
    }

    public boolean unresolvedRandom() {
        return mode == PackageSkillXp.Mode.RANDOM && resolvedTreeId.isBlank();
    }

    public PackageSkillXpGrant withResolvedTree(String treeId) {
        String normalized = treeId == null ? "" : treeId.trim().toLowerCase(Locale.ROOT);
        if (options.stream().noneMatch(option -> option.treeId().equals(normalized))) {
            throw new IllegalArgumentException("Selected package skill XP tree is not an option");
        }
        return new PackageSkillXpGrant(id, amount, mode, options, normalized, applyTitleXpBonus);
    }

    public RewardDefinition asRewardDefinition() {
        if (resolvedTreeId.isBlank()) {
            throw new IllegalStateException("Package skill XP target was not resolved");
        }
        return RewardDefinition.skillXp(id, resolvedTreeId, amount, applyTitleXpBonus);
    }

    private static String normalizeId(String value, String label) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_.-]{1,64}")) {
            throw new IllegalArgumentException("Invalid " + label + ": " + value);
        }
        return normalized;
    }

    /** The option text and icon are snapshotted with the package, not read from later config reloads. */
    public record TreeOption(String treeId, String display, String iconId) {
        public TreeOption {
            treeId = normalizeId(treeId, "skill tree id");
            display = display == null || display.isBlank() ? treeId : display.trim();
            iconId = iconId == null || iconId.isBlank() ? "minecraft:experience_bottle"
                    : iconId.trim().toLowerCase(Locale.ROOT);
        }
    }
}
