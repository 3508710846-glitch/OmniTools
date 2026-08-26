package dev.modmind.omnitools.cdk;

import dev.modmind.omnitools.ModMindEntry;
import dev.modmind.omnitools.reward.RewardEvent;
import dev.modmind.omnitools.reward.RewardGrantResult;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Applies CDK campaigns via the existing reward ledger without retaining raw codes in memory or logs. */
public final class CdkService {
    private volatile CdkConfig config;
    private final Map<UUID, AttemptState> attempts = new HashMap<>();

    public CdkService(CdkConfig config) {
        this.config = config == null ? CdkConfig.empty() : config;
    }

    public void replace(CdkConfig snapshot) {
        config = snapshot == null ? CdkConfig.empty() : snapshot;
        synchronized (attempts) {
            attempts.clear();
        }
    }

    public CdkConfig config() {
        return config;
    }

    public RedemptionResult redeem(ServerPlayer player, String rawCode) {
        CdkConfig current = config;
        long now = System.currentTimeMillis();
        if (!allowAttempt(player.getUUID(), current.security(), now)) {
            return RedemptionResult.unavailable();
        }
        CdkConfig.Campaign campaign = current.find(rawCode).orElse(null);
        if (campaign == null || !campaign.availableAt(Instant.ofEpochMilli(now))) {
            recordFailure(player.getUUID(), current.security(), now);
            return RedemptionResult.unavailable();
        }

        CdkData data = CdkData.get(player.level().getServer());
        CdkData.RedeemResult reservation = data.reserve(campaign, player.getUUID());
        if (reservation == CdkData.RedeemResult.ALREADY_REDEEMED) {
            RewardGrantResult retry = ModMindEntry.rewardGrantService().retry(player,
                    RewardEvent.cdk(campaign.id(), player.getUUID()), campaign.rewards());
            recordSuccess(player.getUUID(), now);
            return retry.complete() ? RedemptionResult.unavailable()
                    : RedemptionResult.pending(campaign.id(), retry.reason());
        }
        if (reservation != CdkData.RedeemResult.RESERVED) {
            recordFailure(player.getUUID(), current.security(), now);
            return RedemptionResult.unavailable();
        }
        RewardGrantResult grant = ModMindEntry.rewardGrantService().grant(player,
                RewardEvent.cdk(campaign.id(), player.getUUID()), campaign.rewards());
        recordSuccess(player.getUUID(), now);
        return grant.complete() ? RedemptionResult.success(campaign.id(), grant.granted())
                : RedemptionResult.pending(campaign.id(), grant.reason());
    }

    /** Retries claims reserved before a stop; reward ids keep every underlying effect idempotent. */
    public void retryPending(ServerPlayer player) {
        CdkData data = CdkData.get(player.level().getServer());
        for (CdkConfig.Campaign campaign : config.campaigns()) {
            if (data.hasRedeemed(campaign.id(), player.getUUID())) {
                ModMindEntry.rewardGrantService().retry(player, RewardEvent.cdk(campaign.id(), player.getUUID()),
                        campaign.rewards());
            }
        }
    }

    /** Lets the shared reward inbox and administrator retry paths recognize a CDK ledger event. */
    public boolean retryEvent(ServerPlayer player, String eventId) {
        if (player == null || eventId == null) {
            return false;
        }
        String prefix = "cdk:";
        String suffix = ":" + player.getUUID();
        if (!eventId.startsWith(prefix) || !eventId.endsWith(suffix)) {
            return false;
        }
        String campaignId = eventId.substring(prefix.length(), eventId.length() - suffix.length());
        CdkConfig.Campaign campaign = config.campaigns().stream()
                .filter(candidate -> candidate.id().equals(campaignId)).findFirst().orElse(null);
        if (campaign == null || !CdkData.get(player.level().getServer()).hasRedeemed(campaignId, player.getUUID())) {
            return false;
        }
        ModMindEntry.rewardGrantService().retry(player, RewardEvent.cdk(campaignId, player.getUUID()), campaign.rewards());
        return true;
    }

    public List<PlayerCampaignStatus> status(ServerPlayer player) {
        CdkData data = CdkData.get(player.level().getServer());
        List<PlayerCampaignStatus> result = new ArrayList<>();
        for (CdkConfig.Campaign campaign : config.campaigns()) {
            if (!data.hasRedeemed(campaign.id(), player.getUUID())) {
                continue;
            }
            RewardGrantResult grant = ModMindEntry.rewardGrantService().retry(player,
                    RewardEvent.cdk(campaign.id(), player.getUUID()), campaign.rewards());
            if (!grant.complete()) {
                result.add(new PlayerCampaignStatus(campaign.id(), false, grant.reason()));
            }
        }
        return List.copyOf(result);
    }

    public List<CdkData.CampaignAudit> audits(MinecraftServer server) {
        CdkData data = CdkData.get(server);
        return config.campaigns().stream().map(campaign -> data.audit(campaign.id())).toList();
    }

    public CdkData.CampaignAudit audit(MinecraftServer server, String campaignId) {
        return CdkData.get(server).audit(campaignId == null ? "" : campaignId.trim());
    }

    private boolean allowAttempt(UUID playerId, CdkConfig.Security security, long now) {
        synchronized (attempts) {
            AttemptState state = attempts.get(playerId);
            if (state == null) {
                return true;
            }
            return now >= state.lockedUntil && now - state.lastAttempt >= security.cooldownTicks() * 50L;
        }
    }

    private void recordFailure(UUID playerId, CdkConfig.Security security, long now) {
        synchronized (attempts) {
            AttemptState state = attempts.computeIfAbsent(playerId, ignored -> new AttemptState());
            state.lastAttempt = now;
            state.failures++;
            if (state.failures >= security.maxFailedAttempts()) {
                state.failures = 0;
                state.lockedUntil = now + security.lockoutSeconds() * 1_000L;
            }
        }
    }

    /** A valid code clears brute-force failures but still observes the configured attempt cooldown. */
    private void recordSuccess(UUID playerId, long now) {
        synchronized (attempts) {
            AttemptState state = attempts.computeIfAbsent(playerId, ignored -> new AttemptState());
            state.lastAttempt = now;
            state.failures = 0;
            state.lockedUntil = 0L;
        }
    }

    public enum Status {
        SUCCESS,
        PENDING,
        UNAVAILABLE
    }

    public record RedemptionResult(Status status, String campaignId, int granted, String reason) {
        static RedemptionResult success(String campaignId, int granted) {
            return new RedemptionResult(Status.SUCCESS, campaignId, granted, "");
        }

        static RedemptionResult pending(String campaignId, String reason) {
            return new RedemptionResult(Status.PENDING, campaignId, 0, reason == null ? "" : reason);
        }

        static RedemptionResult unavailable() {
            return new RedemptionResult(Status.UNAVAILABLE, "", 0, "");
        }
    }

    public record PlayerCampaignStatus(String campaignId, boolean delivered, String reason) {
    }

    private static final class AttemptState {
        private int failures;
        private long lastAttempt = Long.MIN_VALUE;
        private long lockedUntil;
    }
}
