package dev.modmind.omnitools.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleIdTest {
    @Test
    void resolvesStableDirectoryIdsCaseInsensitively() {
        assertEquals(ModuleId.DAILY_CHECKIN, ModuleId.find("DAILY_CHECKIN").orElseThrow());
        assertEquals(ModuleId.CLOUD_STORAGE, ModuleId.find("cloud_storage").orElseThrow());
        assertTrue(ModuleId.find("unknown_module").isEmpty());
    }
}
