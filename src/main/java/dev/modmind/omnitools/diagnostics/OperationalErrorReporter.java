package dev.modmind.omnitools.diagnostics;

import dev.modmind.omnitools.config.ModuleId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Structured, rate-limited operational errors retained for administrator diagnostics. */
public final class OperationalErrorReporter {
    private static final Logger LOGGER = LoggerFactory.getLogger("omnitools");
    private static final long RATE_LIMIT_MILLIS = 60_000L;
    private static final int MAX_RECENT = 64;
    private static final int MAX_WINDOWS = 256;
    private static final OperationalErrorReporter GLOBAL = new OperationalErrorReporter();

    private final Map<String, Window> windows = new LinkedHashMap<>();
    private final ArrayDeque<Report> recent = new ArrayDeque<>();

    public static OperationalErrorReporter global() {
        return GLOBAL;
    }

    public synchronized Report error(Context context, Throwable exception) {
        return report(Severity.ERROR, context, exception);
    }

    public synchronized Report warn(Context context, Throwable exception) {
        return report(Severity.WARN, context, exception);
    }

    /** Records an infrequent lifecycle or configuration event in the same searchable format. */
    public synchronized Report info(Context context, String message) {
        Context safeContext = context == null ? Context.forFeature("unknown") : context;
        Report report = new Report(Instant.now(), Severity.INFO, safeContext, "", compact(message), 1);
        recent.addFirst(report);
        while (recent.size() > MAX_RECENT) {
            recent.removeLast();
        }
        LOGGER.info(format(report));
        return report;
    }

    public synchronized List<Report> recent() {
        return List.copyOf(recent);
    }

