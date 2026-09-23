package dev.modmind.omnitools;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.ChatType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.core.registries.BuiltInRegistries;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.config.ModuleCommandRegistrar;
import dev.modmind.omnitools.config.OmniToolsConfigManager;
import dev.modmind.omnitools.config.OmniToolsConfigSnapshot;
import dev.modmind.omnitools.permissions.CommandAction;
import dev.modmind.omnitools.permissions.CommandPermissionConfig;
import dev.modmind.omnitools.permissions.CommandPermissionService;
import dev.modmind.omnitools.commandmenu.CommandMenuAction;
import dev.modmind.omnitools.commandmenu.CommandMenuConfig;
import dev.modmind.omnitools.commandmenu.CommandMenuDefinition;
import dev.modmind.omnitools.commandmenu.CommandMenuItem;
import dev.modmind.omnitools.commandmenu.CommandMenuScreenHandler;
import dev.modmind.omnitools.commandmenu.CommandMenuService;
import dev.modmind.omnitools.sidebar.SidebarService;
import dev.modmind.omnitools.leaderboard.LeaderboardConfig;
import dev.modmind.omnitools.leaderboard.LeaderboardService;
import dev.modmind.omnitools.reward.RewardClaimLedger;
import dev.modmind.omnitools.reward.RewardDefinition;
import dev.modmind.omnitools.reward.RewardEvent;
import dev.modmind.omnitools.reward.RewardGrantService;
import dev.modmind.omnitools.text.TextTemplateRenderer;
import dev.modmind.omnitools.entitlement.TimedEntitlementService;
import dev.modmind.omnitools.entitlement.TimedEntitlement;
import dev.modmind.omnitools.cdk.CdkConfig;
import dev.modmind.omnitools.cdk.CdkData;
import dev.modmind.omnitools.cdk.CdkService;
import dev.modmind.omnitools.packages.PackageService;
import dev.modmind.omnitools.packages.PackageData;
import dev.modmind.omnitools.packages.PackageInstance;
import dev.modmind.omnitools.packages.PackageDeliveryBatch;
import dev.modmind.omnitools.packages.PackageAuditLog;
import dev.modmind.omnitools.skills.SkillTreeConfig;
import dev.modmind.omnitools.skills.SkillTreeService;
import dev.modmind.omnitools.skills.SkillXpSource;
import dev.modmind.omnitools.skills.SkillXpEvent;
import dev.modmind.omnitools.skills.SkillEventRouter;
import dev.modmind.omnitools.skills.McmmoSkillModule;
import dev.modmind.omnitools.skills.SkillHudService;
import dev.modmind.omnitools.divination.DivinationConfig;
import dev.modmind.omnitools.divination.DivinationData;
import dev.modmind.omnitools.divination.DivinationService;
import dev.modmind.omnitools.diagnostics.AsyncAuditLogWriter;
import dev.modmind.omnitools.diagnostics.ModuleFaultBoundary;
import dev.modmind.omnitools.diagnostics.ModuleHealthRegistry;
import dev.modmind.omnitools.diagnostics.ModuleResourceBudget;
import dev.modmind.omnitools.diagnostics.OperationLedgerDiagnostics;
import dev.modmind.omnitools.diagnostics.OperationalErrorReporter;

public final class ModMindEntry implements ModInitializer {
    public static final String MOD_ID = "omnitools";
    private static CheckinRewardService rewardService;
    private static final TimedEntitlementService TIMED_ENTITLEMENTS = new TimedEntitlementService();
    private static final CheckinMakeupService CHECKIN_MAKEUP_SERVICE = new CheckinMakeupService();
    private static final CdkService CDK_SERVICE = new CdkService(CdkConfig.empty());
    private static OnlineTimeRewardService onlineTimeRewardService;
    private static ShopConfig shopConfig = ShopConfig.empty();
    private static TitleConfig titleConfig = TitleConfig.empty();
    private static TitleEffectConfig titleEffectConfig = TitleEffectConfig.empty();
    private static CloudStorageConfig cloudStorageConfig = CloudStorageConfig.defaultConfig();
    private static AchievementService achievementService = AchievementService.empty();
    private static final SidebarService SIDEBAR_SERVICE = new SidebarService();
    private static final LeaderboardService LEADERBOARD_SERVICE = new LeaderboardService();
    private static final PackageService PACKAGE_SERVICE = new PackageService(ModMindEntry::rewardGrantService);
    private static final RewardGrantService REWARD_GRANT_SERVICE = new RewardGrantService(PACKAGE_SERVICE);
    private static final SkillHudService SKILL_HUD_SERVICE = new SkillHudService();
    private static final DivinationService DIVINATION_SERVICE = new DivinationService();
    private static final SkillTreeService SKILL_TREE_SERVICE = createSkillTreeService();
    private static final ShopPurchaseService SHOP_PURCHASE_SERVICE = new ShopPurchaseService();
    private static final OmniToolsConfigManager CONFIG_MANAGER = new OmniToolsConfigManager();
    private static final ModuleControlService MODULE_CONTROL = new ModuleControlService(CONFIG_MANAGER);
    private static volatile OmniToolsConfigSnapshot configSnapshot = CONFIG_MANAGER.snapshot();
    private static final CommandPermissionService COMMAND_PERMISSIONS = new CommandPermissionService(
            CommandPermissionConfig.defaults());

    private static SkillTreeService createSkillTreeService() {
        SkillTreeService service = new SkillTreeService(SkillTreeConfig.empty());
        service.setFeedbackListener(SKILL_HUD_SERVICE::handleFeedback);
        SKILL_HUD_SERVICE.bind(service);
        return service;
    }

