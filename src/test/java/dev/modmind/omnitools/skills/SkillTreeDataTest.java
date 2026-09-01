package dev.modmind.omnitools.skills;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillTreeDataTest {
    @Test
    void progressSnapshotNormalizesNegativePersistedValues() {
        SkillTreeData.Progress progress = new SkillTreeData.Progress(-1, -2L, -3L, -4, -5, -6,
                Set.of("foundation"), -7L, -8L, 42L);

        assertEquals(0, progress.level());
        assertEquals(0L, progress.currentXp());
        assertEquals(0L, progress.totalXp());
        assertEquals(0, progress.availablePoints());
        assertEquals(0L, progress.overflowXp());
        assertEquals(0L, progress.masteryXp());
        assertEquals(0L, progress.dailyXp());
        assertThrows(UnsupportedOperationException.class, () -> progress.unlockedSkills().add("other"));
    }

    @Test
    void announcementStateNormalizesAndKeepsPendingMilestone() {
        SkillTreeData.AnnouncementState state = new SkillTreeData.AnnouncementState(-1L, "COMBAT", -100, 600);

        assertEquals(0L, state.lastBroadcastAt());
        assertEquals("combat", state.pendingTreeId());
        assertEquals(0, state.pendingTreeLevel());
        assertEquals(600, state.pendingTotalLevel());
        assertTrue(state.hasPending());
    }

    @Test
    void progressPersistsUltimateCooldownBoundary() {
        SkillTreeData.Progress progress = new SkillTreeData.Progress(1500, 0L, 0L, 0, 0, 2, 0, 0,
                Set.of("ultimate"), 0L, 0L, 0L, 123_456L);

        assertEquals(123_456L, progress.ultimateCooldownUntilEpochMillis());
    }
}
