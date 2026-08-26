package dev.modmind.omnitools.reward;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.modmind.omnitools.entitlement.TimedEntitlement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RewardDefinitionTest {
    @Test
    void commandRewardsAllowOnlyControlledBracePlaceholders() {
        assertDoesNotThrow(() -> RewardDefinition.validateCommandText(
                "say {player_name} reached {player_x}", "reward"));
        assertThrows(JsonParseException.class, () -> RewardDefinition.validateCommandText(
                "say %player:name%", "reward"));
    }

    @Test
    void titleRewardsDefaultToPermanentAndParseActiveDays() {
        JsonObject legacyCompatible = new JsonObject();
        assertEquals(TimedEntitlement.Mode.PERMANENT,
                RewardDefinition.parseTitleGrant(legacyCompatible, "reward").mode());

        JsonObject reward = timedTitleReward("active_days", 7, "max");
        TimedEntitlement.Grant grant = RewardDefinition.parseTitleGrant(reward, "reward");
        assertEquals(TimedEntitlement.Mode.ACTIVE_DAYS, grant.mode());
        assertEquals(7L * TimedEntitlement.TICKS_PER_ACTIVE_DAY, grant.activeTicks());
        assertEquals(TimedEntitlement.RenewalPolicy.MAX, grant.renewalPolicy());
    }

    @Test
    void titleRewardDurationRejectsInvalidCombinations() {
        assertThrows(JsonParseException.class, () -> RewardDefinition.parseTitleGrant(
                timedTitleReward("active_days", 0, "extend"), "reward"));
        assertThrows(JsonParseException.class, () -> RewardDefinition.parseTitleGrant(
                timedTitleReward("active_days", 1, "unknown"), "reward"));

        JsonObject permanentWithDays = timedTitleReward("permanent", 1, "extend");
        assertThrows(JsonParseException.class, () -> RewardDefinition.parseTitleGrant(permanentWithDays, "reward"));

        JsonObject permanentWithInvalidRenewal = timedTitleReward("permanent", null, "unknown");
        assertThrows(JsonParseException.class, () -> RewardDefinition.parseTitleGrant(
                permanentWithInvalidRenewal, "reward"));
    }

    @Test
    void timedTitleFactoryWritesActiveDayDurationAndRenewal() {
        RewardDefinition definition = RewardDefinition.titleTimed("vip_week", "vip", 7,
                TimedEntitlement.RenewalPolicy.REPLACE);

        JsonObject serialized = definition.toJsonObject();
        assertEquals("active_days", serialized.getAsJsonObject("duration").get("mode").getAsString());
        assertEquals(7L, serialized.getAsJsonObject("duration").get("days").getAsLong());
        assertEquals("replace", serialized.get("renewal").getAsString());
        assertEquals(TimedEntitlement.Mode.ACTIVE_DAYS,
                RewardDefinition.parseTitleGrant(serialized, "reward").mode());
    }

    private static JsonObject timedTitleReward(String mode, Integer days, String renewal) {
        JsonObject reward = new JsonObject();
        JsonObject duration = new JsonObject();
        duration.addProperty("mode", mode);
        if (days != null) {
            duration.addProperty("days", days);
        }
        reward.add("duration", duration);
        if (renewal != null) {
            reward.addProperty("renewal", renewal);
        }
        return reward;
    }
}
