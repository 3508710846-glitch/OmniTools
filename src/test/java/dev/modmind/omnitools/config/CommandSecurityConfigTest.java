package dev.modmind.omnitools.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void distinguishesSecureDefaultsFromLegacyPermissiveCompatibility() {
        CommandSecurityConfig defaults = CommandSecurityConfig.defaults();
        CommandSecurityConfig legacy = CommandSecurityConfig.legacyCompatible();

        assertFalse(defaults.isPermissive());
        assertFalse(defaults.allows("op Player"));
        assertEquals(10, defaults.cooldownTicks());
        assertTrue(legacy.isPermissive());
        assertTrue(legacy.allows("op Player"));
        assertEquals(0, legacy.cooldownTicks());
    }
}
