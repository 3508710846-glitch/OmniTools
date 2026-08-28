package dev.modmind.omnitools;

import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.packages.PackageData;
import dev.modmind.omnitools.packages.PackageInstance;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Coordinates a package purchase across the currency and virtual-package SavedData stores. */
public final class ShopPurchaseService {
    private static final int CLICK_COOLDOWN_TICKS = 4;
    private static final String PACKAGE_REWARD_ID = "package";

    private final Map<UUID, Long> lastPurchaseTicks = new HashMap<>();

    public PurchaseResult purchasePackage(ServerPlayer player, int productIndex, ShopConfig.ShopItem product) {
        if (player == null || product == null || product.type() != ShopConfig.ProductType.PACKAGE) {
            return PurchaseResult.rejected(Result.INVALID, "invalid package product");
        }
        MinecraftServer server = player.level().getServer();
        if (!ModMindEntry.isModuleEnabled(ModuleId.SHOP) || !ModMindEntry.isModuleEnabled(ModuleId.PACKAGES)) {
            return PurchaseResult.rejected(Result.DISABLED, "shop or packages module is disabled");
        }
        long tick = server.getTickCount();
        long lastTick = lastPurchaseTicks.getOrDefault(player.getUUID(), Long.MIN_VALUE);
        if (lastTick != Long.MIN_VALUE && tick - lastTick < CLICK_COOLDOWN_TICKS) {
            return PurchaseResult.rejected(Result.COOLDOWN, "purchase is cooling down");
        }
        Optional<String> preflight = ModMindEntry.packageService().preflightCreate(server, player.getUUID(),
                product.packageId(), 1);
        if (preflight.isPresent()) {
            return PurchaseResult.rejected(Result.PACKAGE_UNAVAILABLE, preflight.get());
        }
        if (CheckinData.get(player).getBalance(player.getUUID()) < product.price()) {
            return PurchaseResult.rejected(Result.INSUFFICIENT_CURRENCY, "insufficient currency");
        }

        UUID transactionId = UUID.randomUUID();
        String grantKey = "shop:" + transactionId + "#" + PACKAGE_REWARD_ID;
        PackageInstance snapshot;
        try {
            snapshot = ModMindEntry.packageService().createSnapshot(player.getUUID(), product.packageId(),
                    "shop:" + transactionId, grantKey);
        } catch (RuntimeException exception) {
            return PurchaseResult.rejected(Result.PACKAGE_UNAVAILABLE, exception.getMessage());
        }
        long now = System.currentTimeMillis();
        ShopPurchaseData.PurchaseTransaction transaction = ShopPurchaseData.PurchaseTransaction.prepared(transactionId,
                player.getUUID(), player.getGameProfile().name(), productIndex, product.price(), PACKAGE_REWARD_ID,
                snapshot, now);
        ShopPurchaseData data = ShopPurchaseData.get(server);
        data.createIfAbsent(transaction);
        try {
            data.flush(server); // PREPARED must exist before a durable currency debit.
        } catch (RuntimeException exception) {
            return PurchaseResult.rejected(Result.BLOCKED, "could not persist prepared transaction");
        }
        lastPurchaseTicks.put(player.getUUID(), tick);
        return advance(server, data, transaction, false);
    }

    /** Replays only checkpoints whose preceding effect is proven by durable state. */
    public void reconcileStartup(MinecraftServer server) {
        if (server == null || !ModMindEntry.isModuleEnabled(ModuleId.SHOP)
                || !ModMindEntry.isModuleEnabled(ModuleId.PACKAGES)) {
            return;
        }
        ShopPurchaseData data = ShopPurchaseData.get(server);
        CheckinData currency = CheckinData.get(server);
        for (ShopPurchaseData.PurchaseTransaction transaction : data.list()) {
            try {
                switch (transaction.status()) {
                    case PREPARED -> {
                        if (!currency.hasShopPurchaseCharge(transaction.ownerId(), transaction.transactionId())) {
                            block(server, data, transaction, "startup found PREPARED transaction without charge proof");
                        } else {
                            ShopPurchaseData.PurchaseTransaction charged = checkpoint(server, data, transaction,
                                    ShopPurchaseData.Status.CHARGED, "recovered durable charge marker");
                            advance(server, data, charged, true);
                        }
                    }
                    case CHARGED -> {
                        if (!currency.hasShopPurchaseCharge(transaction.ownerId(), transaction.transactionId())) {
                            block(server, data, transaction, "startup found CHARGED transaction without charge proof");
                        } else {
                            advance(server, data, transaction, true);
                        }
                    }
                    case PACKAGE_CREATED -> advance(server, data, transaction, true);
                    case COMPLETED, BLOCKED -> {
                        // Terminal states are never replayed automatically.
                    }
                }
            } catch (RuntimeException exception) {
                block(server, data, transaction, "startup recovery failed: " + describe(exception));
            }
        }
    }

