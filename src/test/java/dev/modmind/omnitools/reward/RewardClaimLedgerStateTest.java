package dev.modmind.omnitools.reward;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RewardClaimLedgerStateTest {
    @Test
    void persistsTheRecoverableRewardStateTransitions() {
        RewardClaimLedger ledger = new RewardClaimLedger();
        RewardEvent event = RewardEvent.online(UUID.fromString("11111111-2222-3333-4444-555555555555"),
                20691L, "online_30m");

        ledger.registerReward(event, "currency", RewardType.CURRENCY, "Tester");
        assertEquals(RewardClaimLedger.EntryStatus.PENDING, ledger.entry(event, "currency").status());

        ledger.beginApplying(event, "currency", "currency_apply");
        assertEquals(RewardClaimLedger.EntryStatus.APPLYING, ledger.entry(event, "currency").status());

        ledger.mark(event, "currency", RewardClaimLedger.EntryStatus.GRANTED, "");
        assertEquals(RewardClaimLedger.EntryStatus.GRANTED, ledger.entry(event, "currency").status());
    }
}