    /**
     * Emits one summary for each completed rate-limit window so recurring failures retain their
     * last-seen time and count even when no later error starts another window.
     */
    public synchronized void flushExpiredSummaries() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Window> entry : windows.entrySet()) {
            Window window = entry.getValue();
            if (!window.summaryLogged() && window.count() > 1 && now - window.firstSeenAt() >= RATE_LIMIT_MILLIS) {
                LOGGER.warn("[omnitools] Repeated error summary: key={} count={} first={} last={}",
                        entry.getKey(), window.count(), Instant.ofEpochMilli(window.firstSeenAt()),
                        Instant.ofEpochMilli(window.lastSeenAt()));
                entry.setValue(window.markSummaryLogged());
            }
        }
    }

    public synchronized Summary summary() {
        EnumMap<ModuleId, Integer> counts = new EnumMap<>(ModuleId.class);
        for (Report report : recent) {
            if (report.severity() != Severity.INFO && report.context().module() != null) {
                counts.merge(report.context().module(), 1, Integer::sum);
            }
        }
        return new Summary(recent.size(), Map.copyOf(counts));
    }

    private Report report(Severity severity, Context context, Throwable exception) {
        Context safeContext = context == null ? Context.forFeature("unknown") : context;
        Throwable safeException = exception == null ? new IllegalStateException("unknown failure") : exception;
        long now = System.currentTimeMillis();
        String key = safeContext.module() + "|" + safeContext.feature() + "|"
                + safeException.getClass().getName() + "|" + safeMessage(safeException);
        Window previous = windows.get(key);
        boolean newWindow = previous == null || now - previous.firstSeenAt() >= RATE_LIMIT_MILLIS;
        Window current = newWindow ? new Window(now, now, 1) : previous.next(now);
        windows.put(key, current);
        while (windows.size() > MAX_WINDOWS) {
            String oldest = windows.keySet().iterator().next();
            windows.remove(oldest);
        }

        Report report = new Report(Instant.ofEpochMilli(now), severity, safeContext,
                safeException.getClass().getSimpleName(), safeMessage(safeException), current.count());
        recent.addFirst(report);
        while (recent.size() > MAX_RECENT) {
            recent.removeLast();
        }

        String line = format(report);
        if (newWindow) {
            if (previous != null && previous.count() > 1 && !previous.summaryLogged()) {
                LOGGER.warn("[omnitools] Repeated error summary: feature={} count={} first={} last={}",
                        safeContext.feature(), previous.count(), Instant.ofEpochMilli(previous.firstSeenAt()),
                        Instant.ofEpochMilli(previous.lastSeenAt()));
            }
            if (severity == Severity.ERROR) {
                LOGGER.error(line, safeException);
            } else {
                LOGGER.warn(line, safeException);
            }
        }
        return report;
    }

    private static String format(Report report) {
        Context context = report.context();
        return "[omnitools] severity=" + report.severity().name()
                + " module=" + (context.module() == null ? "platform" : context.module().id())
                + " feature=" + context.feature()
                + " player=" + nullable(context.playerId())
                + " world=" + valueOrDash(context.world())
                + " operationId=" + nullable(context.operationId())
                + " dataVersion=" + valueOrDash(context.dataVersion())
                + " state=" + valueOrDash(context.state())
                + " parameters=" + context.parameters()
                + " recoveryAction=" + valueOrDash(context.recoveryAction())
                + " exception=" + valueOrDash(report.exceptionType())
                + " message=" + report.message();
    }

    private static String safeMessage(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : compact(message);
    }

    private static String nullable(UUID value) {
        return value == null ? "-" : value.toString();
    }

    private static String valueOrDash(String value) {
        return value == null || value.isBlank() ? "-" : compact(value);
    }

    private static String compact(String value) {
        String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() <= 512 ? normalized : normalized.substring(0, 512) + "...";
    }

    public enum Severity {
        INFO,
        ERROR,
        WARN
    }

    public record Context(ModuleId module, String feature, UUID playerId, String world, UUID operationId,
                          String dataVersion, String state, Map<String, String> parameters, String recoveryAction) {
        public Context {
            feature = feature == null || feature.isBlank() ? "unknown" : compact(feature);
            world = world == null ? "" : compact(world);
            dataVersion = dataVersion == null ? "" : compact(dataVersion);
            state = state == null ? "" : compact(state);
            recoveryAction = recoveryAction == null ? "" : compact(recoveryAction);
            Map<String, String> sanitized = new LinkedHashMap<>();
            if (parameters != null) {
                parameters.entrySet().stream().limit(8).forEach(entry -> sanitized.put(
                        compact(entry.getKey() == null ? "unknown" : entry.getKey()),
                        compact(entry.getValue() == null ? "" : entry.getValue())));
            }
            parameters = Map.copyOf(sanitized);
        }

        public static Context forModule(ModuleId module, String feature) {
            return new Context(module, feature, null, "", null, "", "", Map.of(), "");
        }

        public static Context forFeature(String feature) {
            return forModule(null, feature);
        }

        public Context withPlayer(UUID value) {
            return new Context(module, feature, value, world, operationId, dataVersion, state, parameters, recoveryAction);
        }

        public Context withWorld(String value) {
            return new Context(module, feature, playerId, value, operationId, dataVersion, state, parameters, recoveryAction);
        }

        public Context withOperation(UUID value) {
            return new Context(module, feature, playerId, world, value, dataVersion, state, parameters, recoveryAction);
        }

        public Context withDataVersion(String value) {
            return new Context(module, feature, playerId, world, operationId, value, state, parameters, recoveryAction);
        }

        public Context withState(String value) {
            return new Context(module, feature, playerId, world, operationId, dataVersion, value, parameters, recoveryAction);
        }

        public Context withParameters(Map<String, String> value) {
            return new Context(module, feature, playerId, world, operationId, dataVersion, state, value, recoveryAction);
        }

        public Context withRecoveryAction(String value) {
            return new Context(module, feature, playerId, world, operationId, dataVersion, state, parameters, value);
        }
    }

    public record Report(Instant time, Severity severity, Context context, String exceptionType, String message,
                         int occurrencesInWindow) {
    }

    public record Summary(int recentReports, Map<ModuleId, Integer> reportsByModule) {
        public String concise() {
            if (reportsByModule.isEmpty()) {
                return "none";
            }
            List<String> values = new ArrayList<>();
            reportsByModule.forEach((module, count) -> values.add(module.id() + "=" + count));
            return String.join(", ", values);
        }
    }

    private record Window(long firstSeenAt, long lastSeenAt, int count, boolean summaryLogged) {
        Window(long firstSeenAt, long lastSeenAt, int count) {
            this(firstSeenAt, lastSeenAt, count, false);
        }

        Window next(long now) {
            return new Window(firstSeenAt, now, count + 1, summaryLogged);
        }

        Window markSummaryLogged() {
            return new Window(firstSeenAt, lastSeenAt, count, true);
        }
    }
}
