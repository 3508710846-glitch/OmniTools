package dev.modmind.omnitools;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Exclusive per-player cloud session ownership; no persistent page is touched while a session is open. */
public final class CloudStorageSessionManager {
    /** Keeps a burst of menu closes from monopolising one authoritative server tick. */
    static final int MAX_CHECKPOINTS_PER_TICK = 4;
    private static final CloudStorageSessionManager GLOBAL = new CloudStorageSessionManager();
    private final Map<UUID, ActiveSession> sessions = new HashMap<>();
    private final Map<UUID, PendingRelease> pendingReleases = new HashMap<>();

    private CloudStorageSessionManager() {
    }

    public static CloudStorageSessionManager global() { return GLOBAL; }

    public synchronized Handle open(ServerPlayer player, int page) {
        if (player == null || page < 0) throw new IllegalArgumentException("Cloud storage session request is invalid");
        ActiveSession existing = sessions.get(player.getUUID());
        if (existing != null) {
            throw new IllegalStateException("Cloud storage session already exists for this player");
        }
        CloudStorageSession session = new CloudStorageSession(UUID.randomUUID(), player.getUUID(), page,
                CloudStorageData.get(player).page(player.getUUID(), page), player.level().getGameTime());
        CloudStorageSessionInventory inventory = new CloudStorageSessionInventory(session,
                session.originalPageSnapshot(), player.level()::getGameTime);
        ActiveSession active = new ActiveSession(player, session, inventory);
        sessions.put(player.getUUID(), active);
        return new Handle(session, inventory);
    }

    public synchronized boolean owns(ServerPlayer player, CloudStorageSession session) {
        ActiveSession active = player == null ? null : sessions.get(player.getUUID());
        return session != null && active != null && session == active.session();
    }

    public synchronized void release(ServerPlayer player, CloudStorageSession session) {
        if (player != null && session != null && owns(player, session)) {
            sessions.remove(player.getUUID());
            pendingReleases.remove(player.getUUID());
        }
    }

    public synchronized void releaseAfter(ServerPlayer player, CloudStorageSession session, long currentTick) {
        if (!owns(player, session) || session.state() != CloudStorageSession.State.CLOSED) return;
        pendingReleases.put(player.getUUID(), new PendingRelease(session, Math.max(0L, currentTick) + 2L));
    }

    /** Executes bounded checkpoint work on the authoritative server thread. */
    public void tick(MinecraftServer server) {
        if (server == null) return;
        java.util.List<ActiveSession> checkpoints = new java.util.ArrayList<>();
        long tick = server.getTickCount();
        synchronized (this) {
            for (ActiveSession active : sessions.values()) {
                if (checkpoints.size() >= MAX_CHECKPOINTS_PER_TICK) break;
                if (CloudStorageCommitService.due(active.session(), tick)) checkpoints.add(active);
            }
        }
        for (ActiveSession active : checkpoints) {
            CloudStorageCommitService.Result result = CloudStorageCommitService.checkpoint(active.player(),
                    active.inventory(), CloudStorageCommitService.Reason.CHECKPOINT);
            if (!result.committed()) {
                active.session().fail();
                // Stop receiving vanilla clicks immediately. AbstractContainerMenu.removed()
                // returns a carried cursor stack to the player or world without touching cloud
                // storage, while the failed mirror remains owner-locked for audit/recovery.
                active.player().closeContainer();
            }
        }
        synchronized (this) {
            pendingReleases.entrySet().removeIf(entry -> {
                PendingRelease release = entry.getValue();
                if (tick < release.releaseAtTick()) return false;
                ActiveSession active = sessions.get(entry.getKey());
                if (active != null && active.session() == release.session()) sessions.remove(entry.getKey());
                return true;
            });
        }
    }

    public synchronized int activeSessions() { return sessions.size(); }

    public synchronized boolean hasActiveSession(ServerPlayer player) {
        return player != null && sessions.containsKey(player.getUUID());
    }

    /**
     * Closes a live mirror synchronously for disconnect and stop paths. Successful commits are
     * released immediately; a failed session deliberately remains locked until journal recovery
     * or the next server startup, so no fresh mirror can overwrite ambiguous evidence.
     */
    public void closeFor(ServerPlayer player, CloudStorageCommitService.Reason reason) {
        if (player == null || reason == null) return;
        ActiveSession active;
        synchronized (this) {
            active = sessions.get(player.getUUID());
        }
        if (active == null) return;
        if (active.session().state() == CloudStorageSession.State.CLOSED) {
            release(player, active.session());
            return;
        }
        if (active.session().state() != CloudStorageSession.State.OPEN) return;
        CloudStorageCommitService.Result result = CloudStorageCommitService.checkpoint(player, active.inventory(), reason);
        if (result.committed()) {
            active.session().close();
            release(player, active.session());
        } else {
            active.session().fail();
        }
    }

    /** Flushes all open sessions while their owning player instances are still valid. */
    public void closeAllForStop() {
        java.util.List<ActiveSession> activeSessions;
        synchronized (this) {
            activeSessions = new java.util.ArrayList<>(sessions.values());
        }
        for (ActiveSession active : activeSessions) {
            closeFor(active.player(), CloudStorageCommitService.Reason.STOP);
        }
    }

    /** A new server lifetime must recover from SavedData, never stale in-memory session mirrors. */
    public synchronized void resetForServerStart() {
        sessions.clear();
        pendingReleases.clear();
    }

    public record Handle(CloudStorageSession session, CloudStorageSessionInventory inventory) {
        public Handle {
            if (session == null || inventory == null) throw new IllegalArgumentException("Cloud storage handle is incomplete");
        }
    }

    private record ActiveSession(ServerPlayer player, CloudStorageSession session, CloudStorageSessionInventory inventory) { }
    private record PendingRelease(CloudStorageSession session, long releaseAtTick) { }
}
