package dev.modmind.omnitools;

import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.diagnostics.AsyncAuditLogWriter;
import dev.modmind.omnitools.diagnostics.ModuleFaultBoundary;
import dev.modmind.omnitools.skills.SkillXpTransactionData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

/** Owns module lifecycle ordering so the Fabric entrypoint only wires independent registrars. */
final class ModuleLifecycleRegistrar {
    private ModuleLifecycleRegistrar() {
    }

    static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(ModuleLifecycleRegistrar::onStarting);
        ServerLifecycleEvents.SERVER_STARTED.register(ModuleLifecycleRegistrar::onStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(ModuleLifecycleRegistrar::onStopping);
    }

    private static void onStarting(MinecraftServer server) {
        CloudStorageSessionManager.global().resetForServerStart();
        ModMindEntry.skillHudService().clearAll();
        ModMindEntry.resetModuleServicesForStartup();
    }

    private static void onStarted(MinecraftServer server) {
        LegacySavedDataMigration.migrate(server);
        TitleData.bind(server);
        TitleData.importLegacy(server);
        ModMindEntry.reloadModulesAtStartup(server);
        if (ModMindEntry.isModuleEnabled(ModuleId.SKILLS)) {
            ModuleFaultBoundary.run(ModuleId.SKILLS, "xp_transaction_reconcile",
                    "xp_transactions_retained_for_retry", () -> {
                        SkillXpTransactionData.RecoveryReport report = SkillXpTransactionData.get(server)
                                .reconcileStartup(server);
                        if (report.reconciled() > 0 || report.failed() > 0) {
                            dev.modmind.omnitools.diagnostics.OperationalErrorReporter.global().info(
                                    dev.modmind.omnitools.diagnostics.OperationalErrorReporter.Context
                                            .forModule(ModuleId.SKILLS, "xp_transaction_reconcile")
                                            .withState("COMPLETE")
                                            .withParameters(java.util.Map.of("reconciled", Integer.toString(report.reconciled()),
                                                    "failed", Integer.toString(report.failed())))
                                            .withRecoveryAction("prepared_xp_snapshots_restored"),
                                    "Skill XP transaction recovery completed");
                        }
                    });
        }
        if (ModMindEntry.isModuleEnabled(ModuleId.CLOUD_STORAGE)) {
            ModuleFaultBoundary.run(ModuleId.CLOUD_STORAGE, "journal_reconcile",
                    "journal_retained_for_manual_recovery", () -> {
                        CloudStorageJournalData.RecoveryReport storageRecovery = CloudStorageJournalData.get(server)
                                .reconcileStartup(server, CloudStorageData.get(server));
                        if (storageRecovery.committed() > 0 || storageRecovery.quarantined() > 0) {
                            ModMindEntry.logCloudStorageRecovery(storageRecovery);
                        }
                    });
        }
        if (ModMindEntry.isModuleEnabled(ModuleId.DAILY_CHECKIN)) {
            ModuleFaultBoundary.run(ModuleId.DAILY_CHECKIN, "reward_reconcile", "ledger_retained_for_recovery",
                    () -> ModMindEntry.rewardGrantService().reconcileStartup(server));
        }
        if (ModMindEntry.isModuleEnabled(ModuleId.SHOP)) {
            ModuleFaultBoundary.run(ModuleId.SHOP, "purchase_reconcile", "purchase_journal_retained",
                    () -> ModMindEntry.shopPurchaseService().reconcileStartup(server));
        }
        ModuleFaultBoundary.run(null, "placeholder_bootstrap", "placeholders_unavailable",
                PlaceholderBootstrap::registerIfAvailable);
        ModMindEntry.logServerStarted();
    }

    private static void onStopping(MinecraftServer server) {
        ModMindEntry.logServerStopping();
        if (ModMindEntry.isModuleEnabled(ModuleId.CLOUD_STORAGE)) {
            ModuleFaultBoundary.run(ModuleId.CLOUD_STORAGE, "cloud_storage_stop_commit",
                    "session_journal_retained_for_recovery", () -> CloudStorageSessionManager.global().closeAllForStop());
        }
        if (ModMindEntry.isModuleEnabled(ModuleId.ONLINE_REWARD)) {
            ModuleFaultBoundary.run(ModuleId.ONLINE_REWARD, "server_stop_flush", "online_reward_state_retained",
                    () -> ModMindEntry.onlineTimeRewardService().flushAll(server));
        }
        if (ModMindEntry.isModuleEnabled(ModuleId.TITLES)) {
            ModuleFaultBoundary.run(ModuleId.TITLES, "server_stop_flush", "title_entitlements_retained",
                    () -> ModMindEntry.timedEntitlements().flush(server));
            ModuleFaultBoundary.run(ModuleId.TITLES, "server_stop_cleanup", "title_display_cleanup_skipped",
                    () -> TitleDisplayService.clearAll(server));
        }
        if (ModMindEntry.isModuleEnabled(ModuleId.TITLE_EFFECTS)) {
            ModuleFaultBoundary.run(ModuleId.TITLE_EFFECTS, "server_stop_cleanup", "title_effect_cleanup_skipped",
                    () -> TitleEffectService.removeAll(server));
        }
        if (ModMindEntry.isModuleEnabled(ModuleId.SKILLS)) {
            ModuleFaultBoundary.run(ModuleId.SKILLS, "xp_transaction_stop_flush",
                    "xp_transactions_retained_for_startup_recovery",
                    () -> ModMindEntry.skillTreeService().flushPendingXpTransactions(server, true));
            ModuleFaultBoundary.run(ModuleId.SKILLS, "server_stop_cleanup", "skill_attribute_cleanup_skipped",
                    () -> ModMindEntry.skillTreeService().removeAll(server));
            ModuleFaultBoundary.run(ModuleId.SKILLS, "server_stop_hud_cleanup", "skill_hud_cleanup_skipped",
                    () -> ModMindEntry.skillHudService().clearAll(server));
        }
        ModuleFaultBoundary.run(null, "server_stop_audit_flush", "audit_records_may_remain_queued", () -> {
            if (!AsyncAuditLogWriter.global().flush(java.time.Duration.ofSeconds(3L))) {
                throw new IllegalStateException("Timed out waiting for asynchronous audit records");
            }
        });
        ModuleFaultBoundary.run(null, "server_stop_unbind", "title_data_unbind_skipped", () -> TitleData.unbind(server));
        ModMindEntry.logServerStopped();
    }
}
