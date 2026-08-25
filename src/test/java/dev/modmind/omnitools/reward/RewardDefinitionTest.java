package dev.modmind.omnitools.reward;

import com.google.gson.JsonParseException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RewardDefinitionTest {
    @Test
    void commandRewardsAllowOnlyControlledBracePlaceholders() {
        assertDoesNotThrow(() -> RewardDefinition.validateCommandText(
                "say {player_name} reached {player_x}", "reward"));
        assertThrows(JsonParseException.class, () -> RewardDefinition.validateCommandText(
                "say %player:name%", "reward"));
    }
}
