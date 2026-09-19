package dev.modmind.omnitools.divination;

import com.google.gson.JsonParseException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DivinationConfigTest {
    @Test
    void defaultsCoverEachOfTheFiveRanks() {
        DivinationConfig defaults = DivinationConfig.defaults();

        assertEquals(5, defaults.signs().size());
        assertEquals(5, defaults.signs().stream().map(DivinationConfig.SignDefinition::rank).distinct().count());
    }

    @Test
    void disabledSnapshotRemainsStructurallyValid() {
        DivinationConfig empty = DivinationConfig.empty();

        assertFalse(empty.settings().enabled());
        assertEquals(5, empty.signs().size());
    }

    @Test
    void aSignMustDeclareReadableAdviceFields() {
        assertThrows(JsonParseException.class, () -> new DivinationConfig.SignDefinition("bad",
                DivinationConfig.Rank.NEUTRAL, 1, DivinationConfig.Theme.MINING,
                List.of("one", "two", "three", "four"), "meaning", "", "avoid", "advice", 0.0D, 0L));
    }

    @Test
    void configuredModifiersAndRewardsStayWithinSafeRanges() {
        DivinationConfig.SignDefinition greatFortune = DivinationConfig.defaults().sign("great_fortune");
        DivinationConfig.SignDefinition greatMisfortune = DivinationConfig.defaults().sign("great_misfortune");

        assertTrue(greatFortune.skillXpModifier() > 0.0D);
        assertTrue(greatFortune.currencyReward() > 0L);
        assertTrue(greatMisfortune.skillXpModifier() < 0.0D);
        assertEquals(0L, greatMisfortune.currencyReward());
    }
}
