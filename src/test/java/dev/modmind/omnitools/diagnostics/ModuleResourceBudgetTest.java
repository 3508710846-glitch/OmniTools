package dev.modmind.omnitools.diagnostics;

import dev.modmind.omnitools.config.ModuleId;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleResourceBudgetTest {
    @Test
    void rejectsPlayerAndModuleExcessWithoutCreatingWork() {
        ModuleResourceBudget budget = new ModuleResourceBudget();
        budget.configure(ModuleId.SHOP, new ModuleResourceBudget.Limits(2, 1));
        UUID first = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID second = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        assertTrue(budget.tryAcquire(ModuleId.SHOP, first, 1_000L).accepted());
        ModuleResourceBudget.Decision playerRejected = budget.tryAcquire(ModuleId.SHOP, first, 1_000L);
        assertFalse(playerRejected.accepted());
        assertEquals("player_tasks_per_second", playerRejected.reason());

        assertTrue(budget.tryAcquire(ModuleId.SHOP, second, 1_000L).accepted());
        ModuleResourceBudget.Decision moduleRejected = budget.tryAcquire(ModuleId.SHOP,
                UUID.fromString("99999999-8888-7777-6666-555555555555"), 1_000L);
        assertFalse(moduleRejected.accepted());
        assertEquals("module_tasks_per_second", moduleRejected.reason());

        ModuleResourceBudget.Metrics metrics = budget.metrics().get(ModuleId.SHOP);
        assertEquals(2L, metrics.admitted());
        assertEquals(2L, metrics.rejected());
    }

    @Test
    void opensANewWindowAfterOneSecond() {
        ModuleResourceBudget budget = new ModuleResourceBudget();
        budget.configure(ModuleId.CLOUD_STORAGE, new ModuleResourceBudget.Limits(1, 1));
        UUID player = UUID.randomUUID();

        assertTrue(budget.tryAcquire(ModuleId.CLOUD_STORAGE, player, 1_000L).accepted());
        assertFalse(budget.tryAcquire(ModuleId.CLOUD_STORAGE, player, 1_500L).accepted());
        assertTrue(budget.tryAcquire(ModuleId.CLOUD_STORAGE, player, 2_000L).accepted());
    }
}
