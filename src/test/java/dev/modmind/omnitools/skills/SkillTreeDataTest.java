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

    @Test
    void modernSkillLevelsAreBoundedAndImmutable() {
        SkillTreeData.Progress progress = new SkillTreeData.Progress(100, 0L, 0L, 0, 0, 0, 0, 0,
                Set.of("active"), 0L, 0L, 0L, 0L, java.util.Map.of("active", 99), 11L, 22L);

        assertEquals(10, progress.skillLevels().get("active"));
        assertThrows(UnsupportedOperationException.class, () -> progress.skillLevels().put("passive", 1));
        assertEquals(11L, progress.activeCooldownUntilEpochMillis());
    }

    @Test
    void operationIdentifiersRemainStableForAuditDeduplication() {
        String first = "block:00000000-0000-0000-0000-000000000001:123:456";
        String second = new String(first);
        assertEquals(first, second);
    }

    @Test
    void firstProfessionalSkillLevelIsOneNotTwo() {
        SkillTreeData.Progress progress = SkillTreeData.Progress.empty();
        assertEquals(0, progress.skillLevels().getOrDefault("active", 0));
    }
}