    private PurchaseResult advance(MinecraftServer server, ShopPurchaseData data,
                                   ShopPurchaseData.PurchaseTransaction transaction, boolean recovery) {
        try {
            ShopPurchaseData.PurchaseTransaction current = transaction;
            if (current.status() == ShopPurchaseData.Status.PREPARED) {
                CheckinData.ShopPurchaseChargeResult charged = CheckinData.get(server).chargeShopPurchase(
                        current.ownerId(), current.transactionId(), current.price(), current.ownerName());
                if (charged == CheckinData.ShopPurchaseChargeResult.INSUFFICIENT_CURRENCY) {
                    return block(server, data, current, "currency was insufficient at debit checkpoint");
                }
                data.flush(server); // The balance marker is the proof required by startup recovery.
                current = checkpoint(server, data, current, ShopPurchaseData.Status.CHARGED,
                        charged == CheckinData.ShopPurchaseChargeResult.ALREADY_CHARGED
                                ? "reused durable charge marker" : "currency charged");
            }
            if (current.status() == ShopPurchaseData.Status.CHARGED) {
                // createFromSnapshot first queries grantKey, so a crash after PackageData flushed is idempotent.
                ModMindEntry.packageService().createFromSnapshot(server, current.packageSnapshot());
                PackageData.get(server).flush(server);
                current = checkpoint(server, data, current, ShopPurchaseData.Status.PACKAGE_CREATED,
                        recovery ? "package snapshot recovered" : "package snapshot created");
            }
            if (current.status() == ShopPurchaseData.Status.PACKAGE_CREATED) {
                if (PackageData.get(server).findByGrantKey(current.ownerId(), current.grantKey()).isEmpty()) {
                    return block(server, data, current, "package creation checkpoint has no matching package instance");
                }
                current = checkpoint(server, data, current, ShopPurchaseData.Status.COMPLETED, "purchase completed");
                return PurchaseResult.completed(current);
            }
            if (current.status() == ShopPurchaseData.Status.COMPLETED) {
                return PurchaseResult.completed(current);
            }
            return PurchaseResult.rejected(Result.BLOCKED, "transaction is blocked");
        } catch (RuntimeException exception) {
            return block(server, data, transaction, "purchase outcome requires audit: " + describe(exception));
        }
    }

    private static ShopPurchaseData.PurchaseTransaction checkpoint(MinecraftServer server, ShopPurchaseData data,
                                                                    ShopPurchaseData.PurchaseTransaction transaction,
                                                                    ShopPurchaseData.Status status, String reason) {
        ShopPurchaseData.PurchaseTransaction next = data.transition(transaction.transactionId(), status, reason);
        data.flush(server);
        ShopPurchaseAuditLog.write(server, "checkpoint", "transaction=" + next.transactionId() + " status="
                + next.status() + " owner=" + next.ownerId() + " package=" + next.packageSnapshot().packageId());
        return next;
    }

    private static PurchaseResult block(MinecraftServer server, ShopPurchaseData data,
                                        ShopPurchaseData.PurchaseTransaction transaction, String reason) {
        ShopPurchaseData.PurchaseTransaction current = data.find(transaction.transactionId()).orElse(transaction);
        if (current.status() != ShopPurchaseData.Status.BLOCKED && current.status() != ShopPurchaseData.Status.COMPLETED) {
            try {
                current = data.transition(current.transactionId(), ShopPurchaseData.Status.BLOCKED, reason);
                data.flush(server);
            } catch (RuntimeException persistenceFailure) {
                System.err.println("[omnitools] Could not persist blocked shop purchase " + current.transactionId()
                        + ": " + describe(persistenceFailure));
            }
        }
        ShopPurchaseAuditLog.write(server, "blocked", "transaction=" + current.transactionId() + " owner="
                + current.ownerId() + " status=" + current.status() + " reason=" + reason);
        return new PurchaseResult(Result.BLOCKED, current, reason == null ? "" : reason);
    }

    private static String describe(RuntimeException exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    public enum Result {
        COMPLETED,
        INSUFFICIENT_CURRENCY,
        PACKAGE_UNAVAILABLE,
        COOLDOWN,
        DISABLED,
        INVALID,
        BLOCKED
    }

    public record PurchaseResult(Result result, ShopPurchaseData.PurchaseTransaction transaction, String reason) {
        static PurchaseResult completed(ShopPurchaseData.PurchaseTransaction transaction) {
            return new PurchaseResult(Result.COMPLETED, transaction, "");
        }

        static PurchaseResult rejected(Result result, String reason) {
            return new PurchaseResult(result, null, reason == null ? "" : reason);
        }
    }
}
