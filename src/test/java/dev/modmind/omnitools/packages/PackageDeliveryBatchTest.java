package dev.modmind.omnitools.packages;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackageDeliveryBatchTest {
    @BeforeAll
    static void bootstrapVanillaItems() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void recordsOneSplitStackWithAnIndependentSnapshot() {
        UUID instanceId = UUID.randomUUID();
        PackageDeliveryBatch batch = PackageDeliveryBatch.create(instanceId,
                List.of(new ItemStack(Items.BREAD, 16)), 10L);

        PackageDeliveryBatch.StackEntry entry = batch.stacks().getFirst();
        assertEquals(instanceId, batch.packageInstanceId());
        assertEquals(1, entry.itemSnapshot().getCount());
        assertEquals(16, entry.quantity());
        assertEquals(16, entry.stack().getCount());
        assertEquals(PackageDeliveryBatch.StackStatus.PENDING, entry.status());
    }

    @Test
    void marksAnInterruptedInventoryBoundaryAsUncertain() {
        PackageDeliveryBatch batch = PackageDeliveryBatch.create(UUID.randomUUID(),
                List.of(new ItemStack(Items.BREAD, 1)), 10L);
        UUID stackId = batch.stacks().getFirst().stackId();

        PackageDeliveryBatch delivering = batch.withStackStatus(stackId,
                PackageDeliveryBatch.StackStatus.DELIVERING, 11L);
        PackageDeliveryBatch delivered = delivering.withStackStatus(stackId,
                PackageDeliveryBatch.StackStatus.DELIVERED, 12L);

        assertTrue(delivering.hasUncertainDelivery());
        assertFalse(delivered.hasUncertainDelivery());
        assertTrue(delivered.isComplete());
        assertEquals(1, delivered.cursor());
    }
}
