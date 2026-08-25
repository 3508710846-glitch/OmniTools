package dev.modmind.omnitools;

import dev.modmind.omnitools.reward.RewardClaimLedger;
import dev.modmind.omnitools.reward.RewardDefinition;
import dev.modmind.omnitools.reward.RewardEvent;
import dev.modmind.omnitools.reward.RewardGrantResult;
import dev.modmind.omnitools.reward.RewardGrantService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/** Creates daily/monthly events; actual delivery and retry state live in {@link RewardGrantService}. */
public final class CheckinRewardService {
    private volatile CheckinRewardConfig config;
    private final RewardGrantService grants;

    private CheckinRewardService(CheckinRewardConfig config, RewardGrantService grants) {
        this.config = config;
        this.grants = grants;
    }

    public static CheckinRewardService load() {
        return new CheckinRewardService(ModMindEntry.configSnapshot().rewards(), ModMindEntry.rewardGrantService());
    }

    public static CheckinRewardService from(CheckinRewardConfig config) {
        return new CheckinRewardService(config, ModMindEntry.rewardGrantService());
    }

    public void reload() {
        config = ModMindEntry.configSnapshot().rewards();
    }

    public List<CheckinRewardConfig.OnlineTimeReward> onlineTimeRewards() {
        return config.onlineTimeRewards();
    }

    public List<RewardDefinition> dailyRewards() {
        return config.dailyRewards();
    }

    public Map<Integer, List<RewardDefinition>> monthlyRewards() {
        return config.monthlyRewards();
    }

    /** The sign-in is already permanent before this method runs; failures remain retryable in the ledger. */
    public void grant(ServerPlayer player, CheckinData.SignInResult result) {
        if (!result.newlySigned()) {
            return;
        }
        RewardGrantResult daily = grants.grant(player, RewardEvent.checkinDaily(player.getUUID(), result.day()),
                config.dailyRewards());
        showResult(player, daily, "daily");
        grantEligibleMonthly(player, result.day(), result.stats().monthlyDays());
    }

    public void retryPending(ServerPlayer player) {
        RewardClaimLedger ledger = RewardClaimLedger.get(player);
        String prefix = "checkin:" + player.getUUID() + ":";
        for (String eventId : ledger.eventIdsStartingWith(prefix)) {
            retryKnownEvent(player, eventId);
        }
        long today = CheckinData.today(player.level().getServer()).toEpochDay();
        CheckinData data = CheckinData.get(player);
        if (data.hasSigned(player.getUUID(), today)) {
            grantEligibleMonthly(player, today, data.getStats(player.getUUID(), today).monthlyDays());
        }
    }

    private void retryKnownEvent(ServerPlayer player, String eventId) {
        String[] parts = eventId.split(":", -1);
        if (parts.length == 4 && parts[2].equals("daily")) {
            try {
                long day = Long.parseLong(parts[3]);
                RewardEvent event = RewardEvent.checkinDaily(player.getUUID(), day);
                showResult(player, grants.retry(player, event, config.dailyRewards()), "daily");
            } catch (RuntimeException ignored) {
                // Ignore corrupt or foreign ledger keys rather than risking a player login failure.
            }
            return;
        }
        if (parts.length == 5 && parts[2].equals("monthly")) {
            try {
                YearMonth month = YearMonth.parse(parts[3]);
                int milestone = Integer.parseInt(parts[4]);
                List<RewardDefinition> rewards = config.monthlyRewards().get(milestone);
                if (rewards == null) {
                    return;
                }
                RewardEvent event = RewardEvent.checkinMonthly(player.getUUID(), month, milestone);
                RewardGrantResult result = grants.retry(player, event, rewards);
                if (result.complete()) {
                    CheckinData.get(player).markMonthlyRewardClaimed(player.getUUID(), month, milestone,
                            player.getGameProfile().name());
                }
                showResult(player, result, "monthly_" + milestone);
            } catch (RuntimeException ignored) {
                // See daily branch.
            }
        }
    }

    private void grantEligibleMonthly(ServerPlayer player, long day, int monthlyDays) {
        CheckinData data = CheckinData.get(player);
        YearMonth month = YearMonth.from(LocalDate.ofEpochDay(day));
        for (Map.Entry<Integer, List<RewardDefinition>> entry : config.monthlyRewards().entrySet()) {
            int milestone = entry.getKey();
            if (monthlyDays < milestone || data.hasClaimedMonthlyReward(player.getUUID(), month, milestone)) {
                continue;
            }
            RewardEvent event = RewardEvent.checkinMonthly(player.getUUID(), month, milestone);
            RewardClaimLedger ledger = RewardClaimLedger.get(player);
            if (!ledger.hasEvent(event) && entry.getValue().isEmpty()) {
                data.markMonthlyRewardClaimed(player.getUUID(), month, milestone, player.getGameProfile().name());
                continue;
            }
            RewardGrantResult result = grants.grant(player, event, entry.getValue());
            if (result.complete()) {
                data.markMonthlyRewardClaimed(player.getUUID(), month, milestone, player.getGameProfile().name());
            }
            showResult(player, result, "monthly_" + milestone);
        }
    }

    private static void showResult(ServerPlayer player, RewardGrantResult result, String event) {
        if (result.status() == RewardGrantResult.Status.SUCCESS && result.granted() > 0) {
            player.sendSystemMessage(Component.translatable("message.omnitools.reward.granted",
                    result.granted(), CheckinData.get(player).getBalance(player.getUUID())));
        } else if (result.status() != RewardGrantResult.Status.SUCCESS) {
            player.sendSystemMessage(Component.translatable("message.omnitools.reward.pending", event, result.reason()));
        }
    }
}
