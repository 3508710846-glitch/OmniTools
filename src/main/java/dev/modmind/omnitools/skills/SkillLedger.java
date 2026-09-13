package dev.modmind.omnitools.skills;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Bounded operation-id ledger used by high-frequency skill events.  Persistence is supplied by
 * SkillLedgerData; this small class keeps the mutation and trimming rules testable in isolation.
 */
public final class SkillLedger {
    public static final int MAX_OPERATION_IDS = 16_384;
    private final Set<String> applied = new LinkedHashSet<>();

    public synchronized boolean claim(String operationId) {
        if (operationId == null || operationId.isBlank()) return true;
        if (!applied.add(operationId)) return false;
        while (applied.size() > MAX_OPERATION_IDS) {
            applied.remove(applied.iterator().next());
        }
        return true;
    }

    public synchronized boolean contains(String operationId) {
        return operationId != null && applied.contains(operationId);
    }

    public synchronized Set<String> snapshot() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(applied));
    }

    public synchronized void restore(Iterable<String> operationIds) {
        applied.clear();
        if (operationIds != null) {
            for (String operationId : operationIds) {
                if (operationId != null && !operationId.isBlank()) claim(operationId);
            }
        }
    }
}
