package dev.modmind.omnitools.reward;

import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;

/** Stable ledger namespace for one player-triggered reward batch. */
public record RewardEvent(String id, UUID playerId) {
    public RewardEvent {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Reward event id must not be blank");
        }
        Objects.requireNonNull(playerId, "playerId");
    }

    public static RewardEvent checkinDaily(UUID playerId, long epochDay) {
        return new RewardEvent("checkin:" + playerId + ":daily:" + epochDay, playerId);
    }

    public static RewardEvent checkinMonthly(UUID playerId, YearMonth month, int milestone) {
        return new RewardEvent("checkin:" + playerId + ":monthly:" + month + ":" + milestone, playerId);
    }

    public static RewardEvent achievement(UUID playerId, String achievementId) {
        if (achievementId == null || achievementId.isBlank()) {
            throw new IllegalArgumentException("Achievement id must not be blank");
        }
        return new RewardEvent("achievement:" + playerId + ":" + achievementId.trim().toLowerCase(java.util.Locale.ROOT),
                playerId);
    }

    public static RewardEvent online(UUID playerId, long epochDay, String milestoneId) {
        if (milestoneId == null || milestoneId.isBlank()) {
            throw new IllegalArgumentException("Online reward milestone id must not be blank");
        }
        return new RewardEvent("online:" + playerId + ":" + epochDay + ":"
                + milestoneId.trim().toLowerCase(java.util.Locale.ROOT), playerId);
    }

    /** Stable campaign-first key required by the CDK configuration contract. */
    public static RewardEvent cdk(String campaignId, UUID playerId) {
        String normalizedId = campaignId == null ? "" : campaignId.trim().toLowerCase(java.util.Locale.ROOT);
        if (!RewardDefinition.ID_PATTERN.matcher(normalizedId).matches()) {
            throw new IllegalArgumentException("CDK campaign id must be a stable reward id");
        }
        return new RewardEvent("cdk:" + normalizedId + ":" + playerId,
                playerId);
    }
}
