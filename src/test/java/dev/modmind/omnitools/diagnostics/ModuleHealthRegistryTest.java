package dev.modmind.omnitools.diagnostics;

import dev.modmind.omnitools.config.ModuleId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleHealthRegistryTest {
    @Test
    void failedBoundaryTripsTheModuleCircuitBreaker() {
        ModuleHealthRegistry registry = ModuleHealthRegistry.global();
        registry.reset(null);

        boolean completed = ModuleFaultBoundary.run(ModuleId.CLOUD_STORAGE, "journal_reconcile",
                "journal_retained_for_manual_recovery", () -> {
                    throw new IllegalStateException("storage unavailable");
                });

        assertFalse(completed);
        assertFalse(registry.available(ModuleId.CLOUD_STORAGE));
        assertTrue(registry.metrics().get(ModuleId.CLOUD_STORAGE).failures() >= 1L);
        registry.reset(null);
    }
}
