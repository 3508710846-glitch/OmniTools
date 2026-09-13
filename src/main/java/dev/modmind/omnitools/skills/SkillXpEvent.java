package dev.modmind.omnitools.skills;

import java.util.Locale;
import java.util.UUID;

/** Immutable, auditable input to a skill XP mutation. */
public record SkillXpEvent(String skillId, SkillXpSource source, long amount, UUID playerId,
                           String world, String cause, String operationId, String antiFarmKey) {
    public SkillXpEvent {
        skillId = normalize(skillId);
        if (skillId.isBlank()) throw new IllegalArgumentException("skillId is required");
        if (source == null) throw new IllegalArgumentException("source is required");
        if (amount <= 0L) throw new IllegalArgumentException("amount must be positive");
        if (playerId == null) throw new IllegalArgumentException("playerId is required");
        world = normalizeText(world);
        cause = normalizeText(cause);
        operationId = normalizeText(operationId);
        antiFarmKey = normalizeText(antiFarmKey);
    }

    public boolean hasOperationId() {
        return !operationId.isBlank();
    }

    public static SkillXpEvent of(UUID playerId, String skillId, SkillXpSource source, long amount,
                                  String operationId, String cause, String antiFarmKey) {
        return new SkillXpEvent(skillId, source, amount, playerId, "", cause, operationId, antiFarmKey);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }
}
