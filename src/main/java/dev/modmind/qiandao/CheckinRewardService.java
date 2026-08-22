package dev.modmind.qiandao;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Credits numeric rewards after a server-authoritative sign-in succeeds. */
public final class CheckinRewardService {
    private volatile CheckinRewardConfig config;

    private CheckinRewardService(CheckinRewardConfig config) {
        this.config = config;
    }

    public static CheckinRewardService load() {
        return new CheckinRewardService(ModMindEntry.configSnapshot().rewards());
    }

    public static CheckinRewardService from(CheckinRewardConfig config) {
        return new CheckinRewardService(config);
    }

    public void reload() {
        config = ModMindEntry.configSnapshot().rewards();
    }

    public List<CheckinRewardConfig.OnlineTimeReward> onlineTimeRewards() {
        return config.onlineTimeRewards();
    }

    public void grant(ServerPlayer player, CheckinData.SignInResult result) {
        if (!result.newlySigned()) {
            return;
        }
        CheckinRewardConfig currentConfig = config;
        CheckinData data = CheckinData.get(player);
        long totalCoins = currentConfig.dailyCoins();
        List<Integer> newlyClaimed = new ArrayList<>();
        YearMonth month = YearMonth.from(LocalDate.ofEpochDay(result.day()));
        for (Map.Entry<Integer, Long> entry : currentConfig.monthlyRewards().entrySet()) {
            if (result.stats().monthlyDays() < entry.getKey()
                    || !data.claimMonthlyReward(player.getUUID(), month, entry.getKey(),
                    player.getGameProfile().name())) {
                continue;
            }
            totalCoins = saturatingAdd(totalCoins, entry.getValue());
            newlyClaimed.add(entry.getKey());
        }

        if (totalCoins > 0L) {
            long balance = data.addCurrency(player.getUUID(), totalCoins, player.getGameProfile().name());
            player.sendSystemMessage(Component.translatable(
                    newlyClaimed.isEmpty() ? "message.qiandao.reward.daily" : "message.qiandao.reward.monthly",
                    totalCoins, balance));
        }
        if (!newlyClaimed.isEmpty()) {
            player.sendSystemMessage(Component.translatable(
                    "message.qiandao.reward.milestones", formatMilestones(newlyClaimed)));
        }
    }

    private static String formatMilestones(List<Integer> milestones) {
        return milestones.stream().map(String::valueOf).reduce((left, right) -> left + ", " + right).orElse("");
    }

    private static long saturatingAdd(long left, long right) {
        if (right > Long.MAX_VALUE - left) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
