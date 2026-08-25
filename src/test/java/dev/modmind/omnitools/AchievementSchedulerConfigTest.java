package dev.modmind.omnitools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AchievementSchedulerConfigTest {
    @Test
    void defaultsBoundWorkPerCycle() {
        AchievementConfig.SchedulerConfig scheduler = AchievementConfig.SchedulerConfig.defaults();
        assertEquals(10, scheduler.checkIntervalTicks());
        assertEquals(8, scheduler.maxPlayersPerTick());
        assertEquals(128, scheduler.maxConditionsPerTick());
    }

    @Test
    void rejectsNonPositiveBudget() {
        assertThrows(IllegalArgumentException.class,
                () -> new AchievementConfig.SchedulerConfig(0, 1, 1, 1));
    }

    @Test
    void preservesIndependentPlayerAndConditionBudgets() {
        AchievementConfig.SchedulerConfig scheduler = new AchievementConfig.SchedulerConfig(5, 2, 11, 30);

        assertEquals(5, scheduler.checkIntervalTicks());
        assertEquals(2, scheduler.maxPlayersPerTick());
        assertEquals(11, scheduler.maxConditionsPerTick());
        assertEquals(30, scheduler.fullRecheckSeconds());
    }
}
