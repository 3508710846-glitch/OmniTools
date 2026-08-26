package dev.modmind.omnitools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OmniToolsPlaceholderResolverTest {
    @Test
    void exposesTheDocumentedBuiltinIds() {
        assertEquals(22, OmniToolsPlaceholderResolver.IDS.size());
        assertTrue(OmniToolsPlaceholderResolver.IDS.contains("balance"));
        assertTrue(OmniToolsPlaceholderResolver.IDS.contains("achievements_total"));
        assertTrue(OmniToolsPlaceholderResolver.IDS.contains("title_remaining_hms"));
    }

    @Test
    void usesSafeFallbackValuesWithoutAPlayerOrPlaceholderApi() {
        assertEquals("0", OmniToolsPlaceholderResolver.resolve(null, "balance").getString());
        assertEquals("00:00:00", OmniToolsPlaceholderResolver.resolve(null, "online_today_hms").getString());
        assertEquals("false", OmniToolsPlaceholderResolver.resolve(null, "checkin_today").getString());
        assertEquals("0", OmniToolsPlaceholderResolver.resolve(null, "title_remaining_days").getString());
        assertEquals("00:00:00", OmniToolsPlaceholderResolver.resolve(null, "title_remaining_hms").getString());
        assertEquals("false", OmniToolsPlaceholderResolver.resolve(null, "title_is_temporary").getString());
        assertEquals("false", OmniToolsPlaceholderResolver.resolve(null, "title_is_equipped").getString());
        assertEquals("0", OmniToolsPlaceholderResolver.resolve(null, "third_party:unknown").getString());
    }
}
