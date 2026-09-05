package dev.modmind.omnitools.diagnostics;

import dev.modmind.omnitools.GuiFeedbackService;
import dev.modmind.omnitools.ModMindEntry;
import dev.modmind.omnitools.ServerText;
import dev.modmind.omnitools.config.ModuleId;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

/** Prevents a non-core module exception from escaping a server lifecycle callback. */
public final class ModuleFaultBoundary {
    private ModuleFaultBoundary() {
    }

    public static boolean run(ModuleId module, String feature, String recoveryAction, Runnable action) {
        long startedAt = System.nanoTime();
        try {
            action.run();
            ModuleHealthRegistry.global().recordSuccess(module, System.nanoTime() - startedAt);
            return true;
        } catch (RuntimeException exception) {
            long durationNanos = System.nanoTime() - startedAt;
            ModuleHealthRegistry.global().markDegraded(module, durationNanos);
            OperationalErrorReporter.global().error(OperationalErrorReporter.Context.forModule(module, feature)
                    .withState("DEGRADED")
                    .withParameters(Map.of("thread", Thread.currentThread().getName()))
                    .withRecoveryAction(disabledUntilReload(recoveryAction)), exception);
            return false;
        }
    }

    /**
     * Contains a server-thread game event without applying interactive rate limits.
     * Game events remain on the main thread and are never redirected to a worker.
     */
    public static boolean runPlayerEvent(ModuleId module, String feature, ServerPlayer player,
                                         String recoveryAction, Runnable action) {
        long startedAt = System.nanoTime();
        try {
            action.run();
            ModuleHealthRegistry.global().recordSuccess(module, System.nanoTime() - startedAt);
            return true;
        } catch (RuntimeException exception) {
            ModuleHealthRegistry.global().markDegraded(module, System.nanoTime() - startedAt);
            OperationalErrorReporter.global().error(playerContext(module, feature, player)
                    .withState("DEGRADED")
                    .withParameters(Map.of("thread", Thread.currentThread().getName()))
                    .withRecoveryAction(disabledUntilReload(recoveryAction)), exception);
            return false;
        }
    }

    /**
     * Runs a player-originated operation only when it fits the module's synchronous work budget.
     * Failures are contained, reported with player/dimension context, and trip the module circuit breaker.
     */
    public static boolean runPlayerAction(ModuleId module, String feature, ServerPlayer player,
                                          String recoveryAction, Runnable action) {
        ModuleResourceBudget.Decision decision = ModuleResourceBudget.global()
                .tryAcquire(module, player == null ? null : player.getUUID());
        if (!decision.accepted()) {
            OperationalErrorReporter.global().warn(playerContext(module, feature, player)
                    .withState("BUDGET_REJECTED")
                    .withParameters(Map.of(
                            "reason", decision.reason(),
                            "moduleLimit", Integer.toString(decision.limits().maxTasksPerSecond()),
                            "playerLimit", Integer.toString(decision.limits().maxTasksPerPlayerPerSecond())))
                    .withRecoveryAction("reject_before_mutation"),
                    new ResourceBudgetExceededException(decision.reason()));
            if (player != null) {
                GuiFeedbackService.failure(player);
                player.displayClientMessage(ServerText.translatable("message.omnitools.operation_throttled"), true);
            }
            return false;
        }

        long startedAt = System.nanoTime();
        try {
            action.run();
            ModuleHealthRegistry.global().recordSuccess(module, System.nanoTime() - startedAt);
            return true;
        } catch (RuntimeException exception) {
            ModuleHealthRegistry.global().markDegraded(module, System.nanoTime() - startedAt);
            OperationalErrorReporter.global().error(playerContext(module, feature, player)
                    .withState("DEGRADED")
                    .withParameters(Map.of("thread", Thread.currentThread().getName()))
                    .withRecoveryAction(disabledUntilReload(recoveryAction)), exception);
            if (player != null) {
                GuiFeedbackService.failure(player);
                player.displayClientMessage(ServerText.translatable("message.omnitools.operation_failed"), true);
            }
            return false;
        }
    }

    private static OperationalErrorReporter.Context playerContext(ModuleId module, String feature,
                                                                   ServerPlayer player) {
        OperationalErrorReporter.Context context = OperationalErrorReporter.Context.forModule(module, feature)
                .withDataVersion("config:" + ModMindEntry.configSnapshot().root().formatVersion());
        if (player == null) {
            return context;
        }
        return context.withPlayer(player.getUUID())
                .withWorld(player.level().dimension().identifier().toString());
    }

    private static String disabledUntilReload(String recoveryAction) {
        return (recoveryAction == null || recoveryAction.isBlank() ? "state_retained" : recoveryAction)
                + ";module_disabled_until_config_reload";
    }

    private static final class ResourceBudgetExceededException extends RuntimeException {
        private ResourceBudgetExceededException(String reason) {
            super(reason);
        }
    }
}
