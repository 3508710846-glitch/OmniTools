package dev.modmind.omnitools.entitlement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimedEntitlementTest {
    @Test
    void activeDaysUseTwentyFourHoursOfWornServerTicks() {
        TimedEntitlement.Grant grant = TimedEntitlement.Grant.activeDays(7,
                TimedEntitlement.RenewalPolicy.EXTEND);

        assertEquals(7L * TimedEntitlement.TICKS_PER_ACTIVE_DAY, grant.activeTicks());
        assertEquals(1_728_000L, TimedEntitlement.TICKS_PER_ACTIVE_DAY);
    }

    @Test
    void renewalPoliciesPreservePermanentOwnershipAndApplyExpectedDuration() {
        TimedEntitlement existing = new TimedEntitlement(TimedEntitlement.Mode.ACTIVE_DAYS,
                100L, 100L, 1L, TimedEntitlement.RenewalPolicy.EXTEND);

        assertEquals(150L, new TimedEntitlement.Grant(TimedEntitlement.Mode.ACTIVE_DAYS, 50L,
                TimedEntitlement.RenewalPolicy.EXTEND).applyTo(existing, 2L).remainingActiveTicks());
        assertEquals(50L, new TimedEntitlement.Grant(TimedEntitlement.Mode.ACTIVE_DAYS, 50L,
                TimedEntitlement.RenewalPolicy.REPLACE).applyTo(existing, 2L).remainingActiveTicks());
        assertEquals(100L, new TimedEntitlement.Grant(TimedEntitlement.Mode.ACTIVE_DAYS, 50L,
                TimedEntitlement.RenewalPolicy.MAX).applyTo(existing, 2L).remainingActiveTicks());

        TimedEntitlement permanent = TimedEntitlement.permanent(1L);
        assertTrue(new TimedEntitlement.Grant(TimedEntitlement.Mode.ACTIVE_DAYS, 50L,
                TimedEntitlement.RenewalPolicy.REPLACE).applyTo(permanent, 2L).isPermanent());
    }

    @Test
    void activeEntitlementExpiresAfterItsLastConsumedTick() {
        TimedEntitlement entitlement = new TimedEntitlement(TimedEntitlement.Mode.ACTIVE_DAYS,
                1L, 1L, 1L, TimedEntitlement.RenewalPolicy.EXTEND);

        TimedEntitlement expired = entitlement.consumeActiveTick();
        assertFalse(expired.isActive());
        assertEquals(0L, expired.remainingActiveTicks());
    }

    @Test
    void overflowingDayCountIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TimedEntitlement.Grant.activeDays(
                Long.MAX_VALUE, TimedEntitlement.RenewalPolicy.EXTEND));
    }
}
