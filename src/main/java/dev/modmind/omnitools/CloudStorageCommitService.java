package dev.modmind.omnitools;

import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.diagnostics.AsyncAuditLogWriter;
import dev.modmind.omnitools.diagnostics.OperationalErrorReporter;
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
        CloudStorageData.CommitResult result;
        try {
            result = CloudStorageData.get(player).commitPage(player.level().getServer(),
                    player.getUUID(), session.page(), target, session.sessionId(), reason.name().toLowerCase(),
                    session.originalPageSnapshot());
        } catch (RuntimeException exception) {
            // The page/journal implementation normally converts failures into a CommitResult,
            // but keep the session from being stranded in COMMITTING if an unexpected boundary
            // failure escapes (for example a broken SavedData provider).
            session.fail();
            reportBoundaryFailure(session, null, reason, "commit_boundary_failed", exception,
                    "cloud_storage_commit_evidence_retained");
            return Result.rejected(describe(exception));
        }
        if (result == null) {
            session.fail();
            IllegalStateException exception = new IllegalStateException("cloud storage commit returned no result");
            reportBoundaryFailure(session, null, reason, "commit_boundary_failed", exception,
                    "cloud_storage_commit_evidence_retained");
            return Result.rejected(exception.getMessage());
        }
        try {
            audit(session, result, reason);
        } catch (RuntimeException exception) {
            // Audit output is deliberately best-effort and must never change the authoritative
            // page/journal outcome. Keep the committed result usable while recording the failure.
            reportBoundaryFailure(session, result.operationId(), reason, "audit_write_failed", exception,
                    "authoritative_cloud_storage_state_preserved");
        }
        if (result.accepted()) {
            try {
                session.completeCheckpoint(player.level().getGameTime(), target);
                return Result.committed(result);
            } catch (RuntimeException exception) {
                // The durable page is already authoritative at this point. Freeze the mirror so
                // a later interaction cannot diverge from the committed evidence.
                session.fail();
                reportBoundaryFailure(session, result.operationId(), reason, "session_finalize_failed", exception,
                        "committed_page_retained_session_locked");
                return Result.rejected(describe(exception));
            }
        }
        // A failed close/checkpoint must freeze this mirror.  Retrying ordinary clicks against an
        // uncertain page is worse than a temporary lock: it can make the persisted before/after
        // evidence no longer describe the inventory that the player sees.
        session.fail();
        return Result.rejected(result.reason());
    }

    public static boolean due(CloudStorageSession session, long currentTick) {
        return session != null && session.dirty() && session.canInteract()
                && elapsedAtLeast(currentTick, session.changedAtTick(), CHECKPOINT_DELAY_TICKS)
                && elapsedAtLeast(currentTick, session.lastCheckpointTick(), CHECKPOINT_DELAY_TICKS);
    }

    /**
     * Treats {@link Long#MIN_VALUE} as "never happened" without subtracting it. Direct
     * subtraction overflows for a newly opened session and would otherwise suppress its first
     * automatic checkpoint forever.
     */
    static boolean elapsedAtLeast(long currentTick, long previousTick, long delayTicks) {
        if (previousTick == Long.MIN_VALUE) return true;
        if (currentTick < previousTick) return false;
        return currentTick - previousTick >= Math.max(0L, delayTicks);
    }

    private static void audit(CloudStorageSession session, CloudStorageData.CommitResult result, Reason reason) {
        AsyncAuditLogWriter.global().submit(ModuleId.CLOUD_STORAGE, "cloud_storage_session_commit",
                Path.of("logs", "omnitools-cloud-storage-audit.log"),
                "operationId=" + result.operationId() + " sessionId=" + session.sessionId() + " player="
                        + session.playerId() + " page=" + session.page() + " reason=" + reason
                        + " status=" + result.status() + " result=" + result.reason() + System.lineSeparator());
    }

    private static void reportBoundaryFailure(CloudStorageSession session, java.util.UUID operationId, Reason reason,
                                              String feature, RuntimeException exception, String recoveryAction) {
        OperationalErrorReporter.global().warn(OperationalErrorReporter.Context
                        .forModule(ModuleId.CLOUD_STORAGE, feature)
                        .withPlayer(session.playerId())
                        .withOperation(operationId)
                        .withState(session.state().name())
                        .withParameters(java.util.Map.of("page", Integer.toString(session.page()),
                                "reason", reason.name()))
                        .withRecoveryAction(recoveryAction), exception);
    }

    private static String describe(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    public enum Reason { CHECKPOINT, CLOSE, PAGE_SWITCH, DISCONNECT, STOP }
    public record Result(boolean committed, boolean unchanged, String reason, CloudStorageData.CommitResult transaction) {
        static Result committed(CloudStorageData.CommitResult result) { return new Result(true, false, "", result); }
        static Result noChanges() { return new Result(true, true, "", null); }
        static Result rejected(String reason) { return new Result(false, false, reason == null ? "" : reason, null); }
    }
}
