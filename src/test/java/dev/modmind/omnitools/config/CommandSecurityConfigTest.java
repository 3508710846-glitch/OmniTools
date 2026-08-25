package dev.modmind.omnitools.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandSecurityConfigTest {
    @Test
    void acceptsOnlyConfiguredRootsAndRejectsCommandInjection() {
        CommandSecurityConfig security = new CommandSecurityConfig(List.of("say", "trigger"), 64, 20);

        assertTrue(security.allows("say hello"));
        assertTrue(security.allows("/trigger daily set 1"));
        assertFalse(security.allows("op Player"));
        assertFalse(security.allows("say first\nstop"));
        assertFalse(security.allows("say this command is intentionally longer than sixty-four characters"));
    }
}
