package dev.modmind.omnitools;

import dev.modmind.omnitools.packages.PackageDefinition;
import dev.modmind.omnitools.packages.PackageInstance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShopPurchaseDataTest {
    private static final UUID OWNER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void keepsOnePurchaseAndUsesTheStablePackageGrantKey() {
        ShopPurchaseData data = new ShopPurchaseData();
        UUID transactionId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        ShopPurchaseData.PurchaseTransaction first = prepared(transactionId);
        ShopPurchaseData.PurchaseTransaction duplicate = prepared(transactionId);

        assertSame(first, data.createIfAbsent(first));
        assertSame(first, data.createIfAbsent(duplicate));
        assertEquals("shop:" + transactionId + "#package", first.grantKey());
        assertEquals(1, data.list().size());
    }

    @Test
    void onlyPermitsForwardPurchaseCheckpoints() {
        ShopPurchaseData data = new ShopPurchaseData();
        UUID transactionId = UUID.randomUUID();
        data.createIfAbsent(prepared(transactionId));

        assertEquals(ShopPurchaseData.Status.CHARGED,
                data.transition(transactionId, ShopPurchaseData.Status.CHARGED, "charged").status());
        assertEquals(ShopPurchaseData.Status.PACKAGE_CREATED,
                data.transition(transactionId, ShopPurchaseData.Status.PACKAGE_CREATED, "created").status());
        assertEquals(ShopPurchaseData.Status.COMPLETED,
                data.transition(transactionId, ShopPurchaseData.Status.COMPLETED, "completed").status());
        assertThrows(IllegalStateException.class,
                () -> data.transition(transactionId, ShopPurchaseData.Status.BLOCKED, "must remain terminal"));
    }

    private static ShopPurchaseData.PurchaseTransaction prepared(UUID transactionId) {
        String grantKey = "shop:" + transactionId + "#package";
        PackageInstance snapshot = new PackageInstance(UUID.randomUUID(), OWNER, "starter", 1, "Starter",
                List.of(), "minecraft:chest", PackageDefinition.Mode.ALL, List.of(), List.of(),
                "shop:" + transactionId, grantKey, PackageInstance.Status.PENDING, 1L, -1);
        return ShopPurchaseData.PurchaseTransaction.prepared(transactionId, OWNER, "Tester", 5, 100L,
                "package", snapshot, 1L);
    }
}
