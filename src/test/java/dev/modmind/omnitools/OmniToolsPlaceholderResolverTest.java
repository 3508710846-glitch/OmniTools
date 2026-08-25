package dev.modmind.omnitools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OmniToolsPlaceholderResolverTest {
    @Test
    void exposesTheDocumentedSeventeenBuiltinIds() {
        assertEquals(17, OmniToolsPlaceholderResolver.IDS.size());
        assertTrue(OmniToolsPlaceholderResolver.IDS.contains("balance"));
        assertTrue(OmniToolsPlaceholderResolver.IDS.contains("achievements_total"));
    }

    @Test
    void usesSafeFallbackValuesWithoutAPlayerOrPlaceholderApi() {
        assertEquals("0", OmniToolsPlaceholderResolver.resolve(null, "balance").getString());
        assertEquals("00:00:00", OmniToolsPlaceholderResolver.resolve(null, "online_today_hms").getString());
        assertEquals("false", OmniToolsPlaceholderResolver.resolve(null, "checkin_today").getString());
        assertEquals("0", OmniToolsPlaceholderResolver.resolve(null, "third_party:unknown").getString());
    }
}
