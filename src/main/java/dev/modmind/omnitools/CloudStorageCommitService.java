package dev.modmind.omnitools;

import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.diagnostics.AsyncAuditLogWriter;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.util.List;

/** Main-thread transaction boundary between a session mirror and the durable page/journal pair. */
public final class CloudStorageCommitService {
    public static final long CHECKPOINT_DELAY_TICKS = 20L;
    private CloudStorageCommitService() {
    }

    public static Result checkpoint(ServerPlayer player, CloudStorageSessionInventory inventory, Reason reason) {
        if (player == null || inventory == null || reason == null) return Result.rejected("session is unavailable");
        CloudStorageSession session = inventory.session();
        if (!CloudStorageSessionManager.global().owns(player, session) || !session.canInteract()) {
            return Result.rejected("session is no longer open");
        }
        List<ItemStack> target = inventory.snapshot();
        if (!session.dirty()) {
            return Result.noChanges();
        }
        session.beginCommit();
        CloudStorageData.CommitResult result = CloudStorageData.get(player).commitPage(player.level().getServer(),
                player.getUUID(), session.page(), target, session.sessionId(), reason.name().toLowerCase(),
                session.originalPageSnapshot());
        audit(session, result, reason);
        if (result.accepted()) {
            session.completeCheckpoint(player.level().getGameTime(), target);
            return Result.committed(result);
        }
        // A failed close/checkpoint must freeze this mirror.  Retrying ordinary clicks against an
        // uncertain page is worse than a temporary lock: it can make the persisted before/after
        // evidence no longer describe the inventory that the player sees.
        session.fail();
        return Result.rejected(result.reason());
    }

    public static boolean due(CloudStorageSession session, long currentTick) {
        return session != null && session.dirty() && session.canInteract()
                && currentTick - session.changedAtTick() >= CHECKPOINT_DELAY_TICKS
                && currentTick - session.lastCheckpointTick() >= CHECKPOINT_DELAY_TICKS;
    }

    private static void audit(CloudStorageSession session, CloudStorageData.CommitResult result, Reason reason) {
        AsyncAuditLogWriter.global().submit(ModuleId.CLOUD_STORAGE, "cloud_storage_session_commit",
                Path.of("logs", "omnitools-cloud-storage-audit.log"),
                "operationId=" + result.operationId() + " sessionId=" + session.sessionId() + " player="
                        + session.playerId() + " page=" + session.page() + " reason=" + reason
                        + " status=" + result.status() + " result=" + result.reason() + System.lineSeparator());
    }

    public enum Reason { CHECKPOINT, CLOSE, PAGE_SWITCH, DISCONNECT, STOP }
    public record Result(boolean committed, boolean unchanged, String reason, CloudStorageData.CommitResult transaction) {
        static Result committed(CloudStorageData.CommitResult result) { return new Result(true, false, "", result); }
        static Result noChanges() { return new Result(true, true, "", null); }
        static Result rejected(String reason) { return new Result(false, false, reason == null ? "" : reason, null); }
    }
}
