package dev.modmind.omnitools.skills;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillXpTransactionDataTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void preparedTransactionPersistsFullBeforeAndAfterSnapshots() {
        SkillXpTransactionData data = new SkillXpTransactionData();
        SkillTreeData.Progress before = progress(5, 40L, 140L);
        SkillTreeData.Progress after = progress(6, 5L, 205L);

        SkillXpTransactionData.Entry prepared = data.prepare(PLAYER, "block:1", "mining",
                SkillXpSource.BLOCK_BREAK, before, 100L).orElseThrow();
        assertEquals(SkillXpTransactionData.Status.PREPARED, prepared.status());
        assertFalse(data.prepare(PLAYER, "block:1", "mining", SkillXpSource.BLOCK_BREAK, before, 101L).isPresent());

        data.commit(prepared.transactionId(), after, 102L);
        CompoundTag serialized = SkillXpTransactionData.toTag(data);
        SkillXpTransactionData restored = SkillXpTransactionData.fromTag(serialized);
        SkillXpTransactionData.Entry committed = restored.find(prepared.transactionId()).orElseThrow();

        assertEquals(SkillXpTransactionData.Status.COMMITTED, committed.status());
        assertEquals(before, committed.before());
        assertEquals(after, committed.after());
    }

    @Test
    void preparedTransactionCapturesTheTargetSnapshotBeforeCommit() {
        SkillXpTransactionData data = new SkillXpTransactionData();
        SkillTreeData.Progress before = progress(5, 40L, 140L);
        SkillTreeData.Progress target = new SkillTreeData.Progress(6, 5L, 205L, 3, 0, 0, 0, 0,
                Set.of("transaction_unlock"), 0L, 10L, 1L, 0L, Map.of(), 0L, 0L);

        SkillXpTransactionData.Entry prepared = data.prepare(PLAYER, "block:target", "mining",
                SkillXpSource.BLOCK_BREAK, before, target, 100L).orElseThrow();

        assertEquals(SkillXpTransactionData.Status.PREPARED, prepared.status());
        assertEquals(before, prepared.before());
        assertEquals(target, prepared.after());
    }

    @Test
    void preparedRecoveryOnlyAppliesKnownSnapshots() {
        SkillXpTransactionData data = new SkillXpTransactionData();
        SkillTreeData.Progress before = progress(5, 40L, 140L);
        SkillTreeData.Progress target = progress(6, 5L, 205L);
        SkillXpTransactionData.Entry prepared = data.prepare(PLAYER, "block:recover", "mining",
                SkillXpSource.BLOCK_BREAK, before, target, 100L).orElseThrow();

        assertEquals(SkillXpTransactionData.RecoveryAction.COMMIT_TARGET,
                SkillXpTransactionData.recoveryAction(prepared, before));
        assertEquals(SkillXpTransactionData.RecoveryAction.COMMIT_CONFIRMED,
                SkillXpTransactionData.recoveryAction(prepared, target));
        assertEquals(SkillXpTransactionData.RecoveryAction.RETAIN,
                SkillXpTransactionData.recoveryAction(prepared, progress(7, 1L, 300L)));
    }

    @Test
    void sequentialPreparedEventsRecoverAsOneKnownProgressChain() {
        SkillXpTransactionData data = new SkillXpTransactionData();
        SkillTreeData.Progress initial = progress(5, 40L, 140L);
        SkillTreeData.Progress second = progress(5, 55L, 155L);
        SkillTreeData.Progress third = progress(6, 5L, 205L);
        SkillXpTransactionData.Entry first = data.prepare(PLAYER, "block:chain:1", "mining",
                SkillXpSource.BLOCK_BREAK, initial, second, 100L).orElseThrow();
        SkillXpTransactionData.Entry next = data.prepare(PLAYER, "block:chain:2", "mining",
                SkillXpSource.BLOCK_BREAK, second, third, 101L).orElseThrow();

        assertEquals(SkillXpTransactionData.RecoveryAction.COMMIT_TARGET,
                SkillXpTransactionData.recoveryAction(List.of(first, next), initial));
        assertEquals(SkillXpTransactionData.RecoveryAction.COMMIT_TARGET,
                SkillXpTransactionData.recoveryAction(List.of(first, next), third));
        assertEquals(SkillXpTransactionData.RecoveryAction.RETAIN,
                SkillXpTransactionData.recoveryAction(List.of(next, first), initial));
    }

    @Test
    void recoveryMergesOnlyXpFieldsAndPreservesLaterSkillState() {
        SkillTreeData.Progress before = progress(5, 40L, 140L);
        SkillTreeData.Progress target = new SkillTreeData.Progress(6, 5L, 205L, 3, 0, 0, 0, 0,
                Set.of("transaction_unlock"), 0L, 10L, 1L, 0L, Map.of(), 0L, 0L);
        SkillTreeData.Progress current = new SkillTreeData.Progress(5, 40L, 140L, 0, 2, 3, 4, 5,
                Set.of("later_unlock"), 0L, 10L, 1L, 99L, Map.of("active", 4), 88L, 77L);

        SkillTreeData.Progress merged = SkillXpTransactionData.mergeXpProgress(current, before, target);

        assertEquals(target.level(), merged.level());
        assertEquals(target.totalXp(), merged.totalXp());
        assertEquals(2, merged.availablePoints());
        assertEquals(2, merged.attributePoints());
        assertEquals(88L, merged.activeCooldownUntilEpochMillis());
        assertTrue(merged.unlockedSkills().contains("later_unlock"));
        assertTrue(merged.unlockedSkills().contains("transaction_unlock"));
    }

    @Test
    void terminalTransactionsAreIdempotentAndCannotBeReclassified() {
        SkillXpTransactionData data = new SkillXpTransactionData();
        SkillXpTransactionData.Entry prepared = data.prepare(PLAYER, "kill:1", "swords",
                SkillXpSource.ENTITY_KILL, progress(1, 0L, 0L), 100L).orElseThrow();

        data.commit(prepared.transactionId(), progress(1, 10L, 10L), 101L);
        assertEquals(SkillXpTransactionData.Status.COMMITTED,
                data.commit(prepared.transactionId(), progress(99, 0L, 0L), 102L).status());
        assertThrows(IllegalStateException.class,
                () -> data.rollback(prepared.transactionId(), "must not rewrite committed evidence", 103L));
    }

    @Test
    void rolledBackTransactionCanBeRetriedWithTheSameOperationId() {
        SkillXpTransactionData data = new SkillXpTransactionData();
        SkillXpTransactionData.Entry prepared = data.prepare(PLAYER, "craft:1", "repair",
                SkillXpSource.CRAFT, progress(2, 2L, 50L), 100L).orElseThrow();

        assertTrue(data.hasBlockingOperation(PLAYER, "craft:1"));
        data.rollback(prepared.transactionId(), "mutation failed", 101L);
        assertFalse(data.hasBlockingOperation(PLAYER, "craft:1"));
        assertTrue(data.prepare(PLAYER, "craft:1", "repair", SkillXpSource.CRAFT,
                progress(2, 2L, 50L), 102L).isPresent());
    }

    private static SkillTreeData.Progress progress(int level, long currentXp, long totalXp) {
        return new SkillTreeData.Progress(level, currentXp, totalXp, 1, 0, 0, 0, 0,
                Set.of("active"), 0L, 10L, 1L, 0L, Map.of("active", 1), 0L, 0L);
    }
}
