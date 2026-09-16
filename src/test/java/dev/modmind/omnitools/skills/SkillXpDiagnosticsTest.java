package dev.modmind.omnitools.skills;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkillXpDiagnosticsTest {
    @Test
    void tracksGrantedRejectedAndTransactionHealthSeparately() {
        SkillXpDiagnostics diagnostics = new SkillXpDiagnostics();

        diagnostics.record(new SkillTreeService.XpResult(SkillTreeService.Status.GRANTED, 15L, 0,
                SkillTreeData.Progress.empty(), 0L), SkillXpSource.BLOCK_BREAK);
        diagnostics.record(new SkillTreeService.XpResult(SkillTreeService.Status.RATE_LIMITED, 0L, 0,
                SkillTreeData.Progress.empty(), 0L), SkillXpSource.BLOCK_BREAK);

        SkillXpDiagnostics.Snapshot snapshot = diagnostics.snapshot(new SkillXpTransactionData.Summary(1, 2, 3));
        assertEquals(2L, snapshot.observedEvents());
        assertEquals(1L, snapshot.grantedEvents());
        assertEquals(1L, snapshot.rejectedEvents());
        assertEquals(15L, snapshot.grantedXp());
        assertEquals(2L, snapshot.sourceCounts().get(SkillXpSource.BLOCK_BREAK));
        assertEquals(1, snapshot.transactions().prepared());
    }
}
