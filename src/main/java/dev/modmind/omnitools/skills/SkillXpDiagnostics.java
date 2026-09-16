package dev.modmind.omnitools.skills;

import java.util.EnumMap;
import java.util.Map;

/** In-memory XP counters for operations diagnostics; this class never stores player data. */
public final class SkillXpDiagnostics {
    private final EnumMap<SkillTreeService.Status, Long> statusCounts = new EnumMap<>(SkillTreeService.Status.class);
    private final EnumMap<SkillXpSource, Long> sourceCounts = new EnumMap<>(SkillXpSource.class);
    private long grantedXp;

    public synchronized void record(SkillTreeService.XpResult result, SkillXpSource source) {
        if (result == null) return;
        statusCounts.merge(result.status(), 1L, Long::sum);
        if (source != null) sourceCounts.merge(source, 1L, Long::sum);
        if (result.granted()) grantedXp = saturatedAdd(grantedXp, result.acceptedXp());
    }

    public synchronized Snapshot snapshot(SkillXpTransactionData.Summary transactions) {
        long granted = statusCounts.getOrDefault(SkillTreeService.Status.GRANTED, 0L);
        long observed = statusCounts.values().stream().mapToLong(Long::longValue).sum();
        SkillXpTransactionData.Summary safeTransactions = transactions == null
                ? new SkillXpTransactionData.Summary(0, 0, 0) : transactions;
        return new Snapshot(observed, granted, Math.max(0L, observed - granted), grantedXp,
                Map.copyOf(statusCounts), Map.copyOf(sourceCounts), safeTransactions);
    }

    private static long saturatedAdd(long left, long right) {
        return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    public record Snapshot(long observedEvents, long grantedEvents, long rejectedEvents, long grantedXp,
                           Map<SkillTreeService.Status, Long> statusCounts,
                           Map<SkillXpSource, Long> sourceCounts,
                           SkillXpTransactionData.Summary transactions) {
    }
}
