package dev.modmind.omnitools.commandmenu;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandMenuActionTest {
    @Test
    void commandActionsRejectTextPlaceholders() {
        assertDoesNotThrow(() -> CommandMenuAction.parse(command("say {player_name}"), "action", true));
        assertThrows(JsonParseException.class, () -> CommandMenuAction.parse(command("say %player:name%"),
                "action", true));
    }

    private static JsonObject command(String value) {
        JsonObject object = new JsonObject();
        object.addProperty("type", "command");
        object.addProperty("run_as", "console");
        object.addProperty("command", value);
        return object;
    }
}
