package dev.modmind.omnitools.skills;

import java.util.UUID;

/**
 * Server-authoritative feedback emitted after a skill mutation has actually succeeded.
 * The event is transient; it is deliberately not persisted with player progression.
 */
public record SkillFeedbackEvent(UUID playerUuid, String skillId, SkillXpSource source, long xpAmount,
                                 int oldLevel, int newLevel, long currentXp, long nextLevelXp,
                                 String operationId, Kind kind, String detail) {
    public SkillFeedbackEvent {
        if (playerUuid == null) throw new IllegalArgumentException("playerUuid is required");
        skillId = skillId == null ? "" : skillId.trim().toLowerCase(java.util.Locale.ROOT);
        source = source;
        xpAmount = Math.max(0L, xpAmount);
        oldLevel = Math.max(0, oldLevel);
        newLevel = Math.max(0, newLevel);
        currentXp = Math.max(0L, currentXp);
        nextLevelXp = Math.max(0L, nextLevelXp);
        operationId = operationId == null ? "" : operationId.trim();
        kind = kind == null ? Kind.XP_GRANTED : kind;
        detail = detail == null ? "" : detail.trim();
    }

    public static SkillFeedbackEvent xp(UUID playerUuid, String skillId, SkillXpSource source,
                                        long xpAmount, int oldLevel, int newLevel, long currentXp,
                                        long nextLevelXp, String operationId) {
        return new SkillFeedbackEvent(playerUuid, skillId, source, xpAmount, oldLevel, newLevel,
                currentXp, nextLevelXp, operationId, Kind.XP_GRANTED, "");
    }

    public static SkillFeedbackEvent active(UUID playerUuid, String skillId, int level,
                                            String operationId, String detail) {
        return new SkillFeedbackEvent(playerUuid, skillId, null, 0L, level, level, 0L, 0L,
                operationId, Kind.ACTIVE_ACTIVATED, detail);
    }

    public static SkillFeedbackEvent passive(UUID playerUuid, String skillId, String operationId,
                                             String detail) {
        return new SkillFeedbackEvent(playerUuid, skillId, null, 0L, 0, 0, 0L, 0L,
                operationId, Kind.PASSIVE_TRIGGERED, detail);
    }

    public enum Kind {
        XP_GRANTED,
        ACTIVE_ACTIVATED,
        PASSIVE_TRIGGERED
    }
}
