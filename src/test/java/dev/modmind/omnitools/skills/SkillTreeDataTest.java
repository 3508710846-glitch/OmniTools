package dev.modmind.omnitools.skills;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        assertEquals(0L, progress.dailyXp());
        assertThrows(UnsupportedOperationException.class, () -> progress.unlockedSkills().add("other"));
    }
}
