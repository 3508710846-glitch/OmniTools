package dev.modmind.omnitools.diagnostics;

import dev.modmind.omnitools.CloudStorageJournalData;
import dev.modmind.omnitools.ShopPurchaseData;
import dev.modmind.omnitools.reward.RewardClaimLedger;
import dev.modmind.omnitools.skills.SkillXpTransactionData;
import net.minecraft.server.MinecraftServer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Read-only view over module-specific operation ledgers. It deliberately translates existing
 * evidence rather than replacing the modules' durable state machines.
 */
public final class OperationLedgerDiagnostics {
    private OperationLedgerDiagnostics() {
    }

    public static Summary inspect(MinecraftServer server) {
        return summarize(entries(server));
    }

    /** Returns a stable, read-only view without creating a missing module ledger. */
    public static List<Entry> entries(MinecraftServer server) {
        List<Entry> entries = new ArrayList<>();
        CloudStorageJournalData.find(server).ifPresent(journal -> journal.entries().forEach(entry ->
                entries.add(new Entry("cloud_storage", entry.operationId().toString(), state(entry.status()),
                        entry.updatedAt()))));
        SkillXpTransactionData.find(server).ifPresent(journal -> journal.entries().forEach(entry ->
                entries.add(new Entry("skill_xp", entry.transactionId(), state(entry.status()), entry.updatedAt()))));
        ShopPurchaseData.find(server).ifPresent(journal -> journal.list().forEach(entry ->
                entries.add(new Entry("shop", entry.transactionId().toString(), state(entry.status()),
                        entry.updatedAt()))));
        RewardClaimLedger.find(server).ifPresent(ledger -> ledger.allEntries().forEach(entry ->
                entries.add(new Entry("reward", entry.eventId() + "#" + entry.rewardId(),
                        state(entry.entry().status()), entry.entry().updatedAt()))));
        return List.copyOf(entries);
    }

    /** Finds exact operation evidence across ledgers without inferring or changing recovery state. */
    public static List<Entry> find(MinecraftServer server, String operationId) {
        return find(entries(server), operationId);
    }

    static List<Entry> find(Collection<Entry> entries, String operationId) {
        String normalized = operationId == null ? "" : operationId.trim();
        if (normalized.isEmpty() || entries == null || entries.isEmpty()) {
            return List.of();
        }
        return entries.stream()
                .filter(entry -> entry != null && entry.operationId().equals(normalized))
                .sorted(java.util.Comparator.comparingLong(Entry::updatedAt).reversed())
                .toList();
    }

    public static String updatedAtText(long updatedAt) {
        return updatedAt <= 0L ? "none" : Instant.ofEpochMilli(updatedAt).toString();
    }

    public static State state(CloudStorageJournalData.Status status) {
        return switch (status) {
            case PREPARED -> State.PREPARED;
            case COMMITTED -> State.COMMITTED;
            case ROLLED_BACK -> State.ROLLED_BACK;
            case QUARANTINED -> State.QUARANTINED;
        };
    }

    public static State state(SkillXpTransactionData.Status status) {
        return switch (status) {
            case PREPARED -> State.PREPARED;
            case COMMITTED -> State.COMMITTED;
            case ROLLED_BACK -> State.ROLLED_BACK;
        };
    }

    public static State state(ShopPurchaseData.Status status) {
        return switch (status) {
            case PREPARED -> State.PREPARED;
            case CHARGED, PACKAGE_CREATED -> State.APPLYING;
            case COMPLETED -> State.COMMITTED;
            case BLOCKED -> State.QUARANTINED;
        };
    }

    public static State state(RewardClaimLedger.EntryStatus status) {
        return switch (status) {
            case PENDING -> State.PENDING;
            case APPLYING -> State.APPLYING;
            case GRANTED -> State.COMMITTED;
            case BLOCKED -> State.QUARANTINED;
            case FAILED -> State.FAILED;
        };
    }

    public static Summary summarize(Collection<Entry> entries) {
        int total = 0;
        int pendingRecovery = 0;
        int quarantined = 0;
        int failed = 0;
        long latestUpdatedAt = 0L;
        if (entries != null) {
            for (Entry entry : entries) {
                if (entry == null) {
                    continue;
                }
                total++;
                if (entry.state() == State.PREPARED || entry.state() == State.APPLYING) {
                    pendingRecovery++;
                } else if (entry.state() == State.QUARANTINED) {
                    quarantined++;
                } else if (entry.state() == State.FAILED) {
                    failed++;
                }
                latestUpdatedAt = Math.max(latestUpdatedAt, entry.updatedAt());
            }
        }
        return new Summary(total, pendingRecovery, quarantined, failed, latestUpdatedAt);
    }

    public enum State {
        PENDING,
        PREPARED,
        APPLYING,
        COMMITTED,
        ROLLED_BACK,
        QUARANTINED,
        FAILED
    }

    public record Entry(String ledger, String operationId, State state, long updatedAt) {
        public Entry {
            ledger = normalize(ledger, "unknown");
            operationId = normalize(operationId, "unknown");
            state = state == null ? State.PENDING : state;
            updatedAt = Math.max(0L, updatedAt);
        }

        private static String normalize(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }

    public record Summary(int total, int pendingRecovery, int quarantined, int failed, long latestUpdatedAt) {
        public Summary {
            total = Math.max(0, total);
            pendingRecovery = Math.max(0, pendingRecovery);
            quarantined = Math.max(0, quarantined);
            failed = Math.max(0, failed);
            latestUpdatedAt = Math.max(0L, latestUpdatedAt);
        }

        public String latestUpdateText() {
            return updatedAtText(latestUpdatedAt);
        }

        public boolean requiresAttention() {
            return pendingRecovery > 0 || quarantined > 0 || failed > 0;
        }
    }
}