    @Override
    public void onInitialize() {
        ModuleLifecycleRegistrar.register();
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            OperationalErrorReporter.global().flushExpiredSummaries();
            if (isModuleEnabled(ModuleId.PACKAGES)) {
                ModuleFaultBoundary.run(ModuleId.PACKAGES, "server_tick", "skip_current_tick", () -> {
                    packageService().tick(server);
                    if (server.getTickCount() % 1200 == 0) {
                        PackageData.get(server).cleanupHistory(server,
                                configSnapshot.packages().settings().historyRetentionDays());
                    }
                });
            }
            if (isModuleEnabled(ModuleId.SKILLS)) {
                ModuleFaultBoundary.run(ModuleId.SKILLS, "server_tick", "skip_current_tick",
                        () -> SKILL_TREE_SERVICE.tick(server));
                ModuleFaultBoundary.run(ModuleId.SKILLS, "hud_tick", "skip_current_hud_tick",
                        () -> SKILL_HUD_SERVICE.tick(server));
            }
            if (isModuleEnabled(ModuleId.CLOUD_STORAGE)) {
                ModuleFaultBoundary.run(ModuleId.CLOUD_STORAGE, "session_checkpoint_tick", "session_checkpoint_skipped",
                        () -> CloudStorageSessionManager.global().tick(server));
            }
            if (isModuleEnabled(ModuleId.DIVINATION)) {
                ModuleFaultBoundary.run(ModuleId.DIVINATION, "visual_tick", "skip_current_animation_tick",
                        () -> DivinationScreenHandler.tickAnimations(server));
            }
            if (isModuleEnabled(ModuleId.ONLINE_REWARD)) {
                ModuleFaultBoundary.run(ModuleId.ONLINE_REWARD, "server_tick", "skip_current_tick",
                        () -> onlineTimeRewardService().tick(server));
            }
            if (isModuleEnabled(ModuleId.TITLES)) {
                ModuleFaultBoundary.run(ModuleId.TITLES, "server_tick", "skip_current_tick",
                        () -> TIMED_ENTITLEMENTS.tickTitles(server));
            }
            if (isModuleEnabled(ModuleId.TITLE_EFFECTS)) {
                ModuleFaultBoundary.run(ModuleId.TITLE_EFFECTS, "server_tick", "skip_current_tick",
                        () -> TitleEffectService.tick(server));
            }
            if (isModuleEnabled(ModuleId.ACHIEVEMENTS)) {
                ModuleFaultBoundary.run(ModuleId.ACHIEVEMENTS, "server_tick", "skip_current_tick",
                        () -> achievementService().tick(server));
            }
            if (isModuleEnabled(ModuleId.LEADERBOARDS)) {
                ModuleFaultBoundary.run(ModuleId.LEADERBOARDS, "server_tick", "skip_current_tick",
                        () -> leaderboardService().tick(server));
            }
            if (isModuleEnabled(ModuleId.SIDEBAR)) {
                ModuleFaultBoundary.run(ModuleId.SIDEBAR, "server_tick", "skip_current_tick",
                        () -> sidebarService().tick(server));
            }
        });
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (player instanceof ServerPlayer serverPlayer && !serverPlayer.getAbilities().instabuild
                    && !serverPlayer.isSpectator() && isModuleEnabled(ModuleId.SKILLS)) {
                ModuleFaultBoundary.runPlayerEvent(ModuleId.SKILLS, "block_break_xp", serverPlayer,
                        "skip_current_xp_event", () -> {
                            SkillEventRouter.blockBreak(state).ifPresent(route -> {
                                String operation = SkillEventRouter.blockOperation(serverPlayer.getUUID(), pos,
                                        world.getGameTime(), route.skillId());
                                SKILL_TREE_SERVICE.grantSkillXp(serverPlayer, SkillXpEvent.of(serverPlayer.getUUID(),
                                        route.skillId(), route.source(), route.xp(), operation, "block_break", operation));
                            });
                            if (world instanceof net.minecraft.server.level.ServerLevel serverWorld) {
                                SKILL_TREE_SERVICE.settleBlockPassive(serverPlayer, serverWorld, state, pos, blockEntity);
                                SKILL_TREE_SERVICE.settleLumberjackChain(serverPlayer, serverWorld, pos, state);
                            }
                        });
            }
        });
        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((world, entity, killedEntity, damageSource) -> {
            // Player kills and non-hostile entities do not generate combat XP.  This keeps PvP,
            // passive-animal farms and accidental low-value kills outside the progression loop;
            // supported hostile mod mobs normally inherit Monster as well.
            if (entity instanceof ServerPlayer player && !player.getAbilities().instabuild && !player.isSpectator()
                    && killedEntity instanceof net.minecraft.world.entity.monster.Monster
                    && isModuleEnabled(ModuleId.SKILLS)) {
                ModuleFaultBoundary.runPlayerEvent(ModuleId.SKILLS, "entity_kill_xp", player,
                        "skip_current_xp_event", () -> {
                            String heldItem = BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).getPath();
                            SkillEventRouter.CombatRoute route = SkillEventRouter.entityKill(heldItem);
                            String operation = SkillEventRouter.entityOperation(player.getUUID(), killedEntity.getUUID(),
                                    world.getGameTime(), route.skillId());
                            SKILL_TREE_SERVICE.grantSkillXp(player, SkillXpEvent.of(player.getUUID(),
                                    route.skillId(), SkillXpSource.ENTITY_KILL, route.xp(), operation, "entity_kill", operation));
                            SKILL_TREE_SERVICE.settleCombatPassive(player, killedEntity instanceof net.minecraft.world.entity.LivingEntity living ? living : null,
                                    operation);
                        });
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (isModuleEnabled(ModuleId.ONLINE_REWARD)) {
                ModuleFaultBoundary.runPlayerEvent(ModuleId.ONLINE_REWARD, "player_join", player,
                        "online_reward_recovery_deferred", () -> {
                            onlineTimeRewardService().onJoin(player);
                            onlineTimeRewardService().retryPending(player);
                        });
            }
            if (isModuleEnabled(ModuleId.TITLES)) {
                ModuleFaultBoundary.runPlayerEvent(ModuleId.TITLES, "player_join", player,
                        "title_display_refresh_deferred", () -> {
                            titleConfig().rememberPlayer(player.getUUID(), player.getGameProfile().name());
                            TitleDisplayService.refreshPlayer(player);
                        });
            }
            if (isModuleEnabled(ModuleId.TITLE_EFFECTS)) {
                ModuleFaultBoundary.runPlayerEvent(ModuleId.TITLE_EFFECTS, "player_join", player,
                        "title_effect_refresh_deferred", () -> TitleEffectService.refresh(player));
            }
            if (isModuleEnabled(ModuleId.ACHIEVEMENTS)) {
                ModuleFaultBoundary.runPlayerEvent(ModuleId.ACHIEVEMENTS, "player_join", player,
                        "achievement_recovery_deferred", () -> {
                            achievementService().check(player);
                            achievementService().retryPending(player);
                        });
            }
            if (isModuleEnabled(ModuleId.SKILLS)) {
                ModuleFaultBoundary.runPlayerEvent(ModuleId.SKILLS, "player_join", player,
                        "skill_attribute_refresh_deferred", () -> {
                            SKILL_HUD_SERVICE.onJoin(player);
                            SKILL_TREE_SERVICE.refreshAttributes(player);
                        });
            }
            if (isModuleEnabled(ModuleId.DAILY_CHECKIN)) {
                ModuleFaultBoundary.runPlayerEvent(ModuleId.DAILY_CHECKIN, "player_join", player,
                        "checkin_reward_recovery_deferred", () -> {
                            CheckinData.get(player).ensureFirstSeen(player.getUUID(),
                                    CheckinData.today(server).toEpochDay(), player.getGameProfile().name());
                            rewardService().retryPending(player);
                            LocalDate date = CheckinData.today(server);
                            if (!CheckinData.get(server).hasSigned(player.getUUID(), date.toEpochDay())) {
                                sendCheckinReminder(player);
                            }
                        });
            }
            if (isModuleEnabled(ModuleId.CDK)) {
                ModuleFaultBoundary.runPlayerEvent(ModuleId.CDK, "player_join", player,
                        "cdk_reward_recovery_deferred", () -> CDK_SERVICE.retryPending(player));
            }
            if (isModuleEnabled(ModuleId.SIDEBAR)) {
                ModuleFaultBoundary.runPlayerEvent(ModuleId.SIDEBAR, "player_join", player,
                        "sidebar_refresh_deferred", () -> sidebarService().onJoin(player));
            }
            if (isModuleEnabled(ModuleId.LEADERBOARDS)) {
                ModuleFaultBoundary.runPlayerEvent(ModuleId.LEADERBOARDS, "player_join", player,
                        "leaderboard_refresh_deferred", () -> leaderboardService().onJoin(player));
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            // A circuit breaker blocks new cloud-storage interaction, not the authoritative
            // close of a mirror that was already opened.  Skipping this path after a runtime
            // fault would leave the last in-memory page without a final journaled commit.
            if (CloudStorageSessionManager.global().hasActiveSession(player)) {
                ModuleFaultBoundary.runPlayerEvent(ModuleId.CLOUD_STORAGE, "cloud_storage_disconnect_commit", player,
                        "session_journal_retained_for_recovery", () -> CloudStorageSessionManager.global().closeFor(
                                player, CloudStorageCommitService.Reason.DISCONNECT));
            }
            if (isModuleEnabled(ModuleId.TITLES)) {
                ModuleFaultBoundary.runPlayerEvent(ModuleId.TITLES, "player_disconnect", player,
                        "title_state_retained", () -> {
                            TIMED_ENTITLEMENTS.flush(server);
                            TitleDisplayService.onDisconnect(player);
                        });
            }
            if (isModuleEnabled(ModuleId.ACHIEVEMENTS)) {
                ModuleFaultBoundary.runPlayerEvent(ModuleId.ACHIEVEMENTS, "player_disconnect", player,
                        "achievement_menu_snapshot_cleanup_skipped",
                        () -> achievementService().forgetMenuSnapshot(player));
            }
            if (isModuleEnabled(ModuleId.SKILLS)) {
                ModuleFaultBoundary.runPlayerEvent(ModuleId.SKILLS, "player_disconnect", player,
                        "skill_cache_cleanup_skipped", () -> {
                            SKILL_HUD_SERVICE.remove(player);
                            SKILL_TREE_SERVICE.forget(player);
                        });
            }
            if (isModuleEnabled(ModuleId.TITLE_EFFECTS)) {
                ModuleFaultBoundary.runPlayerEvent(ModuleId.TITLE_EFFECTS, "player_disconnect", player,
                        "title_effect_cleanup_skipped", () -> TitleEffectService.remove(player));
            }
            if (isModuleEnabled(ModuleId.ONLINE_REWARD)) {
                ModuleFaultBoundary.runPlayerEvent(ModuleId.ONLINE_REWARD, "player_disconnect", player,
                        "online_reward_state_retained", () -> onlineTimeRewardService().onDisconnect(player));
            }
            if (isModuleEnabled(ModuleId.SIDEBAR)) {
                ModuleFaultBoundary.runPlayerEvent(ModuleId.SIDEBAR, "player_disconnect", player,
                        "sidebar_cleanup_skipped", () -> sidebarService().onDisconnect(player));
            }
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (isModuleEnabled(ModuleId.TITLE_EFFECTS)) {
                ModuleFaultBoundary.runPlayerEvent(ModuleId.TITLE_EFFECTS, "player_respawn", newPlayer,
                        "title_effect_refresh_deferred", () -> {
                            TitleEffectService.forget(oldPlayer);
                            TitleEffectService.refresh(newPlayer);
                        });
            }
            if (isModuleEnabled(ModuleId.TITLES)) {
                ModuleFaultBoundary.runPlayerEvent(ModuleId.TITLES, "player_respawn", newPlayer,
                        "title_display_refresh_deferred", () -> TitleDisplayService.refreshPlayer(newPlayer));
            }
            if (isModuleEnabled(ModuleId.SKILLS)) {
                ModuleFaultBoundary.runPlayerEvent(ModuleId.SKILLS, "player_respawn", newPlayer,
                        "skill_attribute_refresh_deferred", () -> {
                            SKILL_HUD_SERVICE.remove(oldPlayer);
                            SKILL_HUD_SERVICE.onJoin(newPlayer);
                            SKILL_TREE_SERVICE.refreshAttributes(newPlayer);
                        });
            }
            if (isModuleEnabled(ModuleId.SIDEBAR)) {
                ModuleFaultBoundary.runPlayerEvent(ModuleId.SIDEBAR, "player_respawn", newPlayer,
                        "sidebar_refresh_deferred", () -> sidebarService().onJoin(newPlayer));
            }
        });
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(ModMindEntry::broadcastTitledChatMessage);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var command = Commands.literal("omnitools")
                    .requires(COMMAND_PERMISSIONS.requirementAny(CommandAction.CHECKIN_OPEN,
                            CommandAction.ONLINE_OPEN, CommandAction.SHOP_OPEN, CommandAction.SHOP_AUDIT, CommandAction.TITLE_OPEN,
                            CommandAction.TITLE_GRANT, CommandAction.TITLE_REVOKE, CommandAction.STORAGE_OPEN,
                            CommandAction.STORAGE_RECOVERY,
                            CommandAction.ACHIEVEMENTS_OPEN, CommandAction.CURRENCY_BALANCE_SELF,
                            CommandAction.CURRENCY_BALANCE_OTHER, CommandAction.CURRENCY_ADD,
                            CommandAction.CURRENCY_REMOVE, CommandAction.CHECKIN_CLEAR, CommandAction.CONFIG_RELOAD,
                            CommandAction.DIAGNOSE,
                            CommandAction.COMMAND_MENU_OPEN, CommandAction.COMMAND_MENU_CLOSE,
                            CommandAction.SIDEBAR_TOGGLE, CommandAction.SIDEBAR_STATUS, CommandAction.REWARDS_RETRY,
                            CommandAction.REWARDS_ADMIN, CommandAction.CHECKIN_MAKEUP,
                            CommandAction.CHECKIN_CARDS_BUY, CommandAction.CHECKIN_CARDS_ADMIN,
                            CommandAction.CDK_REDEEM, CommandAction.CDK_ADMIN,
                            CommandAction.LEADERBOARDS_OPEN, CommandAction.LEADERBOARDS_CHAT,
                            CommandAction.PACKAGE_OPEN, CommandAction.PACKAGE_GIVE, CommandAction.PACKAGE_INSPECT,
                            CommandAction.PACKAGE_REMOVE, CommandAction.PACKAGE_RESOLVE, CommandAction.PACKAGE_CANCEL,
                            CommandAction.SKILLS_OPEN, CommandAction.SKILLS_ADMIN,
                            CommandAction.DIVINATION_OPEN, CommandAction.DIVINATION_ADMIN))
                    .executes(context -> openCheckinMenu(context.getSource().getPlayerOrException()))
                    .then(Commands.literal("open")
                            .requires(COMMAND_PERMISSIONS.requirement(CommandAction.CHECKIN_OPEN))
                            .executes(context -> openCheckinMenu(context.getSource().getPlayerOrException())))
                    .then(onlineTimeCommand())
                    .then(shopCommand())
                    .then(titleCommand())
                    .then(titleCommand("titles"))
                    .then(cloudStorageCommand("storage"))
                    .then(achievementCommand())
                    .then(leaderboardCommand("leaderboard"))
                    .then(packageCommand())
                    .then(skillTreeCommand())
                    .then(divinationCommand("divination"))
                    .then(checkinCardsAndMakeupCommand())
                    .then(cdkCommand())
                    .then(sidebarCommand())
                    .then(clearCommand())
                    .then(walletCommand("currency"))
                    .then(Commands.literal("balance")
                            .requires(COMMAND_PERMISSIONS.requirementAny(CommandAction.CURRENCY_BALANCE_SELF,
                                    CommandAction.CURRENCY_BALANCE_OTHER))
                            .executes(context -> queryOwnBalance(context.getSource()))
                            .then(targetBalanceArgument()))
                    .then(Commands.literal("add")
                            .requires(COMMAND_PERMISSIONS.requirement(CommandAction.CURRENCY_ADD))
                            .then(currencyChangeArgument(true)))
                    .then(Commands.literal("remove")
                            .requires(COMMAND_PERMISSIONS.requirement(CommandAction.CURRENCY_REMOVE))
                            .then(currencyChangeArgument(false)))
                    .then(Commands.literal("reload")
                            .requires(COMMAND_PERMISSIONS.requirement(CommandAction.CONFIG_RELOAD))
                            .executes(context -> reloadRewards(context.getSource()))
                            .then(Commands.argument("module", StringArgumentType.word())
                                    .executes(context -> reloadModule(context.getSource(),
                                            StringArgumentType.getString(context, "module")))))
                    .then(diagnoseCommand())
                    .then(rewardsCommand())
                    .then(commandMenuCommand())
                    .then(moduleManagerCommand());
            dispatcher.register(command);
            registerCompatibilityAliases(dispatcher);
        });
        System.out.println("[ModMind] omnitools initialized");
    }

    /**
     * Keeps established top-level aliases while placing their ownership behind the module command
     * boundary. The main /omnitools tree remains compatible and can be migrated independently.
     */
    private static void registerCompatibilityAliases(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        ModuleCommandRegistrar commands = new ModuleCommandRegistrar();
        commands.register(ModuleId.DAILY_CHECKIN, target -> target.register(Commands.literal("checkin")
                .requires(COMMAND_PERMISSIONS.requirementAny(CommandAction.CHECKIN_OPEN,
                        CommandAction.ONLINE_OPEN, CommandAction.SHOP_OPEN, CommandAction.SHOP_AUDIT, CommandAction.TITLE_OPEN,
                        CommandAction.TITLE_GRANT, CommandAction.TITLE_REVOKE, CommandAction.STORAGE_OPEN,
                        CommandAction.STORAGE_RECOVERY,
                        CommandAction.ACHIEVEMENTS_OPEN, CommandAction.CURRENCY_BALANCE_SELF,
                        CommandAction.CURRENCY_BALANCE_OTHER, CommandAction.CURRENCY_ADD,
                        CommandAction.CURRENCY_REMOVE, CommandAction.CHECKIN_CLEAR,
                        CommandAction.CHECKIN_MAKEUP, CommandAction.CHECKIN_CARDS_BUY,
                        CommandAction.CHECKIN_CARDS_ADMIN))
                .executes(context -> openCheckinMenu(context.getSource().getPlayerOrException()))
                .then(onlineTimeCommand())
                .then(shopCommand())
                .then(titleCommand())
                .then(cloudStorageCommand("storage"))
                .then(achievementCommand())
                .then(checkinCardsCommand())
                .then(checkinMakeupCommand())
                .then(clearCommand())
                .then(walletCommand("currency"))
                .then(Commands.literal("balance")
                        .requires(COMMAND_PERMISSIONS.requirementAny(CommandAction.CURRENCY_BALANCE_SELF,
                                CommandAction.CURRENCY_BALANCE_OTHER))
                        .executes(context -> queryOwnBalance(context.getSource()))
                        .then(targetBalanceArgument()))));
        commands.register(ModuleId.DAILY_CHECKIN, target -> target.register(walletCommand("money")));
        commands.register(ModuleId.TITLES, target -> {
            target.register(titleCommand());
            target.register(titleCommand("titles"));
        });
        commands.register(ModuleId.CLOUD_STORAGE, target -> {
            target.register(cloudStorageCommand("cloudstorage"));
            target.register(cloudStorageCommand("cstorage"));
        });
        commands.register(ModuleId.LEADERBOARDS, target -> {
            target.register(leaderboardCommand("leaderboard"));
            target.register(topCommand());
        });
        commands.register(ModuleId.PACKAGES, target -> {
            target.register(packageCommand());
            target.register(packageCommand("packages"));
        });
        commands.register(ModuleId.SKILLS, target -> target.register(skillTreeCommand()));
        commands.register(ModuleId.DIVINATION, target -> {
            target.register(divinationCommand("divination"));
            target.register(divinationCommand("fortune"));
        });
        commands.register(ModuleId.DAILY_CHECKIN, target -> target.register(Commands.literal("balance")
                .requires(COMMAND_PERMISSIONS.requirementAny(CommandAction.CURRENCY_BALANCE_SELF,
                        CommandAction.CURRENCY_BALANCE_OTHER))
                .executes(context -> queryOwnBalance(context.getSource()))
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.CURRENCY_BALANCE_OTHER))
                        .executes(context -> queryTargetBalance(context)))));
        commands.registerAll(dispatcher);
    }

    static CheckinRewardService rewardService() {
        if (rewardService == null) {
            rewardService = CheckinRewardService.load();
        }
        return rewardService;
    }

    public static RewardGrantService rewardGrantService() {
        return REWARD_GRANT_SERVICE;
    }

    /** Mixin entry point for completed crafting-result takes. Keeps unsupported menus on a safe no-XP path. */
    public static void recordCraftedItem(net.minecraft.world.entity.player.Player player,
                                         net.minecraft.world.item.ItemStack crafted) {
        if (!(player instanceof ServerPlayer serverPlayer) || crafted == null || crafted.isEmpty()
                || !isModuleEnabled(ModuleId.SKILLS)) {
            return;
        }
        ModuleFaultBoundary.runPlayerEvent(ModuleId.SKILLS, "craft_item_xp", serverPlayer,
                "skip_current_craft_xp_event", () -> SKILL_TREE_SERVICE.recordCrafted(serverPlayer, crafted.getCount()));
    }

    static CheckinMakeupService checkinMakeupService() {
        return CHECKIN_MAKEUP_SERVICE;
    }

    static CdkService cdkService() {
        return CDK_SERVICE;
    }

    static OnlineTimeRewardService onlineTimeRewardService() {
        if (onlineTimeRewardService == null) {
            onlineTimeRewardService = new OnlineTimeRewardService();
        }
        return onlineTimeRewardService;
    }

    static ShopConfig shopConfig() {
        return shopConfig;
    }

    public static TitleConfig titleConfig() {
        return titleConfig;
    }

    public static TitleEffectConfig titleEffectConfig() {
        return titleEffectConfig;
    }

    static CloudStorageConfig cloudStorageConfig() {
        return cloudStorageConfig;
    }

    static AchievementService achievementService() {
        return achievementService;
    }

    static SidebarService sidebarService() {
        return SIDEBAR_SERVICE;
    }

    public static LeaderboardService leaderboardService() {
        return LEADERBOARD_SERVICE;
    }

    static ModuleControlService moduleControlService() {
        return MODULE_CONTROL;
    }

    static TimedEntitlementService timedEntitlements() {
        return TIMED_ENTITLEMENTS;
    }

    static void resetModuleServicesForStartup() {
        rewardService = CheckinRewardService.from(CheckinRewardConfig.empty());
        onlineTimeRewardService = new OnlineTimeRewardService();
        achievementService = AchievementService.empty();
        SKILL_TREE_SERVICE.replace(SkillTreeConfig.empty());
        SKILL_HUD_SERVICE.replaceSettings(SkillTreeConfig.empty().settings().hud());
        DIVINATION_SERVICE.replace(DivinationConfig.empty());
        CDK_SERVICE.replace(CdkConfig.empty());
        LEADERBOARD_SERVICE.replace(LeaderboardConfig.empty());
        OperationalErrorReporter.global().info(OperationalErrorReporter.Context.forFeature("server_starting")
                        .withState("INITIALIZING")
                        .withRecoveryAction("module_services_reset"),
                "initializing module services");
    }

    static void reloadModulesAtStartup(net.minecraft.server.MinecraftServer server) {
        MODULE_CONTROL.reload(server);
    }

    static void logCloudStorageRecovery(CloudStorageJournalData.RecoveryReport storageRecovery) {
        OperationalErrorReporter.global().info(OperationalErrorReporter.Context
                        .forModule(ModuleId.CLOUD_STORAGE, "journal_reconcile")
                        .withState("RECOVERY_APPLIED")
                        .withParameters(java.util.Map.of(
                                "committed", Integer.toString(storageRecovery.committed()),
                                "quarantined", Integer.toString(storageRecovery.quarantined())))
                        .withRecoveryAction("journal_checkpoint_flushed"),
                "cloud storage startup recovery completed");
    }

    static void logServerStarted() {
        OperationalErrorReporter.global().info(OperationalErrorReporter.Context.forFeature("server_started")
                        .withDataVersion("config:" + configSnapshot.root().formatVersion())
                        .withState("ACTIVE")
                        .withParameters(java.util.Map.of("revision", Long.toString(configSnapshot.revision()),
                                "modules", formatModuleStates(configSnapshot)))
                        .withRecoveryAction("enabled_modules_available"),
                "module startup completed");
    }

    static void logServerStopping() {
        OperationalErrorReporter.global().info(OperationalErrorReporter.Context.forFeature("server_stopping")
                        .withState("FLUSHING")
                        .withRecoveryAction("module_state_flush_started"),
                "saving active module state");
    }

    static void logServerStopped() {
        OperationalErrorReporter.global().info(OperationalErrorReporter.Context.forFeature("server_stopping")
                        .withState("COMPLETE")
                        .withRecoveryAction("module_state_flush_finished"),
                "module shutdown completed");
    }

    public static OmniToolsConfigSnapshot configSnapshot() {
        return configSnapshot;
    }

    public static CommandMenuConfig commandMenuConfig() {
        return configSnapshot.commandMenus();
    }

    public static dev.modmind.omnitools.sidebar.SidebarConfig sidebarConfig() {
        return configSnapshot.sidebar();
    }

    public static boolean isModuleEnabled(ModuleId module) {
        return configSnapshot.enabled(module) && ModuleHealthRegistry.global().available(module);
    }

    static boolean isModuleRuntimeDegraded(ModuleId module) {
        return module != null && configSnapshot.enabled(module) && !ModuleHealthRegistry.global().available(module);
    }

    public static ZoneId configuredZone() {
        return configSnapshot.zoneId();
    }

    static ZoneId configuredZone(net.minecraft.server.MinecraftServer server) {
        return configSnapshot.zoneId();
    }

    private static void applySnapshot(OmniToolsConfigSnapshot snapshot) {
        configSnapshot = snapshot;
        ModuleHealthRegistry.global().reset(snapshot);
        ModuleResourceBudget.global().reset();
        ServerText.setLanguage(snapshot.root().language());
        ServerText.setCommonTexts(snapshot.common().texts());
        COMMAND_PERMISSIONS.update(snapshot.commandPermissions());
        rewardService = CheckinRewardService.from(snapshot.rewards());
        shopConfig = snapshot.shop();
        titleConfig = snapshot.titles();
        titleEffectConfig = snapshot.titleEffects();
        cloudStorageConfig = snapshot.cloudStorage();
        CDK_SERVICE.replace(snapshot.cdk());
        LEADERBOARD_SERVICE.replace(snapshot.leaderboards());
        SKILL_TREE_SERVICE.replace(snapshot.skills());
        SKILL_HUD_SERVICE.replaceSettings(snapshot.skills().settings().hud());
        DIVINATION_SERVICE.replace(snapshot.divination());
        // Keep existing achievement menus bound to the live service. Its revision
        // invalidates their cached progress on the next menu refresh after reload.
        achievementService.replace(snapshot.achievements());
    }

    public static PackageService packageService() { return PACKAGE_SERVICE; }

    public static SkillTreeService skillTreeService() { return SKILL_TREE_SERVICE; }

    public static SkillHudService skillHudService() { return SKILL_HUD_SERVICE; }

    public static DivinationService divinationService() { return DIVINATION_SERVICE; }

    public static ShopPurchaseService shopPurchaseService() { return SHOP_PURCHASE_SERVICE; }

    /** Applies one already-validated snapshot for both command reloads and module GUI changes. */
    public static void applyRuntimeConfigChange(net.minecraft.server.MinecraftServer server,
                                                OmniToolsConfigSnapshot previous, OmniToolsConfigSnapshot current) {
        if (previous.enabled(ModuleId.ONLINE_REWARD) && !current.enabled(ModuleId.ONLINE_REWARD)) {
            onlineTimeRewardService().flushAll(server);
        }
        if (previous.enabled(ModuleId.SIDEBAR) && !current.enabled(ModuleId.SIDEBAR)) {
            sidebarService().clearAll(server);
        }
        if (previous.enabled(ModuleId.TITLES) && !current.enabled(ModuleId.TITLES)) {
            TIMED_ENTITLEMENTS.flush(server);
            TitleDisplayService.clearAll(server);
        }
        if (previous.enabled(ModuleId.SKILLS) && !current.enabled(ModuleId.SKILLS)) {
            SKILL_TREE_SERVICE.removeAll(server);
            SKILL_HUD_SERVICE.clearAll(server);
        }
        applySnapshot(current);
        warnPermissiveCommandSecurity(current);
        CheckinData.get(server).applyRetention(current.root().dataRetention(), CheckinData.today(server));
        if (current.root().dataRetention() != dev.modmind.omnitools.config.OmniToolsRootConfig.DataRetention.FULL) {
            rewardGrantService().cleanupProvenCompleted(server);
        }
        PlaceholderBootstrap.registerIfAvailable();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            server.getCommands().sendCommands(player);
        }
        closeDisabledMenus(server, current);
        TitleDisplayService.refreshAll(server);
        if (current.enabled(ModuleId.TITLE_EFFECTS)) {
            TitleEffectService.refreshAll(server);
        } else {
            TitleEffectService.removeAll(server);
        }
        if (current.enabled(ModuleId.ACHIEVEMENTS)) {
            achievementService().checkAll(server);
        }
        if (current.enabled(ModuleId.SKILLS)) {
            SKILL_TREE_SERVICE.refreshAll(server);
            SKILL_HUD_SERVICE.replaceSettings(current.skills().settings().hud());
        } else {
            SKILL_HUD_SERVICE.clearAll(server);
        }
        refreshCommandMenus(server, current);
        if (current.enabled(ModuleId.SIDEBAR)) {
            sidebarService().refreshAll(server);
        } else {
            sidebarService().clearAll(server);
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> commandMenuCommand() {
        return Commands.literal("menu")
                .requires(COMMAND_PERMISSIONS.requirementAny(CommandAction.COMMAND_MENU_OPEN,
                        CommandAction.COMMAND_MENU_CLOSE))
                .then(Commands.literal("open")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.COMMAND_MENU_OPEN)
                                .and(source -> isModuleEnabled(ModuleId.COMMAND_MENU)))
                        .then(Commands.argument("menu_id", StringArgumentType.word())
                                .executes(context -> openCommandMenu(context.getSource(),
                                        StringArgumentType.getString(context, "menu_id")))))
                .executes(context -> openCommandMenu(context.getSource(), "main"))
                .then(Commands.literal("close")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.COMMAND_MENU_CLOSE))
                        .executes(context -> closeCommandMenu(context.getSource())))
                .then(Commands.literal("main")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.COMMAND_MENU_OPEN)
                                .and(source -> isModuleEnabled(ModuleId.COMMAND_MENU)))
                        .executes(context -> openCommandMenu(context.getSource(), "main")));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> onlineTimeCommand() {
        return Commands.literal("online")
                .requires(COMMAND_PERMISSIONS.requirement(CommandAction.ONLINE_OPEN)
                        .and(source -> isModuleEnabled(ModuleId.ONLINE_REWARD)))
                .executes(context -> openOnlineTimeRewardMenu(context.getSource().getPlayerOrException()))
                .then(Commands.literal("rewards")
                        .executes(context -> openOnlineTimeRewardMenu(context.getSource().getPlayerOrException())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> shopCommand() {
        return Commands.literal("shop")
                .requires(COMMAND_PERMISSIONS.requirementAny(CommandAction.SHOP_OPEN, CommandAction.SHOP_AUDIT)
                        .and(source -> isModuleEnabled(ModuleId.SHOP)))
                .executes(context -> openShopMenu(context.getSource().getPlayerOrException()))
                .then(Commands.literal("open")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.SHOP_OPEN))
                        .executes(context -> openShopMenu(context.getSource().getPlayerOrException())))
                .then(Commands.literal("audit")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.SHOP_AUDIT))
                        .executes(context -> listShopPurchaseAudit(context.getSource()))
                        .then(Commands.argument("transaction", StringArgumentType.word())
                                .executes(context -> inspectShopPurchaseAudit(context.getSource(),
                                        StringArgumentType.getString(context, "transaction")))));
    }

    private static int listShopPurchaseAudit(CommandSourceStack source) {
        List<ShopPurchaseData.PurchaseTransaction> transactions = ShopPurchaseData.get(source.getServer()).list();
        long blocked = transactions.stream().filter(transaction -> transaction.status()
                == ShopPurchaseData.Status.BLOCKED).count();
        source.sendSuccess(() -> Component.literal("shop purchases: " + transactions.size() + ", blocked: " + blocked), false);
        transactions.stream().filter(transaction -> transaction.status() == ShopPurchaseData.Status.BLOCKED)
                .sorted(java.util.Comparator.comparingLong(ShopPurchaseData.PurchaseTransaction::updatedAt).reversed())
                .limit(10).forEach(transaction -> source.sendSuccess(() -> Component.literal("blocked "
                        + transaction.transactionId() + " owner=" + transaction.ownerId() + " package="
                        + transaction.packageSnapshot().packageId() + " reason=" + transaction.auditReason()), false));
        return 1;
    }

    private static int inspectShopPurchaseAudit(CommandSourceStack source, String transactionText) {
        UUID transactionId;
        try {
            transactionId = UUID.fromString(transactionText);
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal("invalid shop transaction UUID"));
            return 0;
        }
        ShopPurchaseData.PurchaseTransaction transaction = ShopPurchaseData.get(source.getServer())
                .find(transactionId).orElse(null);
        if (transaction == null) {
            source.sendFailure(Component.literal("shop transaction not found"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("shop transaction=" + transaction.transactionId()
                + " status=" + transaction.status() + " owner=" + transaction.ownerId()
                + " product=" + transaction.productIndex() + " package=" + transaction.packageSnapshot().packageId()
                + " packageVersion=" + transaction.packageSnapshot().packageVersion() + " price=" + transaction.price()
                + " grantKey=" + transaction.grantKey() + " createdAt=" + transaction.createdAt()
                + " updatedAt=" + transaction.updatedAt() + " reason=" + transaction.auditReason()), false);
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> cloudStorageCommand(String literal) {
        return Commands.literal(literal)
                .requires(COMMAND_PERMISSIONS.requirementAny(CommandAction.STORAGE_OPEN,
                        CommandAction.STORAGE_RECOVERY))
                .executes(context -> openCloudStorageMenu(context.getSource().getPlayerOrException()))
                .then(Commands.literal("open")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.STORAGE_OPEN)
                                .and(source -> isModuleEnabled(ModuleId.CLOUD_STORAGE)))
                        .executes(context -> openCloudStorageMenu(context.getSource().getPlayerOrException())))
                .then(Commands.literal("recovery")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.STORAGE_RECOVERY))
                        .then(Commands.literal("list")
                                .executes(context -> listCloudStorageRecovery(context.getSource())))
                        .then(Commands.literal("inspect")
                                .then(Commands.argument("operation", StringArgumentType.word())
                                        .executes(context -> inspectCloudStorageRecovery(context.getSource(),
                                                StringArgumentType.getString(context, "operation")))))
                        .then(Commands.literal("resolve")
                                .then(Commands.argument("operation", StringArgumentType.word())
                                        .then(Commands.literal("commit")
                                                .executes(context -> resolveCloudStorageRecovery(context.getSource(),
                                                        StringArgumentType.getString(context, "operation"),
                                                        CloudStorageJournalData.Resolution.COMMIT)))
                                        .then(Commands.literal("rollback")
                                                .executes(context -> resolveCloudStorageRecovery(context.getSource(),
                                                        StringArgumentType.getString(context, "operation"),
                                                        CloudStorageJournalData.Resolution.ROLLBACK))))));
    }

    private static int listCloudStorageRecovery(CommandSourceStack source) {
        List<CloudStorageJournalData.Entry> entries = CloudStorageJournalData.get(source.getServer()).entries();
        long pending = entries.stream().filter(ModMindEntry::awaitingCloudStorageRecovery).count();
        source.sendSuccess(() -> Component.literal("cloud storage operations=" + entries.size()
                + ", awaiting recovery=" + pending), false);
        entries.stream().filter(ModMindEntry::awaitingCloudStorageRecovery)
                .sorted(java.util.Comparator.comparingLong(CloudStorageJournalData.Entry::updatedAt).reversed())
                .limit(10)
                .forEach(entry -> source.sendSuccess(() -> Component.literal("operation=" + entry.operationId()
                        + " status=" + entry.status() + " type=" + entry.operation() + " owner="
                        + entry.ownerId() + " page=" + (entry.page() + 1) + " reason=" + entry.reason()), false));
        return pending > 0 ? 1 : 0;
    }

    private static boolean awaitingCloudStorageRecovery(CloudStorageJournalData.Entry entry) {
        return entry.status() == CloudStorageJournalData.Status.PREPARED
                || (entry.status() == CloudStorageJournalData.Status.QUARANTINED
                && !entry.resolutionApplied());
    }

    private static int inspectCloudStorageRecovery(CommandSourceStack source, String operationText) {
        UUID operationId;
        try {
            operationId = UUID.fromString(operationText);
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal("invalid cloud storage operation UUID"));
            return 0;
        }
        CloudStorageJournalData.Entry entry = CloudStorageJournalData.get(source.getServer()).find(operationId)
                .orElse(null);
        if (entry == null) {
            source.sendFailure(Component.literal("cloud storage operation not found"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("operation=" + entry.operationId() + " status=" + entry.status()
                + " type=" + entry.operation() + " owner=" + entry.ownerId() + " page=" + (entry.page() + 1)
                + " createdAt=" + entry.createdAt() + " updatedAt=" + entry.updatedAt() + " beforeItems="
                + cloudStorageItemCount(entry.before()) + " afterItems=" + cloudStorageItemCount(entry.after())
                + " session=" + entry.sessionMetadata().sessionId() + " openedHash="
                + entry.sessionMetadata().openedPageHash() + " beforeHash="
                + entry.sessionMetadata().beforePageHash() + " targetHash="
                + entry.sessionMetadata().targetPageHash() + " changedSlots="
                + entry.sessionMetadata().changedSlots() + " sessionChangedSlots="
                + entry.sessionMetadata().sessionChangedSlots() + " checkpoint="
                + entry.sessionMetadata().checkpointAt() + "/" + entry.sessionMetadata().checkpointReason()
                + " reason=" + entry.reason()), false);
        return 1;
    }

    private static int resolveCloudStorageRecovery(CommandSourceStack source, String operationText,
                                                   CloudStorageJournalData.Resolution resolution) {
        UUID operationId;
        try {
            operationId = UUID.fromString(operationText);
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal("invalid cloud storage operation UUID"));
            return 0;
        }
        CloudStorageJournalData.ResolutionResult result = CloudStorageJournalData.get(source.getServer()).resolve(
                source.getServer(), CloudStorageData.get(source.getServer()), operationId, resolution,
                source.getTextName());
        if (!result.resolved()) {
            source.sendFailure(Component.literal("cloud storage recovery rejected: " + result.reason()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("cloud storage operation " + operationId + " resolved as "
                + result.entry().resolution() + "; evidence status=" + result.entry().status()), true);
        return 1;
    }

    private static long cloudStorageItemCount(List<net.minecraft.world.item.ItemStack> items) {
        return items.stream().filter(stack -> !stack.isEmpty()).mapToLong(net.minecraft.world.item.ItemStack::getCount)
                .sum();
    }

    private static LiteralArgumentBuilder<CommandSourceStack> divinationCommand(String literal) {
        return Commands.literal(literal)
                .requires(COMMAND_PERMISSIONS.requirement(CommandAction.DIVINATION_OPEN)
                        .and(source -> isModuleEnabled(ModuleId.DIVINATION)))
                .executes(context -> openDivinationMenu(context.getSource().getPlayerOrException()))
                .then(Commands.literal("open").requires(COMMAND_PERMISSIONS.requirement(CommandAction.DIVINATION_OPEN))
                        .executes(context -> openDivinationMenu(context.getSource().getPlayerOrException())));
    }

    private static int openDivinationMenu(ServerPlayer player) {
        if (!isModuleEnabled(ModuleId.DIVINATION)
                || !COMMAND_PERMISSIONS.canUse(player, CommandAction.DIVINATION_OPEN)) {
            player.displayClientMessage(ServerText.translatable("message.omnitools.module_disabled"), true);
            return 0;
        }
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> DivinationScreenHandler.createServer(syncId, inventory, player),
                ServerText.translatable("gui.omnitools.divination.title")));
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> skillTreeCommand() {
        return Commands.literal("skills")
                .requires(COMMAND_PERMISSIONS.requirementAny(CommandAction.SKILLS_OPEN, CommandAction.SKILLS_ADMIN)
                        .and(source -> isModuleEnabled(ModuleId.SKILLS)))
                .executes(context -> openSkillTreeMenu(context.getSource().getPlayerOrException()))
                .then(Commands.literal("open").requires(COMMAND_PERMISSIONS.requirement(CommandAction.SKILLS_OPEN))
                        .executes(context -> openSkillTreeMenu(context.getSource().getPlayerOrException())))
                .then(Commands.literal("stats").requires(COMMAND_PERMISSIONS.requirement(CommandAction.SKILLS_OPEN))
                        .executes(context -> showSkillStats(context.getSource().getPlayerOrException())))
                .then(Commands.literal("ability").requires(COMMAND_PERMISSIONS.requirement(CommandAction.SKILLS_OPEN))
                        .then(Commands.argument("skill", StringArgumentType.word())
                                .executes(ModMindEntry::showSkillAbility)))
                .then(Commands.literal("health").requires(COMMAND_PERMISSIONS.requirement(CommandAction.SKILLS_ADMIN))
                        .executes(context -> showSkillHealth(context.getSource())))
                .then(Commands.literal("add").requires(COMMAND_PERMISSIONS.requirement(CommandAction.SKILLS_ADMIN))
                        .then(Commands.argument("tree", StringArgumentType.word())
                                .then(Commands.argument("amount", LongArgumentType.longArg(1L, 1_000_000_000L))
                                        .executes(ModMindEntry::addSkillXpToSource))));
    }

    private static int openSkillTreeMenu(ServerPlayer player) {
        if (!isModuleEnabled(ModuleId.SKILLS) || !COMMAND_PERMISSIONS.canUse(player, CommandAction.SKILLS_OPEN)) {
            player.displayClientMessage(ServerText.translatable("message.omnitools.module_disabled"), true);
            return 0;
        }
        player.openMenu(new SimpleMenuProvider((syncId, inventory, ignored) -> SkillTreeScreenHandler.createServer(
                syncId, inventory, player, SKILL_TREE_SERVICE), Component.literal("技能树")));
        return 1;
    }

    private static int showSkillStats(ServerPlayer player) {
        if (!isModuleEnabled(ModuleId.SKILLS) || !COMMAND_PERMISSIONS.canUse(player, CommandAction.SKILLS_OPEN)) {
            player.displayClientMessage(ServerText.translatable("message.omnitools.module_disabled"), true);
            return 0;
        }
        var service = SKILL_TREE_SERVICE;
        MutableComponent message = Component.literal("技能引擎：" + service.engine().serializedName()
                + "  总战力：" + service.getPowerLevel(player)).withStyle(ChatFormatting.GOLD);
        for (SkillTreeConfig.TreeDefinition tree : service.config().trees()) {
            var progress = service.progress(player, tree.id());
            message = message.append(Component.literal("\n" + tree.display() + " Lv." + progress.level()
                    + "（" + progress.currentXp() + "/" + (progress.level() >= service.config().settings().maxLevel()
                    ? "-" : Long.toString(service.xpRequired(tree, progress.level()))) + " XP）")
                    .withStyle(ChatFormatting.GRAY));
        }
        player.sendSystemMessage(message);
        return 1;
    }

    private static int showSkillAbility(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String skill = StringArgumentType.getString(context, "skill");
        if (!isModuleEnabled(ModuleId.SKILLS) || !COMMAND_PERMISSIONS.canUse(player, CommandAction.SKILLS_OPEN)) {
            context.getSource().sendFailure(ServerText.translatable("message.omnitools.module_disabled"));
            return 0;
        }
        if (SKILL_TREE_SERVICE.config().tree(skill).isEmpty()) {
            context.getSource().sendFailure(Component.literal("未知技能：" + skill));
            return 0;
        }
        McmmoSkillModule.AbilitySnapshot snapshot = SKILL_TREE_SERVICE.abilitySnapshot(player, skill);
        context.getSource().sendSuccess(() -> Component.literal("技能 " + snapshot.skillId()
                + "：专业等级 " + snapshot.professionLevel() + "，能力等级 " + snapshot.abilityLevel()
                + "，冷却 " + snapshot.cooldownRemainingSeconds() + " 秒，生效 "
                + snapshot.activeRemainingSeconds() + " 秒"), false);
        return 1;
    }

    private static int showSkillHealth(CommandSourceStack source) {
        if (!isModuleEnabled(ModuleId.SKILLS) || !COMMAND_PERMISSIONS.canUse(source, CommandAction.SKILLS_ADMIN)) {
            source.sendFailure(ServerText.translatable("message.omnitools.module_disabled"));
            return 0;
        }
        var health = SKILL_TREE_SERVICE.xpHealth(source.getServer());
        var transactions = health.transactions();
        source.sendSuccess(() -> Component.literal("技能经验健康：观察 " + health.observedEvents()
                + "，成功 " + health.grantedEvents() + "（" + health.grantedXp() + " XP），拒绝 "
                + health.rejectedEvents() + "；事务 PREPARED=" + transactions.prepared()
                + "，COMMITTED=" + transactions.committed() + "，ROLLED_BACK=" + transactions.rolledBack())
                .withStyle(ChatFormatting.GOLD), false);
        if (!health.statusCounts().isEmpty()) {
            source.sendSuccess(() -> Component.literal("结果：" + health.statusCounts()
                    + "；来源：" + health.sourceCounts()).withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static int addSkillXpToSource(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String tree = StringArgumentType.getString(context, "tree");
        long amount = LongArgumentType.getLong(context, "amount");
        String operation = "command:skill_xp:" + player.getUUID() + ":" + tree + ":" + amount + ":" + player.level().getGameTime();
        SkillTreeService.XpResult result = SKILL_TREE_SERVICE.grantSkillXp(player,
                SkillXpEvent.of(player.getUUID(), tree, SkillXpSource.COMMAND, amount, operation,
                        "admin_command", operation));
        if (!result.granted()) {
            context.getSource().sendFailure(Component.literal("技能经验未发放：" + result.status().name().toLowerCase(java.util.Locale.ROOT)));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("已发放 " + result.acceptedXp() + " 点 " + tree
                + " 技能经验，当前等级 " + result.progress().level()), true);
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> achievementCommand() {
        return Commands.literal("achievements")
                .requires(COMMAND_PERMISSIONS.requirement(CommandAction.ACHIEVEMENTS_OPEN)
                        .and(source -> isModuleEnabled(ModuleId.ACHIEVEMENTS)))
                .executes(context -> openAchievementMenu(context.getSource().getPlayerOrException()))
                .then(Commands.literal("open")
                        .executes(context -> openAchievementMenu(context.getSource().getPlayerOrException())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> leaderboardCommand(String literal) {
        return Commands.literal(literal)
                .requires(COMMAND_PERMISSIONS.requirementAny(CommandAction.LEADERBOARDS_OPEN,
                        CommandAction.LEADERBOARDS_CHAT).and(source -> isModuleEnabled(ModuleId.LEADERBOARDS)))
                .executes(context -> openLeaderboardFromSource(context.getSource(), ""))
                .then(Commands.literal("open")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.LEADERBOARDS_OPEN))
                        .executes(context -> openLeaderboardFromSource(context.getSource(), ""))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(context -> openLeaderboardFromSource(context.getSource(),
                                        StringArgumentType.getString(context, "id")))))
                .then(Commands.literal("list")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.LEADERBOARDS_OPEN))
                        .executes(context -> listLeaderboards(context.getSource())))
                .then(Commands.literal("chat")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.LEADERBOARDS_CHAT))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(context -> sendLeaderboardChat(context.getSource(),
                                        StringArgumentType.getString(context, "id"), 1))
                                .then(Commands.argument("page", LongArgumentType.longArg(1L, 100_000L))
                                        .executes(context -> sendLeaderboardChat(context.getSource(),
                                                StringArgumentType.getString(context, "id"),
                                                LongArgumentType.getLong(context, "page"))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> packageCommand() {
        return packageCommand("package");
    }

    private static LiteralArgumentBuilder<CommandSourceStack> packageCommand(String literal) {
        return Commands.literal(literal)
                .requires(COMMAND_PERMISSIONS.requirementAny(CommandAction.PACKAGE_OPEN, CommandAction.PACKAGE_GIVE,
                        CommandAction.PACKAGE_INSPECT, CommandAction.PACKAGE_REMOVE, CommandAction.PACKAGE_RESOLVE,
                        CommandAction.PACKAGE_CANCEL)
                        .and(source -> isModuleEnabled(ModuleId.PACKAGES)))
                .executes(context -> openPackageFromSource(context.getSource()))
                .then(Commands.literal("open").requires(COMMAND_PERMISSIONS.requirement(CommandAction.PACKAGE_OPEN))
                        .executes(context -> openPackageFromSource(context.getSource())))
                .then(Commands.literal("give").requires(COMMAND_PERMISSIONS.requirement(CommandAction.PACKAGE_GIVE))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("package", StringArgumentType.word())
                                        .executes(context -> givePackage(context, 1))
                                        .then(Commands.argument("amount", LongArgumentType.longArg(1L, 4096L))
                                                .executes(context -> givePackage(context, (int) LongArgumentType.getLong(context, "amount"))))))
                        )
                .then(Commands.literal("inspect").requires(COMMAND_PERMISSIONS.requirement(CommandAction.PACKAGE_INSPECT))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(context -> inspectPackages(context))
                                .then(Commands.argument("instance", StringArgumentType.word())
                                        .executes(context -> inspectPackageInstance(context)))))
                .then(Commands.literal("list").requires(COMMAND_PERMISSIONS.requirement(CommandAction.PACKAGE_INSPECT))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(context -> listPackages(context, "", 1))
                                .then(Commands.argument("status", StringArgumentType.word())
                                        .executes(context -> listPackages(context, StringArgumentType.getString(context, "status"), 1))
                                        .then(Commands.argument("page", LongArgumentType.longArg(1L, 100_000L))
                                                .executes(context -> listPackages(context, StringArgumentType.getString(context, "status"),
                                                        (int) LongArgumentType.getLong(context, "page")))))))
                .then(Commands.literal("remove").requires(COMMAND_PERMISSIONS.requirement(CommandAction.PACKAGE_REMOVE))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("instance", StringArgumentType.word())
                                         .executes(context -> removePackage(context)))))
                .then(Commands.literal("resolve").requires(COMMAND_PERMISSIONS.requirement(CommandAction.PACKAGE_RESOLVE))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("instance", StringArgumentType.word())
                                        .then(Commands.argument("stack", StringArgumentType.word())
                                                .then(Commands.literal("delivered")
                                                        .then(Commands.literal("confirm").executes(context -> resolvePackage(context, true))))
                                                .then(Commands.literal("pending")
                                                        .then(Commands.literal("confirm").executes(context -> resolvePackage(context, false))))))))
                .then(Commands.literal("cancel").requires(COMMAND_PERMISSIONS.requirement(CommandAction.PACKAGE_CANCEL))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("instance", StringArgumentType.word())
                                        .then(Commands.literal("confirm")
                                                .executes(ModMindEntry::cancelPackage)))));
    }

    private static int openPackageFromSource(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return openPackageMenu(source.getPlayerOrException());
    }
    private static int openPackageMenu(ServerPlayer player) {
        if (!isModuleEnabled(ModuleId.PACKAGES) || !COMMAND_PERMISSIONS.canUse(player, CommandAction.PACKAGE_OPEN)) {
            player.displayClientMessage(ServerText.translatable("message.omnitools.module_disabled"), true); return 0;
        }
        player.openMenu(new SimpleMenuProvider((syncId, inventory, ignored) -> PackageScreenHandler.createServer(syncId, inventory, player),
                ServerText.translatable("gui.omnitools.packages.title"))); return 1;
    }
    private static int givePackage(CommandContext<CommandSourceStack> context, int count) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String id = StringArgumentType.getString(context, "package");
        List<NameAndId> profiles = GameProfileArgument.getGameProfiles(context, "player").stream().toList();
        if (profiles.isEmpty()) {
            context.getSource().sendFailure(Component.literal("no target players"));
            return 0;
        }
        for (NameAndId profile : profiles) {
            Optional<String> failure = PACKAGE_SERVICE.preflightCreate(context.getSource().getServer(), profile.id(), id, count);
            if (failure.isPresent()) {
                context.getSource().sendFailure(Component.literal(profile.name() + ": " + failure.get()));
                return 0;
            }
        }
        UUID operationId = UUID.randomUUID();
        List<PackageInstance> created = new ArrayList<>();
        try {
            for (NameAndId profile : profiles) {
                for (int i = 0; i < count; i++) {
                    created.add(PACKAGE_SERVICE.create(context.getSource().getServer(), profile.id(), id,
                            "admin:" + context.getSource().getTextName() + ":" + operationId));
                }
            }
        } catch (RuntimeException exception) {
            PackageData data = PackageData.get(context.getSource().getServer());
            for (PackageInstance instance : created) data.remove(instance.ownerId(), instance.instanceId());
            context.getSource().sendFailure(Component.literal("package give rolled back (" + operationId + "): "
                    + exception.getMessage()));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("package give " + operationId + ": created " + created.size()), true);
        return 1;
    }
    private static int inspectPackages(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        int total = 0; for (NameAndId profile : GameProfileArgument.getGameProfiles(context, "player")) total += PackageData.get(context.getSource().getServer()).list(profile.id()).size();
        int packageCount = total;
        context.getSource().sendSuccess(() -> Component.literal("packages: " + packageCount), false); return 1;
    }

    private static int listPackages(CommandContext<CommandSourceStack> context, String statusFilter, int page)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        PackageInstance.Status requested = null;
        if (statusFilter != null && !statusFilter.isBlank()) {
            try { requested = PackageInstance.Status.valueOf(statusFilter.trim().toUpperCase(java.util.Locale.ROOT)); }
            catch (IllegalArgumentException exception) { context.getSource().sendFailure(Component.literal("unknown package status: " + statusFilter)); return 0; }
        }
        final PackageInstance.Status filter = requested;
        int offset = Math.max(0, page - 1) * 20;
        int shown = 0;
        for (NameAndId profile : GameProfileArgument.getGameProfiles(context, "player")) {
            List<PackageInstance> matches = PackageData.get(context.getSource().getServer()).list(profile.id()).stream()
                    .filter(instance -> filter == null || instance.status() == filter).toList();
            context.getSource().sendSuccess(() -> Component.literal(profile.name() + " packages=" + matches.size()), false);
            for (int i = offset; i < Math.min(matches.size(), offset + 20); i++) {
                PackageInstance instance = matches.get(i);
                context.getSource().sendSuccess(() -> Component.literal(instance.instanceId() + " " + instance.status()
                        + " " + instance.packageId()), false);
                shown++;
            }
        }
        return shown > 0 ? 1 : 0;
    }

    private static int inspectPackageInstance(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        UUID id;
        try { id = UUID.fromString(StringArgumentType.getString(context, "instance")); }
        catch (IllegalArgumentException exception) { context.getSource().sendFailure(Component.literal("invalid instance UUID")); return 0; }
        PackageData data = PackageData.get(context.getSource().getServer());
        for (NameAndId profile : GameProfileArgument.getGameProfiles(context, "player")) {
            PackageInstance instance = data.find(profile.id(), id).orElse(null);
            if (instance == null) { context.getSource().sendFailure(Component.literal(profile.name() + ": instance not found")); continue; }
            context.getSource().sendSuccess(() -> Component.literal(formatPackageInspection(data, instance)), false);
        }
        return 1;
    }

    private static String formatPackageInspection(PackageData data, PackageInstance instance) {
        StringBuilder result = new StringBuilder("instance=").append(instance.instanceId())
                .append(" package=").append(instance.packageId()).append(" version=").append(instance.packageVersion())
                .append(" owner=").append(instance.ownerId()).append(" status=").append(instance.status())
                .append(" grantKey=").append(instance.grantKey()).append(" selected=").append(instance.selectedItemIndex())
                .append(" grantedAt=").append(instance.grantedAt());
        data.findDeliveryBatch(instance.instanceId()).ifPresent(batch -> {
            result.append(" batch=").append(batch.batchId()).append(" batchStatus=").append(batch.status());
            for (PackageDeliveryBatch.StackEntry stack : batch.stacks()) {
                result.append(" [stack=").append(stack.stackId()).append(" total=").append(stack.quantity())
                        .append(" delivered=").append(stack.deliveredQuantity()).append(" status=").append(stack.status()).append(']');
            }
        });
        return result.toString();
    }

    private static int resolvePackage(CommandContext<CommandSourceStack> context, boolean delivered)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        UUID instanceId;
        UUID stackId;
        try {
            instanceId = UUID.fromString(StringArgumentType.getString(context, "instance"));
            stackId = UUID.fromString(StringArgumentType.getString(context, "stack"));
        } catch (IllegalArgumentException exception) { context.getSource().sendFailure(Component.literal("invalid UUID")); return 0; }
        boolean changed = false;
        for (NameAndId profile : GameProfileArgument.getGameProfiles(context, "player")) {
            changed |= PackageData.get(context.getSource().getServer()).resolveStack(profile.id(), instanceId, stackId,
                    delivered, context.getSource().getServer(), context.getSource().getTextName()).isPresent();
        }
        if (!changed) context.getSource().sendFailure(Component.literal("only BLOCKED stacks can be resolved"));
        return changed ? 1 : 0;
    }

    private static int cancelPackage(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        UUID instanceId;
        try { instanceId = UUID.fromString(StringArgumentType.getString(context, "instance")); }
        catch (IllegalArgumentException exception) { context.getSource().sendFailure(Component.literal("invalid instance UUID")); return 0; }
        boolean removed = false;
        for (NameAndId profile : GameProfileArgument.getGameProfiles(context, "player")) {
            removed |= PackageData.get(context.getSource().getServer()).cancel(profile.id(), instanceId,
                    context.getSource().getServer(), context.getSource().getTextName());
        }
        if (!removed) context.getSource().sendFailure(Component.literal("only BLOCKED packages without uncertain stacks can be cancelled"));
        return removed ? 1 : 0;
    }
    private static int removePackage(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        UUID id; try { id = UUID.fromString(StringArgumentType.getString(context, "instance")); } catch (IllegalArgumentException e) { return 0; }
        boolean removed = false;
        for (NameAndId profile : GameProfileArgument.getGameProfiles(context, "player")) {
            boolean current = PackageData.get(context.getSource().getServer()).remove(profile.id(), id);
            removed |= current;
            if (current) {
                PackageAuditLog.write(context.getSource().getServer(), "remove",
                        "operator=" + context.getSource().getTextName() + " owner=" + profile.id()
                                + " instance=" + id);
            }
        }
        return removed ? 1 : 0;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> topCommand() {
        return Commands.literal("top")
                .requires(COMMAND_PERMISSIONS.requirement(CommandAction.LEADERBOARDS_CHAT)
                        .and(source -> isModuleEnabled(ModuleId.LEADERBOARDS)))
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(context -> sendLeaderboardChat(context.getSource(),
                                StringArgumentType.getString(context, "id"), 1))
                        .then(Commands.argument("page", LongArgumentType.longArg(1L, 100_000L))
                                .executes(context -> sendLeaderboardChat(context.getSource(),
                                        StringArgumentType.getString(context, "id"),
                                        LongArgumentType.getLong(context, "page")))));
    }

    private static int listLeaderboards(CommandSourceStack source) {
        var boards = leaderboardService().boards();
        if (boards.isEmpty()) {
            source.sendFailure(ServerText.translatable("command.omnitools.leaderboard.empty"));
            return 0;
        }
        source.sendSuccess(() -> ServerText.translatable("command.omnitools.leaderboard.list", boards.size()), false);
        for (var board : boards) {
            source.sendSuccess(() -> Component.literal("- " + board.definition().id() + ": ")
                    .append(board.definition().display()), false);
        }
        return 1;
    }

    private static int openLeaderboardFromSource(CommandSourceStack source, String boardId) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(ServerText.translatable("command.omnitools.leaderboard.player_only"));
            return 0;
        }
        return openLeaderboardMenu(player, boardId);
    }

    private static int sendLeaderboardChat(CommandSourceStack source, String boardId, long requestedPage) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(ServerText.translatable("command.omnitools.leaderboard.player_only"));
            return 0;
        }
        if (!isModuleEnabled(ModuleId.LEADERBOARDS)
                || !COMMAND_PERMISSIONS.canUse(player, CommandAction.LEADERBOARDS_CHAT)) {
            player.displayClientMessage(ServerText.translatable("message.omnitools.module_disabled"), true);
            return 0;
        }
        var board = leaderboardService().board(boardId).orElse(null);
        if (board == null) {
            source.sendFailure(ServerText.translatable("command.omnitools.leaderboard.unknown", boardId));
            return 0;
        }
        final int pageSize = 10;
        int pages = Math.max(1, (board.entries().size() + pageSize - 1) / pageSize);
        int page = (int) Math.max(1L, Math.min((long) pages, requestedPage));
        int first = (page - 1) * pageSize;
        source.sendSuccess(() -> TextTemplateRenderer.render(player, board.definition().display()).copy()
                .append(Component.literal(" [" + page + "/" + pages + "]").withStyle(ChatFormatting.GRAY)), false);
        if (board.entries().isEmpty()) {
            source.sendSuccess(() -> ServerText.translatable("command.omnitools.leaderboard.ranking_empty")
                    .withStyle(ChatFormatting.GRAY), false);
        }
        for (int index = first; index < board.entries().size() && index < first + pageSize; index++) {
            var entry = board.entries().get(index);
            source.sendSuccess(() -> Component.literal("#" + entry.rank() + " " + entry.playerName() + " ")
                    .append(Component.literal(board.format(entry.value())).withStyle(ChatFormatting.AQUA)), false);
        }
        var navigation = Component.empty();
        if (page > 1) {
            navigation.append(ServerText.translatable("command.omnitools.leaderboard.previous").withStyle(style -> style.withColor(ChatFormatting.AQUA)
                    .withClickEvent(new ClickEvent.RunCommand("/top " + board.definition().id() + " " + (page - 1)))));
        }
        if (page > 1 && page < pages) {
            navigation.append(Component.literal(" "));
        }
        if (page < pages) {
            navigation.append(ServerText.translatable("command.omnitools.leaderboard.next").withStyle(style -> style.withColor(ChatFormatting.AQUA)
                    .withClickEvent(new ClickEvent.RunCommand("/top " + board.definition().id() + " " + (page + 1)))));
        }
        navigation.append(Component.literal(" ").append(ServerText.translatable("command.omnitools.leaderboard.open"))
                .withStyle(style -> style.withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent.RunCommand("/omnitools leaderboard open " + board.definition().id()))));
        source.sendSuccess(() -> navigation, false);
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> sidebarCommand() {
        return Commands.literal("sidebar")
                .requires(COMMAND_PERMISSIONS.requirementAny(CommandAction.SIDEBAR_TOGGLE,
                        CommandAction.SIDEBAR_STATUS))
                .then(Commands.literal("on")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.SIDEBAR_TOGGLE))
                        .executes(context -> setSidebar(context.getSource(), true)))
                .then(Commands.literal("off")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.SIDEBAR_TOGGLE))
                        .executes(context -> setSidebar(context.getSource(), false)))
                .then(Commands.literal("toggle")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.SIDEBAR_TOGGLE))
                        .executes(context -> toggleSidebar(context.getSource())))
                .then(Commands.literal("status")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.SIDEBAR_STATUS))
                        .executes(context -> sidebarStatus(context.getSource())));
    }

    private static int setSidebar(CommandSourceStack source, boolean visible) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(ServerText.translatable("command.omnitools.sidebar.player_only"));
            return 0;
        }
        if (!isModuleEnabled(ModuleId.SIDEBAR)) {
            player.displayClientMessage(ServerText.translatable("command.omnitools.sidebar.module_disabled"), true);
            return 0;
        }
        sidebarService().setVisible(player, visible);
        player.displayClientMessage(ServerText.translatable(visible
                ? "command.omnitools.sidebar.enabled_result" : "command.omnitools.sidebar.disabled_result"), true);
        return 1;
    }

    private static int toggleSidebar(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(ServerText.translatable("command.omnitools.sidebar.player_only"));
            return 0;
        }
        if (!isModuleEnabled(ModuleId.SIDEBAR)) {
            player.displayClientMessage(ServerText.translatable("command.omnitools.sidebar.module_disabled"), true);
            return 0;
        }
        boolean visible = !sidebarService().isVisible(player);
        sidebarService().setVisible(player, visible);
        player.displayClientMessage(ServerText.translatable(visible
                ? "command.omnitools.sidebar.enabled_result" : "command.omnitools.sidebar.disabled_result"), true);
        return 1;
    }

    private static int sidebarStatus(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(ServerText.translatable("command.omnitools.sidebar.player_only"));
            return 0;
        }
        boolean visible = sidebarService().isVisible(player);
        source.sendSuccess(() -> ServerText.translatable("command.omnitools.sidebar.status",
                ServerText.translatable(visible ? "command.omnitools.sidebar.enabled" : "command.omnitools.sidebar.disabled")), false);
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> moduleManagerCommand() {
        return Commands.literal("modules")
                .requires(COMMAND_PERMISSIONS.requirement(CommandAction.CONFIG_RELOAD))
                .executes(context -> openModuleManagerMenu(context.getSource()));
    }

    private static boolean hasCloudStoragePermission(CommandSourceStack source) {
        return COMMAND_PERMISSIONS.canUse(source, CommandAction.STORAGE_OPEN);
    }

    static boolean hasCloudStoragePermissionForPlayer(ServerPlayer player) {
        return hasCloudStoragePermission(player.createCommandSourceStack());
    }

    public static boolean hasCommandPermission(ServerPlayer player, CommandAction action) {
        return COMMAND_PERMISSIONS.canUse(player, action);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> titleCommand() {
        return titleCommand("title");
    }

    private static LiteralArgumentBuilder<CommandSourceStack> titleCommand(String literal) {
        return Commands.literal(literal)
                .requires(COMMAND_PERMISSIONS.requirementAny(CommandAction.TITLE_OPEN, CommandAction.TITLE_GRANT,
                        CommandAction.TITLE_REVOKE).and(source -> isModuleEnabled(ModuleId.TITLES)))
                .executes(context -> openTitleMenu(context.getSource().getPlayerOrException()))
                .then(Commands.literal("open")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.TITLE_OPEN))
                        .executes(context -> openTitleMenu(context.getSource().getPlayerOrException())))
                .then(Commands.literal("time")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.TITLE_OPEN))
                        .executes(context -> titleTime(context.getSource())))
                .then(Commands.literal("select")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.TITLE_OPEN))
                        .then(Commands.argument("title", StringArgumentType.word())
                                .executes(ModMindEntry::selectTitle)))
                .then(Commands.literal("clear")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.TITLE_OPEN))
                        .executes(context -> clearTitleSelection(context.getSource())))
                .then(Commands.literal("give")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.TITLE_GRANT))
                        .then(titleChangeArgument(true)))
                .then(Commands.literal("add")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.TITLE_GRANT))
                        .then(titleChangeArgument(true)))
                .then(Commands.literal("remove")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.TITLE_REVOKE))
                        .then(titleChangeArgument(false)))
                .then(Commands.literal("take")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.TITLE_REVOKE))
                        .then(titleChangeArgument(false)))
                .then(Commands.literal("admin")
                        .requires(COMMAND_PERMISSIONS.requirementAny(CommandAction.TITLE_GRANT,
                                CommandAction.TITLE_REVOKE))
                        .then(Commands.literal("grant")
                                .requires(COMMAND_PERMISSIONS.requirement(CommandAction.TITLE_GRANT))
                                .then(timedTitleGrantArgument()))
                        .then(Commands.literal("revoke")
                                .requires(COMMAND_PERMISSIONS.requirement(CommandAction.TITLE_REVOKE))
                                .then(titleChangeArgument(false))));
    }

    /** Shared path for /omnitools checkin cards|makeup, intentionally separate from the GUI. */
    private static LiteralArgumentBuilder<CommandSourceStack> checkinCardsAndMakeupCommand() {
        return Commands.literal("checkin")
                .requires(COMMAND_PERMISSIONS.requirementAny(CommandAction.CHECKIN_OPEN,
                        CommandAction.CHECKIN_MAKEUP, CommandAction.CHECKIN_CARDS_BUY,
                        CommandAction.CHECKIN_CARDS_ADMIN))
                .executes(context -> openCheckinMenu(context.getSource().getPlayerOrException()))
                .then(checkinCardsCommand())
                .then(checkinMakeupCommand());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> checkinCardsCommand() {
        return Commands.literal("cards")
                .requires(COMMAND_PERMISSIONS.requirementAny(CommandAction.CHECKIN_MAKEUP,
                        CommandAction.CHECKIN_CARDS_BUY, CommandAction.CHECKIN_CARDS_ADMIN)
                        .and(source -> isModuleEnabled(ModuleId.DAILY_CHECKIN)))
                .executes(context -> showMakeupCards(context.getSource()))
                .then(Commands.literal("buy")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.CHECKIN_CARDS_BUY))
                        .then(Commands.argument("amount", LongArgumentType.longArg(1L, 1_000_000L))
                                .executes(context -> buyMakeupCards(context.getSource(),
                                        LongArgumentType.getLong(context, "amount")))))
                .then(Commands.literal("admin")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.CHECKIN_CARDS_ADMIN))
                        .then(Commands.literal("give")
                                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                        .then(Commands.argument("amount", LongArgumentType.longArg(1L, 1_000_000L))
                                                .executes(context -> changeMakeupCards(context, true)))))
                        .then(Commands.literal("take")
                                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                        .then(Commands.argument("amount", LongArgumentType.longArg(1L, 1_000_000L))
                                                .executes(context -> changeMakeupCards(context, false))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> checkinMakeupCommand() {
        return Commands.literal("makeup")
                .requires(COMMAND_PERMISSIONS.requirement(CommandAction.CHECKIN_MAKEUP)
                        .and(source -> isModuleEnabled(ModuleId.DAILY_CHECKIN)))
                .then(Commands.argument("date", StringArgumentType.word())
                        .executes(context -> makeupCheckin(context.getSource(),
                                StringArgumentType.getString(context, "date"))));
    }

    private static int showMakeupCards(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        CheckinMakeupService.CardStatus status = checkinMakeupService().status(player);
        source.sendSuccess(() -> ServerText.translatable("command.omnitools.checkin.cards.status", status.cards(),
                status.maxCards(), status.monthlyUses(), status.maxMonthlyUses()), false);
        return 1;
    }

    private static int buyMakeupCards(CommandSourceStack source, long amount)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        CheckinData.MakeupPurchaseResult result = checkinMakeupService().buy(player, amount);
        if (!result.applied()) {
            String key = switch (result.status()) {
                case DISABLED -> "command.omnitools.checkin.cards.purchase_disabled";
                case CARD_LIMIT -> "command.omnitools.checkin.cards.limit";
                case INSUFFICIENT_CURRENCY -> "command.omnitools.checkin.cards.insufficient";
                case APPLIED -> throw new IllegalStateException("handled above");
            };
            source.sendFailure(ServerText.translatable(key));
            return 0;
        }
        source.sendSuccess(() -> ServerText.translatable("command.omnitools.checkin.cards.bought", amount,
                result.cost(), result.cards(), result.balance()), false);
        return 1;
    }

    private static int changeMakeupCards(CommandContext<CommandSourceStack> context, boolean give)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        long amount = LongArgumentType.getLong(context, "amount");
        int changed = 0;
        for (NameAndId profile : GameProfileArgument.getGameProfiles(context, "player")) {
            CheckinData data = CheckinData.get(context.getSource().getServer());
            if (give) {
                CheckinData.MakeupCardResult result = data.addMakeupCards(profile.id(), amount,
                        rewardService().makeup().maxCards(), profile.name());
                if (result == CheckinData.MakeupCardResult.LIMIT_REACHED) {
                    context.getSource().sendFailure(ServerText.translatable("command.omnitools.checkin.cards.admin_limit",
                            profile.name()));
                    continue;
                }
                changed++;
            } else {
                data.removeMakeupCards(profile.id(), amount, profile.name());
                changed++;
            }
            long balance = data.getMakeupCards(profile.id());
            context.getSource().sendSuccess(() -> ServerText.translatable(give
                    ? "command.omnitools.checkin.cards.given" : "command.omnitools.checkin.cards.taken",
                    amount, profile.name(), balance), true);
        }
        return changed;
    }

    private static int makeupCheckin(CommandSourceStack source, String dateText)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        LocalDate date;
        try {
            date = LocalDate.parse(dateText);
        } catch (java.time.format.DateTimeParseException exception) {
            source.sendFailure(ServerText.translatable("command.omnitools.checkin.makeup.invalid_date"));
            return 0;
        }
        ServerPlayer player = source.getPlayerOrException();
        CheckinData.MakeupResult result = checkinMakeupService().makeup(player, date);
        if (!result.applied()) {
            source.sendFailure(ServerText.translatable("command.omnitools.checkin.makeup."
                    + result.status().name().toLowerCase(java.util.Locale.ROOT)));
            return 0;
        }
        CheckinMakeupService.CardStatus cards = checkinMakeupService().status(player);
        source.sendSuccess(() -> ServerText.translatable("command.omnitools.checkin.makeup.success", date,
                result.stats().streakDays(), cards.cards()), false);
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> cdkCommand() {
        return Commands.literal("cdk")
                .requires(COMMAND_PERMISSIONS.requirementAny(CommandAction.CDK_REDEEM, CommandAction.CDK_ADMIN)
                        .and(source -> isModuleEnabled(ModuleId.CDK)))
                .then(Commands.literal("redeem")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.CDK_REDEEM))
                        .then(Commands.argument("code", StringArgumentType.word())
                                .executes(context -> redeemCdk(context.getSource(),
                                        StringArgumentType.getString(context, "code")))))
                .then(Commands.literal("status")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.CDK_REDEEM))
                        .executes(context -> cdkStatus(context.getSource())))
                .then(Commands.literal("admin")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.CDK_ADMIN))
                        .then(Commands.literal("list").executes(context -> cdkAuditList(context.getSource())))
                        .then(Commands.literal("audit")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(context -> cdkAudit(context.getSource(),
                                                StringArgumentType.getString(context, "id"))))));
    }

    private static int redeemCdk(CommandSourceStack source, String code)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        CdkService.RedemptionResult result = cdkService().redeem(player, code);
        if (result.status() == CdkService.Status.SUCCESS) {
            source.sendSuccess(() -> ServerText.translatable("command.omnitools.cdk.redeem.success", result.granted()), false);
            return 1;
        }
        if (result.status() == CdkService.Status.PENDING) {
            source.sendSuccess(() -> ServerText.translatable("command.omnitools.cdk.redeem.pending"), false);
            return 1;
        }
        source.sendFailure(ServerText.translatable("command.omnitools.cdk.redeem.unavailable"));
        return 0;
    }

    private static int cdkStatus(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        java.util.List<CdkService.PlayerCampaignStatus> claims = cdkService().status(player);
        if (claims.isEmpty()) {
            source.sendSuccess(() -> ServerText.translatable("command.omnitools.cdk.status.empty"), false);
            return 1;
        }
        for (CdkService.PlayerCampaignStatus claim : claims) {
            source.sendSuccess(() -> ServerText.translatable(claim.delivered()
                    ? "command.omnitools.cdk.status.delivered" : "command.omnitools.cdk.status.pending",
                    claim.campaignId()), false);
        }
        return claims.size();
    }

    private static int cdkAuditList(CommandSourceStack source) {
        for (CdkData.CampaignAudit audit : cdkService().audits(source.getServer())) {
            source.sendSuccess(() -> ServerText.translatable("command.omnitools.cdk.audit", audit.campaignId(),
                    audit.uses(), audit.uniquePlayers()), false);
        }
        return 1;
    }

    private static int cdkAudit(CommandSourceStack source, String campaignId) {
        CdkData.CampaignAudit audit = cdkService().audit(source.getServer(), campaignId);
        source.sendSuccess(() -> ServerText.translatable("command.omnitools.cdk.audit", audit.campaignId(),
                audit.uses(), audit.uniquePlayers()), false);
        return 1;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> clearCommand() {
        return Commands.literal("clear")
                .requires(COMMAND_PERMISSIONS.requirement(CommandAction.CHECKIN_CLEAR))
                .executes(context -> clearToday(context.getSource()))
                .then(Commands.literal("today")
                        .executes(context -> clearToday(context.getSource())));
    }

    private static int clearToday(CommandSourceStack source) {
        if (!COMMAND_PERMISSIONS.canUse(source, CommandAction.CHECKIN_CLEAR)) {
            return 0;
        }
        int clearedPlayers = CheckinData.get(source.getServer()).clearToday();
        source.sendSuccess(() -> ServerText.translatable(
                "command.omnitools.clear.success", clearedPlayers), true);
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> walletCommand(String literal) {
        return Commands.literal(literal)
                .requires(COMMAND_PERMISSIONS.requirementAny(CommandAction.CURRENCY_BALANCE_SELF,
                        CommandAction.CURRENCY_BALANCE_OTHER, CommandAction.CURRENCY_ADD,
                        CommandAction.CURRENCY_REMOVE))
                .executes(context -> queryOwnBalance(context.getSource()))
                .then(Commands.literal("balance")
                        .requires(COMMAND_PERMISSIONS.requirementAny(CommandAction.CURRENCY_BALANCE_SELF,
                                CommandAction.CURRENCY_BALANCE_OTHER))
                        .executes(context -> queryOwnBalance(context.getSource()))
                        .then(targetBalanceArgument()))
                .then(Commands.literal("get")
                        .requires(COMMAND_PERMISSIONS.requirementAny(CommandAction.CURRENCY_BALANCE_SELF,
                                CommandAction.CURRENCY_BALANCE_OTHER))
                        .executes(context -> queryOwnBalance(context.getSource()))
                        .then(targetBalanceArgument()))
                .then(Commands.literal("add")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.CURRENCY_ADD))
                        .then(currencyChangeArgument(true)))
                .then(Commands.literal("remove")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.CURRENCY_REMOVE))
                        .then(currencyChangeArgument(false)))
                .then(Commands.literal("deduct")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.CURRENCY_REMOVE))
                        .then(currencyChangeArgument(false)))
                .then(Commands.literal("take")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.CURRENCY_REMOVE))
                        .then(currencyChangeArgument(false)));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, ?>
    targetBalanceArgument() {
        return Commands.argument("player", GameProfileArgument.gameProfile())
                .requires(COMMAND_PERMISSIONS.requirement(CommandAction.CURRENCY_BALANCE_OTHER))
                .executes(ModMindEntry::queryTargetBalance);
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, ?>
    currencyChangeArgument(boolean add) {
        return Commands.argument("player", GameProfileArgument.gameProfile())
                .then(Commands.argument("amount", LongArgumentType.longArg(1))
                        .executes(context -> changeCurrency(context, add)));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, ?>
    titleChangeArgument(boolean give) {
        return Commands.argument("player", GameProfileArgument.gameProfile())
                .then(Commands.argument("title", StringArgumentType.word())
                        .executes(context -> changeTitle(context, give)));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, ?>
    timedTitleGrantArgument() {
        return Commands.argument("player", GameProfileArgument.gameProfile())
                .then(Commands.argument("title", StringArgumentType.word())
                        .then(Commands.argument("duration", StringArgumentType.word())
                                .executes(ModMindEntry::grantTimedTitle)));
    }

    private static int queryOwnBalance(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!COMMAND_PERMISSIONS.canUse(source, CommandAction.CURRENCY_BALANCE_SELF)) {
            return 0;
        }
        ServerPlayer player = source.getPlayerOrException();
        long balance = CheckinData.get(player).getBalance(player.getUUID());
        source.sendSuccess(() -> ServerText.translatable(
                "command.omnitools.balance.self", balance), false);
        return 1;
    }

    private static int queryTargetBalance(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!COMMAND_PERMISSIONS.canUse(context.getSource(), CommandAction.CURRENCY_BALANCE_OTHER)) {
            return 0;
        }
        Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(context, "player");
        CheckinData data = CheckinData.get(context.getSource().getServer());
        for (NameAndId profile : profiles) {
            long balance = data.getBalance(profile.id());
            context.getSource().sendSuccess(() -> ServerText.translatable(
                    "command.omnitools.balance.other", profile.name(), balance), true);
        }
        return profiles.size();
    }

    private static int changeCurrency(CommandContext<CommandSourceStack> context, boolean add)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!COMMAND_PERMISSIONS.canUse(context.getSource(),
                add ? CommandAction.CURRENCY_ADD : CommandAction.CURRENCY_REMOVE)) {
            return 0;
        }
        long amount = LongArgumentType.getLong(context, "amount");
        Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(context, "player");
        CheckinData data = CheckinData.get(context.getSource().getServer());
        for (NameAndId profile : profiles) {
            long balance;
            long changed = amount;
            if (add) {
                balance = data.addCurrency(profile.id(), amount, profile.name());
            } else {
                changed = data.removeCurrency(profile.id(), amount, profile.name());
                balance = data.getBalance(profile.id());
            }
            long finalChanged = changed;
            long finalBalance = balance;
            context.getSource().sendSuccess(() -> ServerText.translatable(
                    add ? "command.omnitools.currency.add" : "command.omnitools.currency.remove",
                    finalChanged, profile.name(), finalBalance), true);
        }
        return profiles.size();
    }

    private static int changeTitle(CommandContext<CommandSourceStack> context, boolean give)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!COMMAND_PERMISSIONS.canUse(context.getSource(),
                give ? CommandAction.TITLE_GRANT : CommandAction.TITLE_REVOKE)) {
            return 0;
        }
        String titleId = StringArgumentType.getString(context, "title");
        Optional<TitleConfig.TitleDefinition> title = titleConfig().definition(titleId);
        if (title.isEmpty()) {
            context.getSource().sendFailure(ServerText.translatable("command.omnitools.title.unknown", titleId));
            return 0;
        }

        Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(context, "player");
        Component titleDisplay = context.getSource().getEntity() instanceof ServerPlayer sourcePlayer
                ? TextTemplateRenderer.render(sourcePlayer, title.get().display()) : title.get().displayComponent();
        for (NameAndId profile : profiles) {
            if (give) {
                TitleConfig.GrantResult result = titleConfig().grant(profile.id(), profile.name(), titleId);
                context.getSource().sendSuccess(() -> ServerText.translatable(
                        result == TitleConfig.GrantResult.GRANTED
                                ? "command.omnitools.title.give"
                                : result == TitleConfig.GrantResult.RENEWED
                                ? "command.omnitools.title.renewed" : "command.omnitools.title.already_owned",
                        titleDisplay, profile.name()), true);
            } else {
                TitleConfig.RevokeResult result = titleConfig().revoke(profile.id(), profile.name(), titleId);
                context.getSource().sendSuccess(() -> ServerText.translatable(
                        result == TitleConfig.RevokeResult.REVOKED
                                ? "command.omnitools.title.remove" : "command.omnitools.title.not_owned",
                        titleDisplay, profile.name()), true);
            }

            ServerPlayer onlinePlayer = context.getSource().getServer().getPlayerList().getPlayer(profile.id());
            if (onlinePlayer != null) {
                TitleDisplayService.refreshPlayer(onlinePlayer);
                TitleEffectService.refresh(onlinePlayer);
            }
        }
        return profiles.size();
    }

    private static int grantTimedTitle(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!COMMAND_PERMISSIONS.canUse(context.getSource(), CommandAction.TITLE_GRANT)) {
            return 0;
        }
        String titleId = StringArgumentType.getString(context, "title");
        Optional<TitleConfig.TitleDefinition> title = titleConfig().definition(titleId);
        if (title.isEmpty()) {
            context.getSource().sendFailure(ServerText.translatable("command.omnitools.title.unknown", titleId));
            return 0;
        }
        String requestedDuration = StringArgumentType.getString(context, "duration");
        TimedEntitlement.Grant grant;
        try {
            grant = requestedDuration.equalsIgnoreCase("permanent")
                    ? TimedEntitlement.permanentGrant()
                    : TimedEntitlement.Grant.activeDays(Long.parseLong(requestedDuration),
                    TimedEntitlement.RenewalPolicy.EXTEND);
        } catch (IllegalArgumentException exception) {
            context.getSource().sendFailure(ServerText.translatable("command.omnitools.title.invalid_duration"));
            return 0;
        }
        Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(context, "player");
        Component titleDisplay = context.getSource().getEntity() instanceof ServerPlayer sourcePlayer
                ? TextTemplateRenderer.render(sourcePlayer, title.get().display()) : title.get().displayComponent();
        for (NameAndId profile : profiles) {
            TitleConfig.GrantResult result = titleConfig().grant(profile.id(), profile.name(), titleId, grant);
            context.getSource().sendSuccess(() -> ServerText.translatable(
                    result == TitleConfig.GrantResult.RENEWED ? "command.omnitools.title.timed_renewed"
                            : "command.omnitools.title.timed_grant", titleDisplay, profile.name(), requestedDuration), true);
            ServerPlayer onlinePlayer = context.getSource().getServer().getPlayerList().getPlayer(profile.id());
            if (onlinePlayer != null) {
                TitleDisplayService.refreshPlayer(onlinePlayer);
                TitleEffectService.refresh(onlinePlayer);
            }
        }
        return profiles.size();
    }

    private static int selectTitle(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        if (!COMMAND_PERMISSIONS.canUse(source, CommandAction.TITLE_OPEN)) {
            return 0;
        }
        ServerPlayer player = source.getPlayerOrException();
        String titleId = StringArgumentType.getString(context, "title");
        TitleConfig.SelectionResult result = titleConfig().select(player.getUUID(), player.getGameProfile().name(), titleId);
        if (result == TitleConfig.SelectionResult.NOT_OWNED) {
            source.sendFailure(ServerText.translatable("command.omnitools.title.not_owned_self", titleId));
            return 0;
        }
        TitleDisplayService.refreshPlayer(player);
        TitleEffectService.refresh(player);
        source.sendSuccess(() -> ServerText.translatable("command.omnitools.title.selected", titleId), false);
        return 1;
    }

    private static int clearTitleSelection(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!COMMAND_PERMISSIONS.canUse(source, CommandAction.TITLE_OPEN)) {
            return 0;
        }
        ServerPlayer player = source.getPlayerOrException();
        titleConfig().clearSelection(player.getUUID(), player.getGameProfile().name());
        TitleDisplayService.refreshPlayer(player);
        TitleEffectService.refresh(player);
        source.sendSuccess(() -> ServerText.translatable("command.omnitools.title.cleared"), false);
        return 1;
    }

    private static int titleTime(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!COMMAND_PERMISSIONS.canUse(source, CommandAction.TITLE_OPEN)) {
            return 0;
        }
        ServerPlayer player = source.getPlayerOrException();
        String titleId = titleConfig().selectedTitleId(player.getUUID());
        Optional<TitleConfig.TitleDefinition> selectedTitle = titleConfig().selectedTitle(player.getUUID());
        if (titleId.isEmpty() || selectedTitle.isEmpty()) {
            source.sendSuccess(() -> ServerText.translatable("command.omnitools.title.no_selection"), false);
            return 1;
        }
        Component titleDisplay = TextTemplateRenderer.render(player, selectedTitle.get().display());
        TimedEntitlement entitlement = titleConfig().entitlement(player.getUUID(), titleId).orElse(null);
        if (entitlement == null || entitlement.isPermanent()) {
            source.sendSuccess(() -> ServerText.translatable("command.omnitools.title.time_permanent", titleDisplay), false);
            return 1;
        }
        long seconds = entitlement.remainingActiveTicks() / 20L;
        source.sendSuccess(() -> ServerText.translatable("command.omnitools.title.time_remaining", titleDisplay,
                seconds / 86_400L, (seconds % 86_400L) / 3_600L, (seconds % 3_600L) / 60L,
                seconds % 60L), false);
        return 1;
    }

    private static int reloadRewards(CommandSourceStack source) {
        if (!COMMAND_PERMISSIONS.canUse(source, CommandAction.CONFIG_RELOAD)) {
            return 0;
        }
        OmniToolsConfigManager.ReloadResult result = MODULE_CONTROL.reload(source.getServer());
        if (!result.success()) {
            source.sendFailure(ServerText.translatable("command.omnitools.reload.failed"));
            return 0;
        }
        source.sendSuccess(() -> ServerText.translatable("command.omnitools.reload.success",
                configSnapshot.revision()), true);
        return 1;
    }

    private static int reloadModule(CommandSourceStack source, String moduleId) {
        if (!COMMAND_PERMISSIONS.canUse(source, CommandAction.CONFIG_RELOAD)) {
            return 0;
        }
        Optional<ModuleId> module = ModuleId.find(moduleId);
        if (module.isEmpty()) {
            source.sendFailure(ServerText.translatable("command.omnitools.reload.unknown_module", moduleId));
            return 0;
        }
        OmniToolsConfigManager.ModuleReloadResult result = MODULE_CONTROL.reloadModule(source.getServer(), module.get());
        if (!result.success()) {
            source.sendFailure(ServerText.translatable("command.omnitools.reload.module_failed", module.get().id()));
            return 0;
        }
        source.sendSuccess(() -> ServerText.translatable("command.omnitools.reload.module_success",
                module.get().id(), configSnapshot.revision()), true);
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> diagnoseCommand() {
        return Commands.literal("diagnose")
                .requires(COMMAND_PERMISSIONS.requirement(CommandAction.DIAGNOSE))
                .executes(context -> diagnose(context.getSource()))
                .then(Commands.literal("operation")
                        .then(Commands.argument("operation", StringArgumentType.greedyString())
                                .executes(context -> diagnoseOperation(context.getSource(),
                                        StringArgumentType.getString(context, "operation")))));
    }

    /** Locates evidence only; each module retains ownership of any recovery decision. */
    private static int diagnoseOperation(CommandSourceStack source, String operationId) {
        String requested = operationId == null ? "" : operationId.trim();
        List<OperationLedgerDiagnostics.Entry> entries = OperationLedgerDiagnostics.find(source.getServer(), requested);
        if (entries.isEmpty()) {
            source.sendFailure(ServerText.translatable("command.omnitools.diagnose.operation_not_found", requested));
            return 0;
        }
        for (OperationLedgerDiagnostics.Entry entry : entries) {
            source.sendSuccess(() -> ServerText.translatable("command.omnitools.diagnose.operation_entry",
                    entry.operationId(), entry.ledger(), entry.state().name(),
                    OperationLedgerDiagnostics.updatedAtText(entry.updatedAt())), false);
        }
        return entries.size();
    }

    /** Prints only immutable snapshot data and read-only runtime counters. */
    private static int diagnose(CommandSourceStack source) {
        OmniToolsConfigSnapshot snapshot = configSnapshot;
        source.sendSuccess(() -> ServerText.translatable("command.omnitools.diagnose.header"), false);
        source.sendSuccess(() -> ServerText.translatable("command.omnitools.diagnose.config",
                snapshot.root().formatVersion(), snapshot.revision()), false);
        source.sendSuccess(() -> ServerText.translatable("command.omnitools.diagnose.modules",
                formatModuleStates(snapshot)), false);
        source.sendSuccess(() -> ServerText.translatable("command.omnitools.diagnose.placeholder_api",
                ServerText.translatable(PlaceholderBootstrap.availability().translationKey())), false);

        var security = snapshot.root().commandSecurity();
        Component commandSecurity = security.isPermissive()
                ? ServerText.translatable("command.omnitools.diagnose.command_security_permissive")
                : ServerText.translatable("command.omnitools.diagnose.command_security_restricted",
                        security.allowedRoots().size());
        source.sendSuccess(() -> ServerText.translatable("command.omnitools.diagnose.command_security",
                commandSecurity), false);
        source.sendSuccess(() -> ServerText.translatable("command.omnitools.diagnose.unresolved_rewards",
                RewardClaimLedger.unresolvedEntryCount(source.getServer())), false);
        OperationLedgerDiagnostics.Summary ledgerSummary = OperationLedgerDiagnostics.inspect(source.getServer());
        source.sendSuccess(() -> ServerText.translatable("command.omnitools.diagnose.operation_ledgers",
                ledgerSummary.total(), ledgerSummary.pendingRecovery(), ledgerSummary.quarantined(),
                ledgerSummary.failed(), ledgerSummary.latestUpdateText()), false);

        SidebarService.DiagnosticStatus sidebar = sidebarService().diagnosticStatus();
        source.sendSuccess(() -> ServerText.translatable("command.omnitools.diagnose.sidebar_conflicts",
                sidebar.policy().serializedName(), sidebar.skippedByConflict()), false);
        OperationalErrorReporter.Summary errors = OperationalErrorReporter.global().summary();
        source.sendSuccess(() -> Component.literal("Recent operational errors: " + errors.recentReports()
                + " (" + errors.concise() + ")"), false);
        java.util.Map<ModuleId, ModuleHealthRegistry.Metrics> health = ModuleHealthRegistry.global().metrics();
        String healthSummary = health.isEmpty() ? "none" : health.entrySet().stream()
                .map(entry -> entry.getKey().id() + ":calls=" + entry.getValue().invocations()
                        + ",failures=" + entry.getValue().failures()
                        + ",avgUs=" + entry.getValue().averageMicros())
                .collect(java.util.stream.Collectors.joining("; "));
        source.sendSuccess(() -> Component.literal("Module callback health: " + healthSummary), false);
        java.util.Map<ModuleId, ModuleResourceBudget.Metrics> budgets = ModuleResourceBudget.global().metrics();
        String budgetSummary = budgets.isEmpty() ? "none" : budgets.entrySet().stream()
                .map(entry -> entry.getKey().id() + ":accepted=" + entry.getValue().admitted()
                        + ",rejected=" + entry.getValue().rejected()
                        + ",active=" + entry.getValue().activeWindowTasks()
                        + ",limit=" + entry.getValue().limits().maxTasksPerSecond()
                        + "/" + entry.getValue().limits().maxTasksPerPlayerPerSecond())
                .collect(java.util.stream.Collectors.joining("; "));
        source.sendSuccess(() -> Component.literal("Module resource budgets: " + budgetSummary), false);
        AsyncAuditLogWriter.Metrics audit = AsyncAuditLogWriter.global().metrics();
        source.sendSuccess(() -> Component.literal("Audit queue: accepted=" + audit.accepted()
                + ", rejected=" + audit.rejected() + ", completed=" + audit.completed()
                + ", failed=" + audit.failed() + ", depth=" + audit.queueDepth()
                + "/" + audit.queueCapacity()), false);
        return 1;
    }

    private static String formatModuleStates(OmniToolsConfigSnapshot snapshot) {
        java.util.List<String> states = new java.util.ArrayList<>();
        for (ModuleId module : ModuleId.values()) {
            String state = snapshot.degraded(module) || isModuleRuntimeDegraded(module) ? "degraded"
                    : ServerText.translatable(snapshot.enabled(module)
                    ? "command.omnitools.diagnose.enabled" : "command.omnitools.diagnose.disabled").getString();
            states.add(module.id() + "=" + state);
        }
        return String.join(", ", states);
    }

    private static void warnPermissiveCommandSecurity(OmniToolsConfigSnapshot snapshot) {
        if (!snapshot.root().commandSecurity().isPermissive()) {
            return;
        }
        java.util.List<String> affectedMenus = new java.util.ArrayList<>();
        int commandActions = 0;
        for (CommandMenuDefinition menu : snapshot.commandMenus().menus().values()) {
            int actions = 0;
            for (CommandMenuItem item : menu.page().items().values()) {
                actions += countCommandActions(item.leftClick());
                actions += countCommandActions(item.rightClick());
            }
            if (actions > 0) {
                affectedMenus.add(menu.id());
                commandActions += actions;
            }
        }
        int commandRewards = countCommandRewards(snapshot.rewards().dailyRewards());
        for (java.util.List<RewardDefinition> rewards : snapshot.rewards().monthlyRewards().values()) {
            commandRewards += countCommandRewards(rewards);
        }
        for (CheckinRewardConfig.OnlineTimeReward reward : snapshot.rewards().onlineTimeRewards()) {
            commandRewards += countCommandRewards(reward.rewards());
        }
        for (AchievementConfig.AchievementDefinition achievement : snapshot.achievements().achievements()) {
            commandRewards += countCommandRewards(achievement.rewards());
        }
        for (CdkConfig.Campaign campaign : snapshot.cdk().campaigns()) {
            commandRewards += countCommandRewards(campaign.rewards());
        }
        String menus = affectedMenus.isEmpty()
                ? ServerText.translatable("command.omnitools.diagnose.none").getString()
                : String.join(", ", affectedMenus);
        System.err.println("[omnitools] " + ServerText.translatable(
                "log.omnitools.command_security.permissive", menus, commandActions, commandRewards).getString());
    }

    private static int countCommandActions(java.util.List<CommandMenuAction> actions) {
        return (int) actions.stream().filter(action -> action.type() == CommandMenuAction.Type.COMMAND).count();
    }

    private static int countCommandRewards(java.util.Collection<RewardDefinition> rewards) {
        return (int) rewards.stream().filter(reward -> reward.type() == dev.modmind.omnitools.reward.RewardType.COMMAND)
                .count();
    }

    private static LiteralArgumentBuilder<CommandSourceStack> rewardsCommand() {
        return Commands.literal("rewards")
                .requires(COMMAND_PERMISSIONS.requirementAny(CommandAction.REWARDS_RETRY, CommandAction.REWARDS_ADMIN))
                .then(Commands.literal("open")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.REWARDS_RETRY))
                        .executes(context -> openRewardInbox(context.getSource().getPlayerOrException())))
                .then(Commands.literal("admin")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.REWARDS_ADMIN))
                        .executes(context -> openRewardLedger(context.getSource().getPlayerOrException())))
                .then(Commands.literal("retry")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.REWARDS_RETRY))
                        .executes(context -> retryRewards(context.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .requires(COMMAND_PERMISSIONS.requirement(CommandAction.REWARDS_ADMIN))
                                .then(Commands.argument("event", StringArgumentType.word())
                                        .executes(ModMindEntry::retryRewardEvent))))
                .then(Commands.literal("inspect")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.REWARDS_ADMIN))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(context -> inspectRewardLedger(context, null))
                                .then(Commands.argument("event", StringArgumentType.word())
                                        .executes(context -> inspectRewardLedger(context,
                                                StringArgumentType.getString(context, "event"))))))
                .then(Commands.literal("resolve")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.REWARDS_ADMIN))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("event", StringArgumentType.word())
                                        .then(Commands.literal("grant")
                                                .executes(context -> resolveRewardEvent(context,
                                                        RewardClaimLedger.EntryStatus.GRANTED)))
                                        .then(Commands.literal("fail")
                                                .executes(context -> resolveRewardEvent(context,
                                                        RewardClaimLedger.EntryStatus.FAILED))))));
    }

    private static int retryRewardEvent(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String eventId = StringArgumentType.getString(context, "event");
        int retried = 0;
        for (NameAndId profile : GameProfileArgument.getGameProfiles(context, "player")) {
            ServerPlayer player = context.getSource().getServer().getPlayerList().getPlayer(profile.id());
            if (player == null) {
                context.getSource().sendFailure(Component.literal("Player must be online to retry rewards: "
                        + profile.name()));
                continue;
            }
            boolean accepted = isModuleEnabled(ModuleId.DAILY_CHECKIN) && rewardService().retryEvent(player, eventId);
            accepted |= isModuleEnabled(ModuleId.ACHIEVEMENTS) && achievementService().retryEvent(player, eventId);
            accepted |= isModuleEnabled(ModuleId.CDK) && cdkService().retryEvent(player, eventId);
            if (accepted) {
                retried++;
                context.getSource().sendSuccess(() -> Component.literal("Retried reward event " + eventId
                        + " for " + player.getGameProfile().name()), true);
            } else {
                context.getSource().sendFailure(Component.literal("Unknown or ineligible reward event for "
                        + player.getGameProfile().name() + ": " + eventId));
            }
        }
        return retried;
    }

    private static int inspectRewardLedger(CommandContext<CommandSourceStack> context, String requestedEvent)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        int inspected = 0;
        for (NameAndId profile : GameProfileArgument.getGameProfiles(context, "player")) {
            RewardClaimLedger ledger = RewardClaimLedger.get(context.getSource().getServer());
            java.util.List<String> eventIds = requestedEvent == null
                    ? rewardEventIdsFor(ledger, profile.id()) : java.util.List.of(requestedEvent);
            for (String eventId : eventIds) {
                if (!belongsToPlayer(eventId, profile.id())) {
                    context.getSource().sendFailure(Component.literal("Reward event does not belong to "
                            + profile.name() + ": " + eventId));
                    continue;
                }
                RewardEvent event = new RewardEvent(eventId, profile.id());
                java.util.Map<String, RewardClaimLedger.Entry> entries = ledger.entries(event);
                if (entries.isEmpty()) {
                    context.getSource().sendFailure(Component.literal("No reward ledger entry for " + eventId));
                    continue;
                }
                inspected++;
                context.getSource().sendSuccess(() -> Component.literal(formatLedgerEntries(profile.name(), eventId, entries)),
                        false);
            }
        }
        return inspected;
    }

    private static int resolveRewardEvent(CommandContext<CommandSourceStack> context,
                                          RewardClaimLedger.EntryStatus status)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String eventId = StringArgumentType.getString(context, "event");
        int resolved = 0;
        for (NameAndId profile : GameProfileArgument.getGameProfiles(context, "player")) {
            if (!belongsToPlayer(eventId, profile.id())) {
                context.getSource().sendFailure(Component.literal("Reward event does not belong to "
                        + profile.name() + ": " + eventId));
                continue;
            }
            RewardEvent event = new RewardEvent(eventId, profile.id());
            int count = RewardClaimLedger.get(context.getSource().getServer()).resolveEvent(event, status,
                    context.getSource().getTextName());
            if (count == 0) {
                context.getSource().sendFailure(Component.literal("No reward ledger entry for " + eventId));
                continue;
            }
            if (status == RewardClaimLedger.EntryStatus.GRANTED) {
                ServerPlayer online = context.getSource().getServer().getPlayerList().getPlayer(profile.id());
                finalizeResolvedGrant(context.getSource().getServer(), profile.id(), profile.name(), eventId, online);
            }
            resolved++;
            context.getSource().sendSuccess(() -> Component.literal("Resolved " + count + " reward entries as "
                    + status + " for " + profile.name() + ": " + eventId), true);
        }
        return resolved;
    }

    private static java.util.List<String> rewardEventIdsFor(RewardClaimLedger ledger, java.util.UUID playerId) {
        java.util.List<String> events = new java.util.ArrayList<>();
        events.addAll(ledger.eventIdsStartingWith("checkin:" + playerId + ":"));
        events.addAll(ledger.eventIdsStartingWith("achievement:" + playerId + ":"));
        events.addAll(ledger.eventIdsStartingWith("online:" + playerId + ":"));
        ledger.eventIds().stream().filter(event -> isCdkEventForPlayer(event, playerId)).forEach(events::add);
        return events;
    }

    private static boolean belongsToPlayer(String eventId, java.util.UUID playerId) {
        return eventId != null && (eventId.startsWith("checkin:" + playerId + ":")
                || eventId.startsWith("achievement:" + playerId + ":")
                || eventId.startsWith("online:" + playerId + ":")
                || isCdkEventForPlayer(eventId, playerId));
    }

    private static boolean isCdkEventForPlayer(String eventId, java.util.UUID playerId) {
        String[] parts = eventId == null ? new String[0] : eventId.split(":", -1);
        if (parts.length != 3 || !parts[0].equals("cdk")) {
            return false;
        }
        try {
            return parts[1].matches(RewardDefinition.ID_PATTERN.pattern())
                    && java.util.UUID.fromString(parts[2]).equals(playerId);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String formatLedgerEntries(String playerName, String eventId,
                                              java.util.Map<String, RewardClaimLedger.Entry> entries) {
        StringBuilder message = new StringBuilder("Reward ledger ").append(eventId).append(" for ")
                .append(playerName).append(':');
        entries.forEach((rewardId, entry) -> message.append(" ").append(rewardId).append('=')
                .append(entry.status()).append(entry.reason().isBlank() ? "" : "(" + entry.reason() + ")")
                .append(entry.dispatchedCommand().isBlank() ? "" : " command=" + entry.dispatchedCommand()));
        return message.toString();
    }

    private static void finalizeResolvedGrant(net.minecraft.server.MinecraftServer server, java.util.UUID playerId,
                                              String playerName, String eventId, ServerPlayer onlinePlayer) {
        String[] checkin = eventId.split(":", -1);
        if (checkin.length == 4 && checkin[0].equals("online")) {
            try {
                CheckinData.get(server).markOnlineTimeRewardClaimed(playerId, Long.parseLong(checkin[2]), checkin[3],
                        playerName);
            } catch (RuntimeException ignored) {
                // The ledger has still been resolved; leave malformed source keys inspectable.
            }
            return;
        }
        if (checkin.length == 5 && checkin[0].equals("checkin") && checkin[2].equals("monthly")) {
            try {
                CheckinData.get(server).markMonthlyRewardClaimed(playerId, java.time.YearMonth.parse(checkin[3]),
                        Integer.parseInt(checkin[4]), playerName);
            } catch (RuntimeException ignored) {
                // The event was still resolved; a malformed legacy key must not fail the command.
            }
            return;
        }
        String prefix = "achievement:" + playerId + ":";
        if (eventId.startsWith(prefix)) {
            AchievementData.get(server).markClaimed(playerId, eventId.substring(prefix.length()));
            if (onlinePlayer != null) {
                achievementService().check(onlinePlayer);
            }
        }
    }

    /** Completes an event only after a player inbox delivery has updated its original ledger entry. */
    static void finalizeRewardInboxDelivery(ServerPlayer player, String eventId) {
        boolean known = false;
        if (isModuleEnabled(ModuleId.DAILY_CHECKIN)) {
            known = rewardService().retryEvent(player, eventId);
        }
        if (isModuleEnabled(ModuleId.ACHIEVEMENTS)) {
            known |= achievementService().retryEvent(player, eventId);
        }
        if (isModuleEnabled(ModuleId.ONLINE_REWARD)) {
            known |= onlineTimeRewardService().retryEvent(player, eventId);
        }
        if (isModuleEnabled(ModuleId.CDK)) {
            known |= cdkService().retryEvent(player, eventId);
        }
        if (!known) {
            System.err.println("[omnitools] Delivered reward inbox item for an unknown source event: " + eventId);
        }
    }

    /**
     * Marks the source claim only when the current source definition proves every reward entry is
     * granted. Unlike retrying an event, this never invokes RewardGrantService or a command.
     */
    static void finalizeResolvedLedgerEntry(net.minecraft.server.MinecraftServer server,
                                            RewardClaimLedger.LedgerEntry entry) {
        if (server == null || entry.playerId() == null) {
            return;
        }
        RewardEvent event = new RewardEvent(entry.eventId(), entry.playerId());
        RewardClaimLedger ledger = RewardClaimLedger.get(server);
        String[] checkin = entry.eventId().split(":", -1);
        if (checkin.length == 4 && checkin[0].equals("online")) {
            try {
                long day = Long.parseLong(checkin[2]);
                rewardService().onlineTimeRewards().stream()
                        .filter(reward -> reward.id().equals(checkin[3]))
                        .findFirst()
                        .filter(reward -> ledger.allGranted(event, reward.rewards()))
                        .ifPresent(reward -> {
                            String playerName = entry.playerName().isBlank()
                                    ? entry.playerId().toString() : entry.playerName();
                            CheckinData.get(server).markOnlineTimeRewardClaimed(entry.playerId(), day, reward.id(),
                                    playerName);
                        });
            } catch (RuntimeException ignored) {
                // Preserve an inspectable ledger record when a legacy event id is malformed.
            }
            return;
        }
        if (checkin.length == 5 && checkin[0].equals("checkin") && checkin[2].equals("monthly")) {
            try {
                java.time.YearMonth month = java.time.YearMonth.parse(checkin[3]);
                int milestone = Integer.parseInt(checkin[4]);
                java.util.List<RewardDefinition> rewards = rewardService().monthlyRewards().get(milestone);
                if (rewards != null && ledger.allGranted(event, rewards)) {
                    String playerName = entry.playerName().isBlank() ? entry.playerId().toString() : entry.playerName();
                    CheckinData.get(server).markMonthlyRewardClaimed(entry.playerId(), month, milestone, playerName);
                }
            } catch (RuntimeException ignored) {
                // Preserve an inspectable ledger record when a legacy event id is malformed.
            }
            return;
        }
        String prefix = "achievement:" + entry.playerId() + ":";
        if (entry.eventId().startsWith(prefix)) {
            String achievementId = entry.eventId().substring(prefix.length());
            achievementService().config().definition(achievementId).ifPresent(achievement -> {
                if (ledger.allGranted(event, achievement.rewards())) {
                    AchievementData.get(server).markClaimed(entry.playerId(), achievement.id());
                    ServerPlayer online = server.getPlayerList().getPlayer(entry.playerId());
                    if (online != null) {
                        achievementService().check(online);
                    }
                }
            });
        }
    }

    private static int retryRewards(ServerPlayer player) {
        if (isModuleEnabled(ModuleId.DAILY_CHECKIN)) {
            rewardService().retryPending(player);
        }
        if (isModuleEnabled(ModuleId.ACHIEVEMENTS)) {
            achievementService().retryPending(player);
        }
        if (isModuleEnabled(ModuleId.ONLINE_REWARD)) {
            onlineTimeRewardService().retryPending(player);
        }
        if (isModuleEnabled(ModuleId.CDK)) {
            cdkService().retryPending(player);
        }
        if (player.containerMenu instanceof CheckinScreenHandler checkinMenu) {
            checkinMenu.refreshAfterRewardRetry();
        } else if (player.containerMenu instanceof CheckinRewardInfoScreenHandler rewardMenu) {
            rewardMenu.refreshAfterRewardRetry();
        } else if (player.containerMenu instanceof RewardInboxScreenHandler inboxMenu) {
            inboxMenu.refreshAfterRewardRetry();
        } else if (player.containerMenu instanceof OnlineTimeRewardScreenHandler onlineMenu) {
            onlineMenu.refreshAfterRewardRetry();
        }
        player.displayClientMessage(ServerText.translatable("message.omnitools.reward.retry_complete"), true);
        return 1;
    }

    private static void closeDisabledMenus(net.minecraft.server.MinecraftServer server,
                                            OmniToolsConfigSnapshot snapshot) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            boolean close = (!snapshot.enabled(ModuleId.DAILY_CHECKIN)
                    && (player.containerMenu instanceof CheckinScreenHandler
                    || player.containerMenu instanceof CheckinRecordsScreenHandler
                    || player.containerMenu instanceof CheckinRewardInfoScreenHandler))
                    || (!COMMAND_PERMISSIONS.canUse(player, CommandAction.CHECKIN_OPEN)
                    && (player.containerMenu instanceof CheckinScreenHandler
                    || player.containerMenu instanceof CheckinRecordsScreenHandler
                    || player.containerMenu instanceof CheckinRewardInfoScreenHandler))
                    || (!snapshot.enabled(ModuleId.ONLINE_REWARD)
                    && player.containerMenu instanceof OnlineTimeRewardScreenHandler)
                    || (!COMMAND_PERMISSIONS.canUse(player, CommandAction.ONLINE_OPEN)
                    && player.containerMenu instanceof OnlineTimeRewardScreenHandler)
                    || (!snapshot.enabled(ModuleId.SHOP) && player.containerMenu instanceof ShopScreenHandler)
                    || (!COMMAND_PERMISSIONS.canUse(player, CommandAction.SHOP_OPEN)
                    && player.containerMenu instanceof ShopScreenHandler)
                    || (!snapshot.enabled(ModuleId.TITLES) && player.containerMenu instanceof TitleScreenHandler)
                    || (!COMMAND_PERMISSIONS.canUse(player, CommandAction.TITLE_OPEN)
                    && player.containerMenu instanceof TitleScreenHandler)
                    || (!snapshot.enabled(ModuleId.ACHIEVEMENTS)
                    && player.containerMenu instanceof AchievementScreenHandler)
                    || (!COMMAND_PERMISSIONS.canUse(player, CommandAction.ACHIEVEMENTS_OPEN)
                    && player.containerMenu instanceof AchievementScreenHandler)
                    || (!snapshot.enabled(ModuleId.LEADERBOARDS)
                    && player.containerMenu instanceof LeaderboardScreenHandler)
                    || (!COMMAND_PERMISSIONS.canUse(player, CommandAction.LEADERBOARDS_OPEN)
                        && player.containerMenu instanceof LeaderboardScreenHandler)
                    || (!snapshot.enabled(ModuleId.PACKAGES)
                        && (player.containerMenu instanceof PackageScreenHandler
                        || player.containerMenu instanceof PackagePreviewScreenHandler
                        || player.containerMenu instanceof PackageConfirmScreenHandler))
                    || (!COMMAND_PERMISSIONS.canUse(player, CommandAction.PACKAGE_OPEN)
                        && (player.containerMenu instanceof PackageScreenHandler
                        || player.containerMenu instanceof PackagePreviewScreenHandler
                        || player.containerMenu instanceof PackageConfirmScreenHandler))
                    || (!snapshot.enabled(ModuleId.SKILLS)
                    && player.containerMenu instanceof SkillTreeScreenHandler)
                    || (!COMMAND_PERMISSIONS.canUse(player, CommandAction.SKILLS_OPEN)
                    && player.containerMenu instanceof SkillTreeScreenHandler)
                    || (!snapshot.enabled(ModuleId.DIVINATION)
                    && player.containerMenu instanceof DivinationScreenHandler)
                    || (!COMMAND_PERMISSIONS.canUse(player, CommandAction.DIVINATION_OPEN)
                    && player.containerMenu instanceof DivinationScreenHandler)
                    || (!snapshot.enabled(ModuleId.CLOUD_STORAGE)
                    && player.containerMenu instanceof CloudStorageScreenHandler)
                    || (!COMMAND_PERMISSIONS.canUse(player, CommandAction.STORAGE_OPEN)
                    && player.containerMenu instanceof CloudStorageScreenHandler)
                    || (!COMMAND_PERMISSIONS.canUse(player, CommandAction.CONFIG_RELOAD)
                    && player.containerMenu instanceof ModuleManagerScreenHandler)
                    || (!snapshot.enabled(ModuleId.COMMAND_MENU)
                    && player.containerMenu instanceof CommandMenuScreenHandler)
                    || (!COMMAND_PERMISSIONS.canUse(player, CommandAction.COMMAND_MENU_OPEN)
                    && player.containerMenu instanceof CommandMenuScreenHandler)
                    || (!COMMAND_PERMISSIONS.canUse(player, CommandAction.REWARDS_RETRY)
                    && player.containerMenu instanceof RewardInboxScreenHandler)
                    || (!COMMAND_PERMISSIONS.canUse(player, CommandAction.REWARDS_ADMIN)
                    && player.containerMenu instanceof RewardLedgerScreenHandler);
            if (close) {
                player.closeContainer();
            } else if (player.containerMenu instanceof CommandMenuScreenHandler commandMenu) {
                commandMenu.refreshFromConfig();
            }
        }
    }

    private static void refreshCommandMenus(net.minecraft.server.MinecraftServer server,
                                            OmniToolsConfigSnapshot snapshot) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof CommandMenuScreenHandler menu) {
                if (!snapshot.enabled(ModuleId.COMMAND_MENU)
                        || snapshot.commandMenus().menu(menu.menuId()) == null) {
                    player.closeContainer();
                } else {
                    menu.refreshFromConfig();
                }
            }
        }
    }

    private static boolean broadcastTitledChatMessage(PlayerChatMessage message, ServerPlayer sender, ChatType.Bound parameters) {
        if (!isModuleEnabled(ModuleId.TITLES)) {
            return true;
        }
        Optional<TitleConfig.TitleDefinition> title = titleConfig().selectedTitle(sender.getUUID());
        if (title.isEmpty()) {
            return true;
        }

        Component chatMessage = TitleDisplayService.chatName(sender, title.get())
                .copy()
                .append(Component.literal(": "))
                .append(message.decoratedContent());
        sender.level().getServer().getPlayerList().broadcastSystemMessage(chatMessage, false);
        return false;
    }

    private static void sendCheckinReminder(ServerPlayer player) {
        Component action = ServerText.translatable("message.omnitools.join_reminder.action")
                .withStyle(style -> style
                        .withColor(ChatFormatting.GOLD)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand("/omnitools")));
        player.sendSystemMessage(ServerText.translatable("message.omnitools.join_reminder.prefix").append(action));
    }

    static int openCheckinMenu(ServerPlayer player) {
        if (!COMMAND_PERMISSIONS.canUse(player, CommandAction.CHECKIN_OPEN)
                || !isModuleEnabled(ModuleId.DAILY_CHECKIN)) {
            player.displayClientMessage(ServerText.translatable("message.omnitools.module_disabled"), true);
            return 0;
        }
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> CheckinScreenHandler.createServer(syncId, inventory, player),
                ServerText.translatable("gui.omnitools.title")));
        return 1;
    }

    static int openRewardInbox(ServerPlayer player) {
        if (!COMMAND_PERMISSIONS.canUse(player, CommandAction.REWARDS_RETRY)) {
            player.displayClientMessage(ServerText.translatable("message.omnitools.module_disabled"), true);
            return 0;
        }
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> RewardInboxScreenHandler.createServer(syncId, inventory, player, 0),
                ServerText.translatable("gui.omnitools.rewards.inbox.title")));
        return 1;
    }

    private static int openRewardLedger(ServerPlayer player) {
        if (!COMMAND_PERMISSIONS.canUse(player, CommandAction.REWARDS_ADMIN)) {
            return 0;
        }
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> RewardLedgerScreenHandler.createServer(syncId, inventory, player),
                ServerText.translatable("gui.omnitools.rewards.admin.title")));
        return 1;
    }

    static int openOnlineTimeRewardMenu(ServerPlayer player) {
        if (!COMMAND_PERMISSIONS.canUse(player, CommandAction.ONLINE_OPEN)
                || !isModuleEnabled(ModuleId.ONLINE_REWARD)) {
            player.displayClientMessage(ServerText.translatable("message.omnitools.module_disabled"), true);
            return 0;
        }
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> OnlineTimeRewardScreenHandler.createServer(syncId, inventory, player),
                ServerText.translatable("gui.omnitools.online_reward.menu_title")));
        return 1;
    }

    static int openShopMenu(ServerPlayer player) {
        if (!COMMAND_PERMISSIONS.canUse(player, CommandAction.SHOP_OPEN)
                || !isModuleEnabled(ModuleId.SHOP)) {
            player.displayClientMessage(ServerText.translatable("message.omnitools.module_disabled"), true);
            return 0;
        }
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> ShopScreenHandler.createServer(syncId, inventory, player,
                        shopConfig(), 0),
                ServerText.translatable("gui.omnitools.shop.title")));
        return 1;
    }

    static int openTitleMenu(ServerPlayer player) {
        if (!COMMAND_PERMISSIONS.canUse(player, CommandAction.TITLE_OPEN)
                || !isModuleEnabled(ModuleId.TITLES)) {
            player.displayClientMessage(ServerText.translatable("message.omnitools.module_disabled"), true);
            return 0;
        }
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> TitleScreenHandler.createServer(syncId, inventory, player, titleConfig()),
                ServerText.translatable("gui.omnitools.title.menu_title")));
        return 1;
    }

    static int openCloudStorageMenu(ServerPlayer player) {
        if (!COMMAND_PERMISSIONS.canUse(player, CommandAction.STORAGE_OPEN)
                || !isModuleEnabled(ModuleId.CLOUD_STORAGE)) {
            player.displayClientMessage(ServerText.translatable("message.omnitools.module_disabled"), true);
            return 0;
        }
        if (CloudStorageData.get(player).isQuarantined(player.getUUID())) {
            player.displayClientMessage(ServerText.translatable("message.omnitools.storage.quarantined"), true);
            return 0;
        }
        if (CloudStorageJournalData.get(player.level().getServer()).hasUnresolvedOperation(player.getUUID())) {
            player.displayClientMessage(ServerText.translatable("message.omnitools.storage.quarantined"), true);
            return 0;
        }
        if (CloudStorageSessionManager.global().hasActiveSession(player)) {
            player.displayClientMessage(ServerText.translatable("message.omnitools.storage.save_failed"), true);
            return 0;
        }
        try {
            player.openMenu(new SimpleMenuProvider(
                    (syncId, inventory, ignored) -> CloudStorageScreenHandler.createServer(syncId, inventory, player,
                            cloudStorageConfig(), 0),
                    ServerText.translatable("gui.omnitools.storage.title")));
            return 1;
        } catch (IllegalStateException exception) {
            player.displayClientMessage(ServerText.translatable("message.omnitools.storage.save_failed"), true);
            return 0;
        }
    }

    static int openAchievementMenu(ServerPlayer player) {
        if (!COMMAND_PERMISSIONS.canUse(player, CommandAction.ACHIEVEMENTS_OPEN)
                || !isModuleEnabled(ModuleId.ACHIEVEMENTS)) {
            player.displayClientMessage(ServerText.translatable("message.omnitools.module_disabled"), true);
            return 0;
        }
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> AchievementScreenHandler.createServer(syncId, inventory, player,
                        achievementService(), 0),
                ServerText.translatable("gui.omnitools.achievement.title")));
        return 1;
    }

    static int openLeaderboardMenu(ServerPlayer player, String boardId) {
        if (!COMMAND_PERMISSIONS.canUse(player, CommandAction.LEADERBOARDS_OPEN)
                || !isModuleEnabled(ModuleId.LEADERBOARDS)) {
            player.displayClientMessage(ServerText.translatable("message.omnitools.module_disabled"), true);
            return 0;
        }
        String requested = boardId == null ? "" : boardId.trim().toLowerCase(java.util.Locale.ROOT);
        if (!requested.isBlank() && !leaderboardService().hasBoard(requested)) {
            player.displayClientMessage(ServerText.translatable("command.omnitools.leaderboard.unknown", requested), true);
            return 0;
        }
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> LeaderboardScreenHandler.createServer(syncId, inventory, player,
                        leaderboardService(), requested, 0), ServerText.translatable("gui.omnitools.leaderboard.title")));
        return 1;
    }

    private static int openCommandMenu(CommandSourceStack source, String menuId) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(ServerText.translatable("message.omnitools.command_menu.player_only"));
            return 0;
        }
        return CommandMenuService.open(player, menuId) ? 1 : 0;
    }

    private static int closeCommandMenu(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(ServerText.translatable("message.omnitools.command_menu.player_only"));
            return 0;
        }
        if (player.containerMenu instanceof CommandMenuScreenHandler) {
            player.closeContainer();
        }
        return 1;
    }

    private static int openModuleManagerMenu(CommandSourceStack source) {
        if (!COMMAND_PERMISSIONS.canUse(source, CommandAction.CONFIG_RELOAD)) {
            return 0;
        }
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(ServerText.translatable("message.omnitools.modules.player_only"));
            return 0;
        }
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> ModuleManagerScreenHandler.createServer(syncId, inventory, player),
                ServerText.translatable("gui.omnitools.modules.title")));
        return 1;
    }
}
