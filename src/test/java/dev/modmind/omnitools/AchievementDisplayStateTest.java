package dev.modmind.omnitools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AchievementDisplayStateTest {
    @Test
    void resolvesCompletionAndClaimingIndependently() {
        assertEquals(AchievementDisplayState.IN_PROGRESS,
                AchievementDisplayState.resolve(false, false, false));
        assertEquals(AchievementDisplayState.CLAIMABLE,
                AchievementDisplayState.resolve(false, true, false));
        assertEquals(AchievementDisplayState.CLAIMED,
                AchievementDisplayState.resolve(false, true, true));
    }

    @Test
    void lockedStateTakesPrecedenceWithoutUsingPresentationText() {
        assertEquals(AchievementDisplayState.LOCKED,
                AchievementDisplayState.resolve(true, false, false));
    }

    @Test
    void exposesCompletionAndClaimabilityForFiltersAndClicks() {
        assertFalse(AchievementDisplayState.IN_PROGRESS.isCompleted());
        assertFalse(AchievementDisplayState.IN_PROGRESS.isClaimable());
        assertTrue(AchievementDisplayState.CLAIMABLE.isCompleted());
        assertTrue(AchievementDisplayState.CLAIMABLE.isClaimable());
        assertTrue(AchievementDisplayState.CLAIMED.isCompleted());
        assertFalse(AchievementDisplayState.CLAIMED.isClaimable());
    }
}
