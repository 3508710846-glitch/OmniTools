package dev.modmind.omnitools.diagnostics;

import dev.modmind.omnitools.CloudStorageJournalData;
import dev.modmind.omnitools.ShopPurchaseData;
import dev.modmind.omnitools.reward.RewardClaimLedger;
import dev.modmind.omnitools.skills.SkillXpTransactionData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationLedgerDiagnosticsTest {
    @Test
    void mapsModuleSpecificStatesWithoutChangingTheirRecoveryMeaning() {
        assertEquals(OperationLedgerDiagnostics.State.PREPARED,
                OperationLedgerDiagnostics.state(CloudStorageJournalData.Status.PREPARED));
        assertEquals(OperationLedgerDiagnostics.State.ROLLED_BACK,
                OperationLedgerDiagnostics.state(SkillXpTransactionData.Status.ROLLED_BACK));
        assertEquals(OperationLedgerDiagnostics.State.APPLYING,
                OperationLedgerDiagnostics.state(ShopPurchaseData.Status.CHARGED));
        assertEquals(OperationLedgerDiagnostics.State.FAILED,
                OperationLedgerDiagnostics.state(RewardClaimLedger.EntryStatus.FAILED));
    }

    @Test
    void summarizesCrossLedgerAttentionCountsAndLatestUpdate() {
        OperationLedgerDiagnostics.Summary summary = OperationLedgerDiagnostics.summarize(List.of(
                new OperationLedgerDiagnostics.Entry("cloud_storage", "cloud-1",
                        OperationLedgerDiagnostics.State.PREPARED, 100L),
                new OperationLedgerDiagnostics.Entry("skill_xp", "xp-1",
                        OperationLedgerDiagnostics.State.COMMITTED, 250L),
                new OperationLedgerDiagnostics.Entry("shop", "shop-1",
                        OperationLedgerDiagnostics.State.QUARANTINED, 200L),
                new OperationLedgerDiagnostics.Entry("reward", "reward-1",
                        OperationLedgerDiagnostics.State.FAILED, 300L)));

        assertEquals(4, summary.total());
        assertEquals(1, summary.pendingRecovery());
        assertEquals(1, summary.quarantined());
        assertEquals(1, summary.failed());
        assertEquals(300L, summary.latestUpdatedAt());
        assertTrue(summary.requiresAttention());
    }

    @Test
    void findsExactOperationEvidenceAcrossLedgers() {
        List<OperationLedgerDiagnostics.Entry> found = OperationLedgerDiagnostics.find(List.of(
                new OperationLedgerDiagnostics.Entry("cloud_storage", "shared-operation",
                        OperationLedgerDiagnostics.State.COMMITTED, 100L),
                new OperationLedgerDiagnostics.Entry("shop", "other-operation",
                        OperationLedgerDiagnostics.State.APPLYING, 300L),
                new OperationLedgerDiagnostics.Entry("reward", "shared-operation",
                        OperationLedgerDiagnostics.State.FAILED, 200L)), " shared-operation ");

        assertEquals(List.of("reward", "cloud_storage"), found.stream()
                .map(OperationLedgerDiagnostics.Entry::ledger).toList());
        assertTrue(OperationLedgerDiagnostics.find(found, "missing-operation").isEmpty());
    }

    @Test
    void emptySummaryDoesNotRequestRecovery() {
        OperationLedgerDiagnostics.Summary summary = OperationLedgerDiagnostics.summarize(List.of());

        assertEquals(0, summary.total());
        assertEquals(0, summary.pendingRecovery());
        assertEquals(0, summary.quarantined());
        assertEquals(0, summary.failed());
        assertEquals("none", summary.latestUpdateText());
        assertFalse(summary.requiresAttention());
    }
}
