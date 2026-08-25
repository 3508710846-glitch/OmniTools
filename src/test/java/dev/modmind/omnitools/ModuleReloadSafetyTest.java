package dev.modmind.omnitools;

import dev.modmind.omnitools.config.OmniToolsRootConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleReloadSafetyTest {
    @Test
    void moduleToggleBuildsAnIndependentCandidateBeforePublication() {
        OmniToolsRootConfig current = OmniToolsRootConfig.defaults();
        OmniToolsRootConfig candidate = current.withModuleEnabled(dev.modmind.omnitools.config.ModuleId.ONLINE_REWARD,
                false);

        assertTrue(current.enabled(dev.modmind.omnitools.config.ModuleId.ONLINE_REWARD));
        assertFalse(candidate.enabled(dev.modmind.omnitools.config.ModuleId.ONLINE_REWARD));
        assertTrue(current.commandSecurity().equals(candidate.commandSecurity()));
    }
}
