package dev.modmind.omnitools;

import dev.modmind.omnitools.config.ModuleId;
import net.minecraft.server.level.ServerPlayer;

import java.time.LocalDate;
import java.time.YearMonth;

/** Server-side coordinator for historical sign-ins backed by virtual makeup cards. */
public final class CheckinMakeupService {
    public CheckinData.MakeupResult makeup(ServerPlayer player, LocalDate targetDate) {
        CheckinRewardService rewards = ModMindEntry.rewardService();
        CheckinRewardConfig.MakeupConfig config = rewards.makeup();
        CheckinData data = CheckinData.get(player);
        LocalDate today = CheckinData.today(player.level().getServer());
        if (!ModMindEntry.isModuleEnabled(ModuleId.DAILY_CHECKIN)) {
            return new CheckinData.MakeupResult(CheckinData.MakeupStatus.DISABLED, Long.MIN_VALUE,
                    data.getStats(player.getUUID(), today.toEpochDay()));
        }
        CheckinData.MakeupResult result = data.makeupSignIn(player.getUUID(), targetDate.toEpochDay(),
                today.toEpochDay(), player.getGameProfile().name(), config);
        if (result.applied()) {
            rewards.grantMakeup(player, result);
        }
        return result;
    }

    public CheckinData.MakeupPurchaseResult buy(ServerPlayer player, long amount) {
        return CheckinData.get(player).purchaseMakeupCards(player.getUUID(), amount,
                ModMindEntry.rewardService().makeup(), player.getGameProfile().name());
    }

    public CardStatus status(ServerPlayer player) {
        CheckinData data = CheckinData.get(player);
        LocalDate today = CheckinData.today(player.level().getServer());
        CheckinRewardConfig.MakeupConfig config = ModMindEntry.rewardService().makeup();
        return new CardStatus(data.getMakeupCards(player.getUUID()),
                data.getMakeupUses(player.getUUID(), YearMonth.from(today)), config.maxCards(),
                config.maxUsesPerCalendarMonth());
    }

    public record CardStatus(long cards, int monthlyUses, int maxCards, int maxMonthlyUses) {
    }
}
