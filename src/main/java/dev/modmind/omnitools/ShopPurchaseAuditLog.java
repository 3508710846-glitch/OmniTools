package dev.modmind.omnitools;

import dev.modmind.omnitools.config.ConfigPaths;
import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.diagnostics.AsyncAuditLogWriter;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.time.Instant;

/** Append-only audit trail for package purchase checkpoints and recovery blocks. */
public final class ShopPurchaseAuditLog {
    private ShopPurchaseAuditLog() {
    }

    public static void write(MinecraftServer server, String operation, String details) {
        if (server == null) {
            return;
        }
        Path path = ConfigPaths.root().resolve("shop-purchase-audit.log");
        String line = Instant.now() + " operation=" + sanitize(operation) + " " + sanitize(details)
                + System.lineSeparator();
        AsyncAuditLogWriter.global().submit(ModuleId.SHOP, "shop_audit_write", path, line);
    }

    private static String sanitize(String value) {
        return (value == null ? "" : value).replace('\r', ' ').replace('\n', ' ');
    }
}
