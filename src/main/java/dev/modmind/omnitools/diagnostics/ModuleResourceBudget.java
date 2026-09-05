package dev.modmind.omnitools.diagnostics;

import dev.modmind.omnitools.config.ModuleId;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Small, synchronous admission budget for server-thread module operations.
 *
 * <p>This class deliberately does not create worker threads. Minecraft state continues to be
 * touched only from the server thread; callers reject excess interactive work before it starts.</p>
 */
public final class ModuleResourceBudget {
    private static final ModuleResourceBudget GLOBAL = new ModuleResourceBudget();
    private static final long WINDOW_MILLIS = 1_000L;
    private static final Limits DEFAULT_LIMITS = new Limits(200, 32);

    private final EnumMap<ModuleId, Limits> limits = new EnumMap<>(ModuleId.class);
    private final EnumMap<ModuleId, Window> windows = new EnumMap<>(ModuleId.class);
    private final EnumMap<ModuleId, Totals> totals = new EnumMap<>(ModuleId.class);

    public static ModuleResourceBudget global() {
        return GLOBAL;
    }

    public synchronized void configure(ModuleId module, Limits value) {
        if (module != null && value != null) {
            limits.put(module, value);
        }
    }

    public synchronized Limits limits(ModuleId module) {
        return module == null ? DEFAULT_LIMITS : limits.getOrDefault(module, defaultLimits(module));
    }

    public synchronized Decision tryAcquire(ModuleId module, UUID playerId) {
        return tryAcquire(module, playerId, System.currentTimeMillis());
    }

    synchronized Decision tryAcquire(ModuleId module, UUID playerId, long nowMillis) {
        if (module == null) {
            return Decision.accepted(DEFAULT_LIMITS, 0);
        }
        Limits activeLimits = limits(module);
        Window window = windows.get(module);
        if (window == null || nowMillis - window.startedAtMillis() >= WINDOW_MILLIS || nowMillis < window.startedAtMillis()) {
            window = new Window(nowMillis, 0, new HashMap<>());
            windows.put(module, window);
        }
        int playerTasks = playerId == null ? 0 : window.playerTasks().getOrDefault(playerId, 0);
        if (window.tasks() >= activeLimits.maxTasksPerSecond()) {
            record(module, false);
            return Decision.rejected(activeLimits, "module_tasks_per_second", window.tasks(), playerTasks);
        }
        if (playerId != null && playerTasks >= activeLimits.maxTasksPerPlayerPerSecond()) {
            record(module, false);
            return Decision.rejected(activeLimits, "player_tasks_per_second", window.tasks(), playerTasks);
        }
        window = window.accept(playerId);
        windows.put(module, window);
        record(module, true);
        return Decision.accepted(activeLimits, window.tasks());
    }

    /** Clears transient counters after a successful configuration publication or in tests. */
    public synchronized void reset() {
        windows.clear();
        totals.clear();
    }

    public synchronized Map<ModuleId, Metrics> metrics() {
        EnumMap<ModuleId, Metrics> result = new EnumMap<>(ModuleId.class);
        for (ModuleId module : ModuleId.values()) {
            Totals total = totals.getOrDefault(module, Totals.EMPTY);
            Window window = windows.get(module);
            if (total.admitted() > 0L || total.rejected() > 0L || window != null) {
                result.put(module, new Metrics(total.admitted(), total.rejected(),
                        window == null ? 0 : window.tasks(), limits(module)));
            }
        }
        return Map.copyOf(result);
    }

    private void record(ModuleId module, boolean accepted) {
        Totals current = totals.getOrDefault(module, Totals.EMPTY);
        totals.put(module, accepted ? new Totals(current.admitted() + 1L, current.rejected())
                : new Totals(current.admitted(), current.rejected() + 1L));
    }

    private static Limits defaultLimits(ModuleId module) {
        return switch (module) {
            case CLOUD_STORAGE, SHOP -> new Limits(96, 12);
            case DAILY_CHECKIN, ONLINE_REWARD, ACHIEVEMENTS, PACKAGES -> new Limits(144, 20);
            default -> DEFAULT_LIMITS;
        };
    }

    public record Limits(int maxTasksPerSecond, int maxTasksPerPlayerPerSecond) {
        public Limits {
            if (maxTasksPerSecond < 1 || maxTasksPerPlayerPerSecond < 1
                    || maxTasksPerPlayerPerSecond > maxTasksPerSecond) {
                throw new IllegalArgumentException("Resource budget limits must be positive and player limit <= module limit");
            }
        }
    }

    public record Decision(boolean accepted, String reason, Limits limits, int windowTasks, int playerWindowTasks) {
        static Decision accepted(Limits limits, int windowTasks) {
            return new Decision(true, "", limits, windowTasks, 0);
        }

        static Decision rejected(Limits limits, String reason, int windowTasks, int playerWindowTasks) {
            return new Decision(false, reason, limits, windowTasks, playerWindowTasks);
        }
    }

    public record Metrics(long admitted, long rejected, int activeWindowTasks, Limits limits) {
    }

    private record Totals(long admitted, long rejected) {
        private static final Totals EMPTY = new Totals(0L, 0L);
    }

    private record Window(long startedAtMillis, int tasks, Map<UUID, Integer> playerTasks) {
        Window accept(UUID playerId) {
            Map<UUID, Integer> updatedPlayers = new HashMap<>(playerTasks);
            if (playerId != null) {
                updatedPlayers.merge(playerId, 1, Integer::sum);
            }
            return new Window(startedAtMillis, tasks + 1, updatedPlayers);
        }
    }
}
