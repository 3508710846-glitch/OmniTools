package dev.modmind.omnitools;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
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
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.ChatType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.SimpleMenuProvider;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Optional;
import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.config.OmniToolsConfigManager;
import dev.modmind.omnitools.config.OmniToolsConfigSnapshot;
import dev.modmind.omnitools.permissions.CommandAction;
import dev.modmind.omnitools.permissions.CommandPermissionConfig;
import dev.modmind.omnitools.permissions.CommandPermissionService;
import dev.modmind.omnitools.commandmenu.CommandMenuConfig;
import dev.modmind.omnitools.commandmenu.CommandMenuScreenHandler;
import dev.modmind.omnitools.commandmenu.CommandMenuService;
import dev.modmind.omnitools.sidebar.SidebarService;
import dev.modmind.omnitools.reward.RewardClaimLedger;
import dev.modmind.omnitools.reward.RewardEvent;
import dev.modmind.omnitools.reward.RewardGrantService;

public final class ModMindEntry implements ModInitializer {
    public static final String MOD_ID = "omnitools";
    private static CheckinRewardService rewardService;
    private static final RewardGrantService REWARD_GRANT_SERVICE = new RewardGrantService();
    private static OnlineTimeRewardService onlineTimeRewardService;
    private static ShopConfig shopConfig = ShopConfig.empty();
    private static TitleConfig titleConfig = TitleConfig.empty();
    private static TitleEffectConfig titleEffectConfig = TitleEffectConfig.empty();
    private static CloudStorageConfig cloudStorageConfig = CloudStorageConfig.defaultConfig();
    private static AchievementService achievementService = AchievementService.empty();
    private static final SidebarService SIDEBAR_SERVICE = new SidebarService();
    private static final OmniToolsConfigManager CONFIG_MANAGER = new OmniToolsConfigManager();
    private static final ModuleControlService MODULE_CONTROL = new ModuleControlService(CONFIG_MANAGER);
    private static volatile OmniToolsConfigSnapshot configSnapshot = CONFIG_MANAGER.snapshot();
    private static final CommandPermissionService COMMAND_PERMISSIONS = new CommandPermissionService(
            CommandPermissionConfig.defaults());

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            rewardService = CheckinRewardService.from(CheckinRewardConfig.empty());
            onlineTimeRewardService = new OnlineTimeRewardService();
            achievementService = AchievementService.empty();
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LegacySavedDataMigration.migrate(server);
            TitleData.bind(server);
            TitleData.importLegacy(server);
            MODULE_CONTROL.reload(server);
            rewardGrantService().reconcileStartup(server);
            PlaceholderBootstrap.registerIfAvailable();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            onlineTimeRewardService().flushAll(server);
            TitleEffectService.removeAll(server);
            TitleDisplayService.clearAll(server);
            TitleData.unbind(server);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (isModuleEnabled(ModuleId.ONLINE_REWARD)) {
                onlineTimeRewardService().tick(server);
            }
            if (isModuleEnabled(ModuleId.TITLE_EFFECTS)) {
                TitleEffectService.tick(server);
            }
            if (isModuleEnabled(ModuleId.ACHIEVEMENTS)) {
                achievementService().tick(server);
            }
            if (isModuleEnabled(ModuleId.SIDEBAR)) {
                sidebarService().tick(server);
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (isModuleEnabled(ModuleId.ONLINE_REWARD)) {
                onlineTimeRewardService().onJoin(player);
            }
            if (isModuleEnabled(ModuleId.TITLES)) {
                titleConfig().rememberPlayer(player.getUUID(), player.getGameProfile().name());
                TitleDisplayService.refreshPlayer(player);
            }
            if (isModuleEnabled(ModuleId.TITLE_EFFECTS)) {
                TitleEffectService.refresh(player);
            }
            if (isModuleEnabled(ModuleId.ACHIEVEMENTS)) {
                achievementService().check(player);
                achievementService().retryPending(player);
            }
            if (isModuleEnabled(ModuleId.DAILY_CHECKIN)) {
                rewardService().retryPending(player);
            }
            if (isModuleEnabled(ModuleId.SIDEBAR)) {
                sidebarService().onJoin(player);
            }
            LocalDate date = CheckinData.today(server);
            if (isModuleEnabled(ModuleId.DAILY_CHECKIN)
                    && !CheckinData.get(server).hasSigned(player.getUUID(), date.toEpochDay())) {
                sendCheckinReminder(player);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            TitleDisplayService.onDisconnect(handler.getPlayer());
            achievementService().forgetMenuSnapshot(handler.getPlayer());
            if (isModuleEnabled(ModuleId.TITLE_EFFECTS)) {
                TitleEffectService.remove(handler.getPlayer());
            }
            if (isModuleEnabled(ModuleId.ONLINE_REWARD)) {
                onlineTimeRewardService().onDisconnect(handler.getPlayer());
            }
            sidebarService().onDisconnect(handler.getPlayer());
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (isModuleEnabled(ModuleId.TITLE_EFFECTS)) {
                TitleEffectService.forget(oldPlayer);
                TitleEffectService.refresh(newPlayer);
            }
            if (isModuleEnabled(ModuleId.TITLES)) {
                TitleDisplayService.refreshPlayer(newPlayer);
            }
            if (isModuleEnabled(ModuleId.SIDEBAR)) {
                sidebarService().onJoin(newPlayer);
            }
        });
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(ModMindEntry::broadcastTitledChatMessage);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var command = Commands.literal("omnitools")
                    .requires(COMMAND_PERMISSIONS.requirementAny(CommandAction.CHECKIN_OPEN,
                            CommandAction.ONLINE_OPEN, CommandAction.SHOP_OPEN, CommandAction.TITLE_OPEN,
                            CommandAction.TITLE_GRANT, CommandAction.TITLE_REVOKE, CommandAction.STORAGE_OPEN,
                            CommandAction.ACHIEVEMENTS_OPEN, CommandAction.CURRENCY_BALANCE_SELF,
                            CommandAction.CURRENCY_BALANCE_OTHER, CommandAction.CURRENCY_ADD,
                            CommandAction.CURRENCY_REMOVE, CommandAction.CHECKIN_CLEAR, CommandAction.CONFIG_RELOAD,
                            CommandAction.COMMAND_MENU_OPEN, CommandAction.COMMAND_MENU_CLOSE,
                            CommandAction.SIDEBAR_TOGGLE, CommandAction.SIDEBAR_STATUS, CommandAction.REWARDS_RETRY,
                            CommandAction.REWARDS_ADMIN))
                    .executes(context -> openCheckinMenu(context.getSource().getPlayerOrException()))
                    .then(Commands.literal("open")
                            .requires(COMMAND_PERMISSIONS.requirement(CommandAction.CHECKIN_OPEN))
                            .executes(context -> openCheckinMenu(context.getSource().getPlayerOrException())))
                    .then(onlineTimeCommand())
                    .then(shopCommand())
                    .then(titleCommand())
                    .then(cloudStorageCommand("storage"))
                    .then(achievementCommand())
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
                            .executes(context -> reloadRewards(context.getSource())))
                    .then(rewardsCommand())
                    .then(commandMenuCommand())
                    .then(moduleManagerCommand());
            dispatcher.register(command);
            dispatcher.register(Commands.literal("checkin")
                    .requires(COMMAND_PERMISSIONS.requirementAny(CommandAction.CHECKIN_OPEN,
                            CommandAction.ONLINE_OPEN, CommandAction.SHOP_OPEN, CommandAction.TITLE_OPEN,
                            CommandAction.TITLE_GRANT, CommandAction.TITLE_REVOKE, CommandAction.STORAGE_OPEN,
                            CommandAction.ACHIEVEMENTS_OPEN, CommandAction.CURRENCY_BALANCE_SELF,
                            CommandAction.CURRENCY_BALANCE_OTHER, CommandAction.CURRENCY_ADD,
                            CommandAction.CURRENCY_REMOVE, CommandAction.CHECKIN_CLEAR))
                    .executes(context -> openCheckinMenu(context.getSource().getPlayerOrException()))
                    .then(onlineTimeCommand())
                    .then(shopCommand())
                    .then(titleCommand())
                    .then(cloudStorageCommand("storage"))
                    .then(achievementCommand())
                    .then(clearCommand())
                    .then(walletCommand("currency"))
                    .then(Commands.literal("balance")
                            .requires(COMMAND_PERMISSIONS.requirementAny(CommandAction.CURRENCY_BALANCE_SELF,
                                    CommandAction.CURRENCY_BALANCE_OTHER))
                            .executes(context -> queryOwnBalance(context.getSource()))
                            .then(targetBalanceArgument())));
            dispatcher.register(walletCommand("money"));
            dispatcher.register(titleCommand());
            dispatcher.register(cloudStorageCommand("cloudstorage"));
            dispatcher.register(cloudStorageCommand("cstorage"));
            dispatcher.register(Commands.literal("balance")
                    .requires(COMMAND_PERMISSIONS.requirementAny(CommandAction.CURRENCY_BALANCE_SELF,
                            CommandAction.CURRENCY_BALANCE_OTHER))
                    .executes(context -> queryOwnBalance(context.getSource()))
                    .then(Commands.argument("player", GameProfileArgument.gameProfile())
                            .requires(COMMAND_PERMISSIONS.requirement(CommandAction.CURRENCY_BALANCE_OTHER))
                            .executes(context -> queryTargetBalance(context))));
        });
        System.out.println("[ModMind] omnitools initialized");
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

    static TitleEffectConfig titleEffectConfig() {
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

    static ModuleControlService moduleControlService() {
        return MODULE_CONTROL;
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
        return configSnapshot.enabled(module);
    }

    static ZoneId configuredZone() {
        return configSnapshot.zoneId();
    }

    static ZoneId configuredZone(net.minecraft.server.MinecraftServer server) {
        return configSnapshot.zoneId();
    }

    private static void applySnapshot(OmniToolsConfigSnapshot snapshot) {
        configSnapshot = snapshot;
        ServerText.setLanguage(snapshot.root().language());
        COMMAND_PERMISSIONS.update(snapshot.commandPermissions());
        rewardService = CheckinRewardService.from(snapshot.rewards());
        shopConfig = snapshot.shop();
        titleConfig = snapshot.titles();
        titleEffectConfig = snapshot.titleEffects();
        cloudStorageConfig = snapshot.cloudStorage();
        // Keep existing achievement menus bound to the live service. Its revision
        // invalidates their cached progress on the next menu refresh after reload.
        achievementService.replace(snapshot.achievements());
    }

    /** Applies one already-validated snapshot for both command reloads and module GUI changes. */
    static void applyRuntimeConfigChange(net.minecraft.server.MinecraftServer server,
                                         OmniToolsConfigSnapshot previous, OmniToolsConfigSnapshot current) {
        if (previous.enabled(ModuleId.ONLINE_REWARD) && !current.enabled(ModuleId.ONLINE_REWARD)) {
            onlineTimeRewardService().flushAll(server);
        }
        if (previous.enabled(ModuleId.SIDEBAR) && !current.enabled(ModuleId.SIDEBAR)) {
            sidebarService().clearAll(server);
        }
        if (previous.enabled(ModuleId.TITLES) && !current.enabled(ModuleId.TITLES)) {
            TitleDisplayService.clearAll(server);
        }
        applySnapshot(current);
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
                .requires(COMMAND_PERMISSIONS.requirement(CommandAction.SHOP_OPEN)
                        .and(source -> isModuleEnabled(ModuleId.SHOP)))
                .executes(context -> openShopMenu(context.getSource().getPlayerOrException()))
                .then(Commands.literal("open")
                        .executes(context -> openShopMenu(context.getSource().getPlayerOrException())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> cloudStorageCommand(String literal) {
        return Commands.literal(literal)
                .requires(COMMAND_PERMISSIONS.requirement(CommandAction.STORAGE_OPEN)
                        .and(source -> isModuleEnabled(ModuleId.CLOUD_STORAGE)))
                .executes(context -> openCloudStorageMenu(context.getSource().getPlayerOrException()))
                .then(Commands.literal("open")
                        .executes(context -> openCloudStorageMenu(context.getSource().getPlayerOrException())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> achievementCommand() {
        return Commands.literal("achievements")
                .requires(COMMAND_PERMISSIONS.requirement(CommandAction.ACHIEVEMENTS_OPEN)
                        .and(source -> isModuleEnabled(ModuleId.ACHIEVEMENTS)))
                .executes(context -> openAchievementMenu(context.getSource().getPlayerOrException()))
                .then(Commands.literal("open")
                        .executes(context -> openAchievementMenu(context.getSource().getPlayerOrException())));
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
        return Commands.literal("title")
                .requires(COMMAND_PERMISSIONS.requirementAny(CommandAction.TITLE_OPEN, CommandAction.TITLE_GRANT,
                        CommandAction.TITLE_REVOKE).and(source -> isModuleEnabled(ModuleId.TITLES)))
                .executes(context -> openTitleMenu(context.getSource().getPlayerOrException()))
                .then(Commands.literal("open")
                        .requires(COMMAND_PERMISSIONS.requirement(CommandAction.TITLE_OPEN))
                        .executes(context -> openTitleMenu(context.getSource().getPlayerOrException())))
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
                        .then(titleChangeArgument(false)));
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
        for (NameAndId profile : profiles) {
            if (give) {
                TitleConfig.GrantResult result = titleConfig().grant(profile.id(), profile.name(), titleId);
                context.getSource().sendSuccess(() -> ServerText.translatable(
                        result == TitleConfig.GrantResult.GRANTED
                                ? "command.omnitools.title.give" : "command.omnitools.title.already_owned",
                        title.get().displayComponent(), profile.name()), true);
            } else {
                TitleConfig.RevokeResult result = titleConfig().revoke(profile.id(), profile.name(), titleId);
                context.getSource().sendSuccess(() -> ServerText.translatable(
                        result == TitleConfig.RevokeResult.REVOKED
                                ? "command.omnitools.title.remove" : "command.omnitools.title.not_owned",
                        title.get().displayComponent(), profile.name()), true);
            }

            ServerPlayer onlinePlayer = context.getSource().getServer().getPlayerList().getPlayer(profile.id());
            if (onlinePlayer != null) {
                TitleDisplayService.refreshPlayer(onlinePlayer);
                TitleEffectService.refresh(onlinePlayer);
            }
        }
        return profiles.size();
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

    private static LiteralArgumentBuilder<CommandSourceStack> rewardsCommand() {
        return Commands.literal("rewards")
                .requires(COMMAND_PERMISSIONS.requirementAny(CommandAction.REWARDS_RETRY, CommandAction.REWARDS_ADMIN))
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
                    "resolved_by:" + context.getSource().getTextName());
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
        return events;
    }

    private static boolean belongsToPlayer(String eventId, java.util.UUID playerId) {
        return eventId != null && (eventId.startsWith("checkin:" + playerId + ":")
                || eventId.startsWith("achievement:" + playerId + ":"));
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

    private static int retryRewards(ServerPlayer player) {
        if (isModuleEnabled(ModuleId.DAILY_CHECKIN)) {
            rewardService().retryPending(player);
        }
        if (isModuleEnabled(ModuleId.ACHIEVEMENTS)) {
            achievementService().retryPending(player);
        }
        if (player.containerMenu instanceof CheckinScreenHandler checkinMenu) {
            checkinMenu.refreshAfterRewardRetry();
        } else if (player.containerMenu instanceof CheckinRewardInfoScreenHandler rewardMenu) {
            rewardMenu.refreshAfterRewardRetry();
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
                    || (!snapshot.enabled(ModuleId.CLOUD_STORAGE)
                    && player.containerMenu instanceof CloudStorageScreenHandler)
                    || (!COMMAND_PERMISSIONS.canUse(player, CommandAction.STORAGE_OPEN)
                    && player.containerMenu instanceof CloudStorageScreenHandler)
                    || (!COMMAND_PERMISSIONS.canUse(player, CommandAction.CONFIG_RELOAD)
                    && player.containerMenu instanceof ModuleManagerScreenHandler)
                    || (!snapshot.enabled(ModuleId.COMMAND_MENU)
                    && player.containerMenu instanceof CommandMenuScreenHandler)
                    || (!COMMAND_PERMISSIONS.canUse(player, CommandAction.COMMAND_MENU_OPEN)
                    && player.containerMenu instanceof CommandMenuScreenHandler);
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
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> CloudStorageScreenHandler.createServer(syncId, inventory, player,
                        cloudStorageConfig(), 0),
                ServerText.translatable("gui.omnitools.storage.title")));
        return 1;
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
