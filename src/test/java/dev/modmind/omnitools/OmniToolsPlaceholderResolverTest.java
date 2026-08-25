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
}
