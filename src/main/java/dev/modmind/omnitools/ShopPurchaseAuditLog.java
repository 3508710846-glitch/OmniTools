package dev.modmind.omnitools;

import dev.modmind.omnitools.config.ConfigPaths;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** Append-only audit trail for package purchase checkpoints and recovery blocks. */
public final class ShopPurchaseAuditLog {
    private ShopPurchaseAuditLog() {
    }

    public static synchronized void write(MinecraftServer server, String operation, String details) {
        if (server == null) {
            return;
        }
        Path path = ConfigPaths.root().resolve("shop-purchase-audit.log");
        String line = Instant.now() + " operation=" + sanitize(operation) + " " + sanitize(details)
                + System.lineSeparator();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            System.err.println("[omnitools] Could not write shop purchase audit log: " + exception.getMessage());
        }
    }

    private static String sanitize(String value) {
        return (value == null ? "" : value).replace('\r', ' ').replace('\n', ' ');
    }
}
