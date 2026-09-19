package dev.modmind.omnitools.divination;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DivinationDataTest {
    private static final DivinationConfig.Settings SETTINGS = new DivinationConfig.Settings(
            true, 1, 3, 100L, 0.15D, 30, 0, true);

    @Test
    void unreadSignBlocksReplacementAndDrawOperationIsIdempotent() {
        DivinationData data = new DivinationData();
        UUID player = UUID.randomUUID();
        DivinationData.Reading reading = reading(40L, DivinationConfig.Rank.GREAT_FORTUNE, 0.15D, 300L);

        assertEquals(DivinationData.DrawStatus.DRAWN,
                data.draw(player, "tester", 40L, "draw-1", SETTINGS, reading).status());
        assertEquals(DivinationData.DrawStatus.DUPLICATE_OPERATION,
                data.draw(player, "tester", 40L, "draw-1", SETTINGS, reading).status());
        assertEquals(DivinationData.DrawStatus.PENDING_INTERPRETATION,
                data.draw(player, "tester", 40L, "reroll-1", SETTINGS, reading, DivinationData.DrawMode.REROLL).status());
    }

    @Test
    void paidRerollArchivesTheInterpretedSign() {
        DivinationData data = new DivinationData();
        UUID player = UUID.randomUUID();

        data.draw(player, "tester", 40L, "draw-1", SETTINGS, reading(40L, DivinationConfig.Rank.NEUTRAL, 0.0D, 0L));
        assertEquals(DivinationData.InterpretStatus.INTERPRETED,
                data.interpret(player, "tester", 40L, "interpret-1", false, SETTINGS, null).status());
        assertEquals(DivinationData.DrawStatus.DRAWN, data.draw(player, "tester", 40L, "reroll-1", SETTINGS,
                reading(40L, DivinationConfig.Rank.FORTUNE, 0.08D, 80L), DivinationData.DrawMode.REROLL).status());
        assertEquals(1, data.history(player, 30).size());
        assertFalse(data.current(player, 40L).resolved());
    }

    @Test
    void preparedPaidRerollDoesNotReplaceTheSignUntilWalletPhaseCompletes() {
        DivinationData data = new DivinationData();
        UUID player = UUID.randomUUID();
        DivinationData.Reading first = reading(40L, DivinationConfig.Rank.NEUTRAL, 0.0D, 0L);
        DivinationData.Reading replacement = new DivinationData.Reading(40L, "replacement",
                DivinationConfig.Rank.FORTUNE, DivinationConfig.Theme.COMBAT, 2L,
                false, false, 0, 0, true, 0.08D, 0.0D, false, 80L, "");

        data.draw(player, "tester", 40L, "draw-1", SETTINGS, first);
        data.interpret(player, "tester", 40L, "interpret-1", false, SETTINGS, null);
        assertTrue(data.prepareReroll(player, "tester", 40L, "reroll-1", SETTINGS, replacement).prepared());
        assertEquals("sign", data.current(player, 40L).signId());

        DivinationData.DrawResult committed = data.commitPreparedReroll(player, "tester", "reroll-1");
        assertTrue(committed.changed());
        assertEquals("replacement", data.current(player, 40L).signId());
        assertEquals(1, data.history(player, 30).size());
    }

    @Test
    void aResolvedMisfortuneAppliesItsLightNegativeModifier() {
        DivinationData data = new DivinationData();
        UUID player = UUID.randomUUID();

        data.draw(player, "tester", 40L, "draw-1", SETTINGS,
                reading(40L, DivinationConfig.Rank.MISFORTUNE, -0.03D, 0L));
        data.interpret(player, "tester", 40L, "interpret-1", false, SETTINGS, null);

        assertTrue(data.current(player, 40L).active());
        assertEquals(-0.03D, data.current(player, 40L).activeBonus(0.15D));
    }

    @Test
    void dayBoundaryArchivesTheOldReadingAndResetsTheFreeDraw() {
        DivinationData data = new DivinationData();
        UUID player = UUID.randomUUID();

        data.draw(player, "tester", 40L, "draw-1", SETTINGS, reading(40L, DivinationConfig.Rank.NEUTRAL, 0.0D, 0L));
        assertEquals(DivinationData.DrawStatus.DRAWN,
                data.draw(player, "tester", 41L, "draw-2", SETTINGS, reading(41L, DivinationConfig.Rank.NEUTRAL, 0.0D, 0L)).status());
        assertEquals(1, data.history(player, 30).size());
    }

    private static DivinationData.Reading reading(long day, DivinationConfig.Rank rank, double modifier, long reward) {
        return new DivinationData.Reading(day, "sign", rank, DivinationConfig.Theme.MINING, 1L,
                false, false, 0, 0, true, modifier, 0.0D, false, reward, "");
    }
}
