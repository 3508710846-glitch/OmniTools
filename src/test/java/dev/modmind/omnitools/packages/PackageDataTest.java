package dev.modmind.omnitools.packages;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackageDataTest {
    private static final UUID OWNER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void reusesTheSameRewardGrantKey() {
        PackageData data = new PackageData();
        PackageInstance first = instance("achievement:" + OWNER + ":miner#starter_package");
        PackageInstance duplicate = instance("achievement:" + OWNER + ":miner#starter_package");

        PackageInstance created = data.createIfAbsent(first);
        PackageInstance recovered = data.createIfAbsent(duplicate);

        assertSame(first, created);
        assertSame(first, recovered);
        assertEquals(1, data.list(OWNER).size());
        assertEquals(first.instanceId(), data.findByGrantKey(OWNER, first.grantKey()).orElseThrow().instanceId());
    }

    @Test
    void doesNotDeduplicateAdministrativeGrantsWithoutAKey() {
        PackageData data = new PackageData();

        data.createIfAbsent(instance(""));
        data.createIfAbsent(instance(""));

        assertEquals(2, data.list(OWNER).size());
    }

    @Test
    void preservesTheDisplayDescriptionSnapshotAcrossStatusChanges() {
        PackageInstance instance = new PackageInstance(UUID.randomUUID(), OWNER, "starter", 3,
                "&aStarter", List.of("&7Original description", "&eVersion 3"), "minecraft:chest",
                PackageDefinition.Mode.ALL, List.of(), List.of(), "test", "",
                PackageInstance.Status.PENDING, 1L, -1);

        PackageInstance opening = instance.withOpeningSelection(0);
        PackageInstance blocked = opening.withStatus(PackageInstance.Status.BLOCKED);

        assertEquals(List.of("&7Original description", "&eVersion 3"), instance.description());
        assertEquals(instance.description(), opening.description());
        assertEquals(instance.description(), blocked.description());
    }

    @Test
    void keepsOneDeliveryBatchPerInstanceAndRemovesItWithThePackage() {
        PackageData data = new PackageData();
        PackageInstance instance = data.add(instance(""));
        PackageDeliveryBatch first = new PackageDeliveryBatch(UUID.randomUUID(), instance.instanceId(), List.of(), 0,
                PackageDeliveryBatch.Status.PENDING, 1L, 1L);
        PackageDeliveryBatch duplicate = new PackageDeliveryBatch(UUID.randomUUID(), instance.instanceId(), List.of(), 0,
                PackageDeliveryBatch.Status.PENDING, 2L, 2L);

        assertSame(first, data.createDeliveryBatchIfAbsent(first));
        assertSame(first, data.createDeliveryBatchIfAbsent(duplicate));
        assertTrue(data.findDeliveryBatch(instance.instanceId()).isPresent());
        assertTrue(data.remove(OWNER, instance.instanceId()));
        assertTrue(data.findDeliveryBatch(instance.instanceId()).isEmpty());
    }

    private static PackageInstance instance(String grantKey) {
        return new PackageInstance(UUID.randomUUID(), OWNER, "starter", 1, "Starter", "minecraft:chest",
                PackageDefinition.Mode.ALL, List.of(), List.of(), "test", grantKey,
                PackageInstance.Status.PENDING, 1L, -1);
    }
}
