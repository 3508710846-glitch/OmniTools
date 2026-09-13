package dev.modmind.omnitools;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Per-open immutable baseline plus mutable page mirror. Player inventory and cursor state remain
 * entirely under vanilla's menu handling; only this mirror is eligible for cloud persistence.
 */
public final class CloudStorageSession {
    private final UUID sessionId;
    private final UUID playerId;
    private final int page;
    private final long openedAt;
    private final List<ItemStack> originalPageSnapshot;
    private State state = State.OPEN;
    private boolean dirty;
    private long changedAtTick = Long.MIN_VALUE;
    private long lastCheckpointTick = Long.MIN_VALUE;

    CloudStorageSession(UUID sessionId, UUID playerId, int page, List<ItemStack> originalPageSnapshot, long openedAt) {
        if (sessionId == null || playerId == null || page < 0 || originalPageSnapshot == null || openedAt < 0L) {
            throw new IllegalArgumentException("Cloud storage session is invalid");
        }
        this.sessionId = sessionId;
        this.playerId = playerId;
        this.page = page;
        this.openedAt = openedAt;
        this.originalPageSnapshot = copyPage(originalPageSnapshot);
    }

    public UUID sessionId() { return sessionId; }
    public UUID playerId() { return playerId; }
    public int page() { return page; }
    public long openedAt() { return openedAt; }
    public State state() { return state; }
    public boolean dirty() { return dirty; }
    public long changedAtTick() { return changedAtTick; }
    public long lastCheckpointTick() { return lastCheckpointTick; }
    public List<ItemStack> originalPageSnapshot() { return copyPage(originalPageSnapshot); }

    boolean canInteract() {
        return state == State.OPEN;
    }

    void markChanged(long currentTick) {
        if (state != State.OPEN) return;
        dirty = true;
        changedAtTick = Math.max(0L, currentTick);
    }

    /**
     * Marks the current mirror as durably represented by the journal/page pair.  This is only
     * legal after {@link #beginCommit()}, so a failed checkpoint cannot accidentally discard the
     * dirty bit and make a later close look clean.
     */
    void completeCheckpoint(long currentTick, List<ItemStack> committedSnapshot) {
        if (state != State.COMMITTING) {
            throw new IllegalStateException("Cloud storage session is not committing");
        }
        copyPage(committedSnapshot); // validate the exact image before exposing the session again
        dirty = false;
        lastCheckpointTick = Math.max(0L, currentTick);
        state = State.OPEN;
    }

    void beginCommit() {
        if (state != State.OPEN) throw new IllegalStateException("Cloud storage session is not open");
        state = State.COMMITTING;
    }

    void reopenAfterCheckpointFailure() {
        if (state == State.COMMITTING) state = State.OPEN;
    }

    void close() {
        state = State.CLOSED;
    }

    void fail() {
        state = State.FAILED;
    }

    static List<ItemStack> copyPage(List<ItemStack> source) {
        if (source == null || source.size() != CloudStorageData.SLOTS_PER_PAGE) {
            throw new IllegalArgumentException("Cloud storage session page has an unexpected slot count");
        }
        List<ItemStack> copy = new ArrayList<>(CloudStorageData.SLOTS_PER_PAGE);
        for (ItemStack stack : source) copy.add(stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
        return List.copyOf(copy);
    }

    public enum State {
        OPEN,
        COMMITTING,
        CLOSED,
        FAILED
    }
}
