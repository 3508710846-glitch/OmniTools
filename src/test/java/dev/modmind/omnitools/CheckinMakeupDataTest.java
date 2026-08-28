package dev.modmind.omnitools;

import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckinMakeupDataTest {
    private static final CheckinRewardConfig.MakeupConfig CONFIG = new CheckinRewardConfig.MakeupConfig(
            true, 99, 7, 3, CheckinRewardConfig.EarliestEligibleDay.FIRST_SEEN, true,
            CheckinRewardConfig.DailyRewardPolicy.NONE, true, new CheckinRewardConfig.PurchaseConfig(true, 200L));

    @Test
    void makeupAtomicallyConsumesOneCardAndRebuildsTheStreak() {
        CheckinData data = new CheckinData();
        UUID player = UUID.randomUUID();
        long today = 20_000L;
        data.ensureFirstSeen(player, today - 10L, "Tester");
        data.signIn(player, today, "Tester", 1L);
        assertEquals(CheckinData.MakeupCardResult.APPLIED, data.addMakeupCards(player, 1L, 99, "Tester"));

        CheckinData.MakeupResult result = data.makeupSignIn(player, today - 1L, today, "Tester", CONFIG);

        assertTrue(result.applied());
        assertEquals(0L, data.getMakeupCards(player));
        assertTrue(data.hasSigned(player, today - 1L));
        assertEquals(2, data.getStats(player, today).streakDays());
        assertEquals(CheckinData.MakeupStatus.ALREADY_SIGNED,
                data.makeupSignIn(player, today - 1L, today, "Tester", CONFIG).status());
        assertEquals(0L, data.getMakeupCards(player));
    }

    @Test
    void invalidMakeupAttemptsDoNotConsumeCards() {
        CheckinData data = new CheckinData();
        UUID player = UUID.randomUUID();
        long today = 20_000L;
        data.ensureFirstSeen(player, today - 3L, "Tester");
        assertEquals(CheckinData.MakeupCardResult.APPLIED, data.addMakeupCards(player, 1L, 99, "Tester"));

        assertEquals(CheckinData.MakeupStatus.NOT_HISTORICAL,
                data.makeupSignIn(player, today, today, "Tester", CONFIG).status());
        assertEquals(CheckinData.MakeupStatus.TOO_OLD,
                data.makeupSignIn(player, today - 8L, today, "Tester", CONFIG).status());
        assertEquals(1L, data.getMakeupCards(player));

        data.addCurrency(player, 500L, "Tester");
        CheckinData.MakeupPurchaseResult purchase = data.purchaseMakeupCards(player, 2L, CONFIG, "Tester");
        assertTrue(purchase.applied());
        assertEquals(3L, purchase.cards());
        assertEquals(100L, purchase.balance());
        assertEquals(0, data.getMakeupUses(player, YearMonth.of(2024, 10)));
        assertFalse(data.hasSigned(player, today - 1L));
    }

    @Test
    void shopChargeUsesTheTransactionIdAsAnIdempotencyMarker() {
        CheckinData data = new CheckinData();
        UUID player = UUID.randomUUID();
        UUID transaction = UUID.randomUUID();
        data.addCurrency(player, 100L, "Tester");

        assertEquals(CheckinData.ShopPurchaseChargeResult.CHARGED,
                data.chargeShopPurchase(player, transaction, 30L, "Tester"));
        assertEquals(CheckinData.ShopPurchaseChargeResult.ALREADY_CHARGED,
                data.chargeShopPurchase(player, transaction, 30L, "Tester"));
        assertEquals(70L, data.getBalance(player));
        assertTrue(data.hasShopPurchaseCharge(player, transaction));
    }
}
