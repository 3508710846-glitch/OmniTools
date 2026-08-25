package dev.modmind.omnitools.reward;

import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RewardEventTest {
    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void eventIdsRemainStableAcrossRewardOrderingChanges() {
        assertEquals("checkin:" + PLAYER + ":daily:20455", RewardEvent.checkinDaily(PLAYER, 20455L).id());
        assertEquals("checkin:" + PLAYER + ":monthly:2026-08:10",
                RewardEvent.checkinMonthly(PLAYER, YearMonth.of(2026, 8), 10).id());
        assertEquals("achievement:" + PLAYER + ":stone_expert",
                RewardEvent.achievement(PLAYER, "Stone_Expert").id());
    }
}
