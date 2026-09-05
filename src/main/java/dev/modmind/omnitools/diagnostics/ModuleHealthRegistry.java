package dev.modmind.omnitools.diagnostics;

import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.config.OmniToolsConfigSnapshot;

import java.util.EnumMap;
import java.util.Map;

/** Runtime circuit breakers for modules that throw from an event or scheduled callback. */
public final class ModuleHealthRegistry {
    private static final ModuleHealthRegistry GLOBAL = new ModuleHealthRegistry();

    private final EnumMap<ModuleId, Status> statuses = new EnumMap<>(ModuleId.class);
    private final EnumMap<ModuleId, Metrics> metrics = new EnumMap<>(ModuleId.class);

    public static ModuleHealthRegistry global() {
        return GLOBAL;
    }

    public synchronized boolean available(ModuleId module) {
        return module == null || statuses.getOrDefault(module, Status.HEALTHY) == Status.HEALTHY;
    }

    public synchronized void recordSuccess(ModuleId module, long durationNanos) {
        if (module != null) {
            metrics.merge(module, Metrics.success(durationNanos), Metrics::merge);
        }
    }

    public synchronized void markDegraded(ModuleId module, long durationNanos) {
        if (module != null) {
            statuses.put(module, Status.DEGRADED);
            metrics.merge(module, Metrics.failure(durationNanos), Metrics::merge);
        }
    }

    /** A successfully applied configuration is an explicit administrator recovery action. */
    public synchronized void reset(OmniToolsConfigSnapshot snapshot) {
        statuses.clear();
        metrics.clear();
        if (snapshot != null) {
            for (ModuleId module : ModuleId.values()) {
                if (!snapshot.enabled(module)) {
                    statuses.put(module, Status.DISABLED);
                }
            }
        }
    }

    public synchronized Map<ModuleId, Status> snapshot() {
        return Map.copyOf(statuses);
    }

    public synchronized Map<ModuleId, Metrics> metrics() {
        return Map.copyOf(metrics);
    }

    public record Metrics(long invocations, long failures, long totalDurationNanos, long lastDurationNanos) {
        static Metrics success(long durationNanos) {
            return new Metrics(1L, 0L, Math.max(0L, durationNanos), Math.max(0L, durationNanos));
        }

        static Metrics failure(long durationNanos) {
            return new Metrics(1L, 1L, Math.max(0L, durationNanos), Math.max(0L, durationNanos));
        }

        Metrics merge(Metrics update) {
            return new Metrics(invocations + update.invocations, failures + update.failures,
                    totalDurationNanos + update.totalDurationNanos, update.lastDurationNanos);
        }

        public long averageMicros() {
            return invocations == 0L ? 0L : totalDurationNanos / invocations / 1_000L;
        }

        public long lastMicros() {
            return lastDurationNanos / 1_000L;
        }
    }

    public enum Status {
        HEALTHY,
        DEGRADED,
        DISABLED
    }
}
