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
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.SimpleMenuProvider;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Optional;
import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.config.QiandaoConfigManager;
import dev.modmind.omnitools.config.QiandaoConfigSnapshot;

public final class ModMindEntry implements ModInitializer {
    public static final String MOD_ID = "omnitools";
    private static final Permission CLOUD_STORAGE_PERMISSION = Permission.Atom.create(
            Identifier.fromNamespaceAndPath(MOD_ID, "cloud_storage"));
    private static CheckinRewardService rewardService;
    private static OnlineTimeRewardService onlineTimeRewardService;
    private static ShopConfig shopConfig = ShopConfig.empty();
    private static TitleConfig titleConfig = TitleConfig.empty();
    private static TitleEffectConfig titleEffectConfig = TitleEffectConfig.empty();
    private static CloudStorageConfig cloudStorageConfig = CloudStorageConfig.defaultConfig();
    private static AchievementService achievementService = AchievementService.empty();
    private static final QiandaoConfigManager CONFIG_MANAGER = new QiandaoConfigManager();
    private static volatile QiandaoConfigSnapshot configSnapshot = CONFIG_MANAGER.snapshot();

    @Override
    public void onInitialize() {
        CheckinScreenHandler.register();
        CheckinRecordsScreenHandler.register();
        OnlineTimeRewardScreenHandler.register();
        ShopScreenHandler.register();
        TitleScreenHandler.register();
        CloudStorageScreenHandler.register();
        AchievementScreenHandler.register();
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            rewardService = CheckinRewardService.from(CheckinRewardConfig.empty());
            onlineTimeRewardService = new OnlineTimeRewardService();
            achievementService = AchievementService.empty();
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            TitleData.bind(server);
            TitleData.importLegacy(server);
            applySnapshot(CONFIG_MANAGER.load(server));
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            onlineTimeRewardService().flushAll(server);
            TitleEffectService.removeAll(server);
            TitleData.unbind(server);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (isModuleEnabled(ModuleId.ONLINE_REWARD)) {
                onlineTimeRewardService().tick(server);
            }
            if (isModuleEnabled(ModuleId.TITLE_EFFECTS)) {
                TitleEffectService.tick(server);
            }
            if (isModuleEnabled(ModuleId.ACHIEVEMENTS)
                    && server.getTickCount() % AchievementService.CHECK_INTERVAL_TICKS == 0) {
                achievementService().checkAll(server);
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
            }
            LocalDate date = CheckinData.today(server);
            if (isModuleEnabled(ModuleId.DAILY_CHECKIN)
                    && !CheckinData.get(server).hasSigned(player.getUUID(), date.toEpochDay())) {
                sendCheckinReminder(player);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (isModuleEnabled(ModuleId.TITLE_EFFECTS)) {
                TitleEffectService.remove(handler.getPlayer());
            }
            if (isModuleEnabled(ModuleId.ONLINE_REWARD)) {
                onlineTimeRewardService().onDisconnect(handler.getPlayer());
            }
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (isModuleEnabled(ModuleId.TITLE_EFFECTS)) {
                TitleEffectService.forget(oldPlayer);
                TitleEffectService.refresh(newPlayer);
            }
            if (isModuleEnabled(ModuleId.TITLES)) {
                TitleDisplayService.refreshPlayer(newPlayer);
            }
        });
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(ModMindEntry::broadcastTitledChatMessage);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var command = Commands.literal("omnitools")
                    .executes(context -> openCheckinMenu(context.getSource().getPlayerOrException()))
                    .then(Commands.literal("open")
                            .executes(context -> openCheckinMenu(context.getSource().getPlayerOrException())))
                    .then(onlineTimeCommand())
                    .then(shopCommand())
                    .then(titleCommand())
                    .then(cloudStorageCommand("storage"))
                    .then(achievementCommand())
                    .then(clearCommand())
                    .then(walletCommand("currency"))
                    .then(Commands.literal("balance")
                            .executes(context -> queryOwnBalance(context.getSource()))
                            .then(targetBalanceArgument()))
                    .then(Commands.literal("add")
                            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                            .then(currencyChangeArgument(true)))
                    .then(Commands.literal("remove")
                            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                            .then(currencyChangeArgument(false)))
                    .then(Commands.literal("reload")
                            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                            .executes(context -> reloadRewards(context.getSource())));
            dispatcher.register(command);
            dispatcher.register(Commands.literal("checkin")
                    .executes(context -> openCheckinMenu(context.getSource().getPlayerOrException()))
                    .then(onlineTimeCommand())
                    .then(shopCommand())
                    .then(titleCommand())
                    .then(cloudStorageCommand("storage"))
                    .then(achievementCommand())
                    .then(clearCommand())
                    .then(walletCommand("currency"))
                    .then(Commands.literal("balance")
                            .executes(context -> queryOwnBalance(context.getSource()))
                            .then(targetBalanceArgument())));
            dispatcher.register(walletCommand("money"));
            dispatcher.register(titleCommand());
            dispatcher.register(cloudStorageCommand("cloudstorage"));
            dispatcher.register(cloudStorageCommand("cstorage"));
            dispatcher.register(Commands.literal("balance")
                    .executes(context -> queryOwnBalance(context.getSource()))
                    .then(Commands.argument("player", GameProfileArgument.gameProfile())
                            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
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

    static OnlineTimeRewardService onlineTimeRewardService() {
        if (onlineTimeRewardService == null) {
            onlineTimeRewardService = new OnlineTimeRewardService();
        }
        return onlineTimeRewardService;
    }

    static ShopConfig shopConfig() {
        return shopConfig;
    }

    static TitleConfig titleConfig() {
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

    static QiandaoConfigSnapshot configSnapshot() {
        return configSnapshot;
    }

    static boolean isModuleEnabled(ModuleId module) {
        return configSnapshot.enabled(module);
    }

    static ZoneId configuredZone() {
        return configSnapshot.zoneId();
    }

    static ZoneId configuredZone(net.minecraft.server.MinecraftServer server) {
        return configSnapshot.zoneId();
    }

    private static void applySnapshot(QiandaoConfigSnapshot snapshot) {
        configSnapshot = snapshot;
        rewardService = CheckinRewardService.from(snapshot.rewards());
        shopConfig = snapshot.shop();
        titleConfig = snapshot.titles();
        titleEffectConfig = snapshot.titleEffects();
        cloudStorageConfig = snapshot.cloudStorage();
        achievementService = AchievementService.from(snapshot.achievements());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> onlineTimeCommand() {
        return Commands.literal("online")
                .requires(source -> isModuleEnabled(ModuleId.ONLINE_REWARD))
                .executes(context -> openOnlineTimeRewardMenu(context.getSource().getPlayerOrException()))
                .then(Commands.literal("rewards")
                        .executes(context -> openOnlineTimeRewardMenu(context.getSource().getPlayerOrException())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> shopCommand() {
        return Commands.literal("shop")
                .requires(source -> isModuleEnabled(ModuleId.SHOP))
                .executes(context -> openShopMenu(context.getSource().getPlayerOrException()))
                .then(Commands.literal("open")
                        .executes(context -> openShopMenu(context.getSource().getPlayerOrException())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> cloudStorageCommand(String literal) {
        return Commands.literal(literal)
                .requires(source -> isModuleEnabled(ModuleId.CLOUD_STORAGE) && hasCloudStoragePermission(source))
                .executes(context -> openCloudStorageMenu(context.getSource().getPlayerOrException()))
                .then(Commands.literal("open")
                        .executes(context -> openCloudStorageMenu(context.getSource().getPlayerOrException())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> achievementCommand() {
        return Commands.literal("achievements")
                .requires(source -> isModuleEnabled(ModuleId.ACHIEVEMENTS))
                .executes(context -> openAchievementMenu(context.getSource().getPlayerOrException()))
                .then(Commands.literal("open")
                        .executes(context -> openAchievementMenu(context.getSource().getPlayerOrException())));
    }

    private static boolean hasCloudStoragePermission(CommandSourceStack source) {
        return source.permissions().hasPermission(CLOUD_STORAGE_PERMISSION)
                || Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source);
    }

    static boolean hasCloudStoragePermissionForPlayer(ServerPlayer player) {
        return hasCloudStoragePermission(player.createCommandSourceStack());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> titleCommand() {
        return Commands.literal("title")
                .requires(source -> isModuleEnabled(ModuleId.TITLES))
                .executes(context -> openTitleMenu(context.getSource().getPlayerOrException()))
                .then(Commands.literal("open")
                        .executes(context -> openTitleMenu(context.getSource().getPlayerOrException())))
                .then(Commands.literal("give")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(titleChangeArgument(true)))
                .then(Commands.literal("add")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(titleChangeArgument(true)))
                .then(Commands.literal("remove")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(titleChangeArgument(false)))
                .then(Commands.literal("take")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(titleChangeArgument(false)));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> clearCommand() {
        return Commands.literal("clear")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(context -> clearToday(context.getSource()))
                .then(Commands.literal("today")
                        .executes(context -> clearToday(context.getSource())));
    }

    private static int clearToday(CommandSourceStack source) {
        int clearedPlayers = CheckinData.get(source.getServer()).clearToday();
        source.sendSuccess(() -> Component.translatable(
                "command.omnitools.clear.success", clearedPlayers), true);
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> walletCommand(String literal) {
        return Commands.literal(literal)
                .executes(context -> queryOwnBalance(context.getSource()))
                .then(Commands.literal("balance")
                        .executes(context -> queryOwnBalance(context.getSource()))
                        .then(targetBalanceArgument()))
                .then(Commands.literal("get")
                        .executes(context -> queryOwnBalance(context.getSource()))
                        .then(targetBalanceArgument()))
                .then(Commands.literal("add")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(currencyChangeArgument(true)))
                .then(Commands.literal("remove")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(currencyChangeArgument(false)))
                .then(Commands.literal("deduct")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(currencyChangeArgument(false)))
                .then(Commands.literal("take")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(currencyChangeArgument(false)));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, ?>
    targetBalanceArgument() {
        return Commands.argument("player", GameProfileArgument.gameProfile())
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
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
        ServerPlayer player = source.getPlayerOrException();
        long balance = CheckinData.get(player).getBalance(player.getUUID());
        source.sendSuccess(() -> Component.translatable(
                "command.omnitools.balance.self", balance), false);
        return 1;
    }

    private static int queryTargetBalance(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(context, "player");
        CheckinData data = CheckinData.get(context.getSource().getServer());
        for (NameAndId profile : profiles) {
            long balance = data.getBalance(profile.id());
            context.getSource().sendSuccess(() -> Component.translatable(
                    "command.omnitools.balance.other", profile.name(), balance), true);
        }
        return profiles.size();
    }

    private static int changeCurrency(CommandContext<CommandSourceStack> context, boolean add)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
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
            context.getSource().sendSuccess(() -> Component.translatable(
                    add ? "command.omnitools.currency.add" : "command.omnitools.currency.remove",
                    finalChanged, profile.name(), finalBalance), true);
        }
        return profiles.size();
    }

    private static int changeTitle(CommandContext<CommandSourceStack> context, boolean give)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String titleId = StringArgumentType.getString(context, "title");
        Optional<TitleConfig.TitleDefinition> title = titleConfig().definition(titleId);
        if (title.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("command.omnitools.title.unknown", titleId));
            return 0;
        }

        Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(context, "player");
        for (NameAndId profile : profiles) {
            if (give) {
                TitleConfig.GrantResult result = titleConfig().grant(profile.id(), profile.name(), titleId);
                context.getSource().sendSuccess(() -> Component.translatable(
                        result == TitleConfig.GrantResult.GRANTED
                                ? "command.omnitools.title.give" : "command.omnitools.title.already_owned",
                        title.get().displayComponent(), profile.name()), true);
            } else {
                TitleConfig.RevokeResult result = titleConfig().revoke(profile.id(), profile.name(), titleId);
                context.getSource().sendSuccess(() -> Component.translatable(
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
        long previousRevision = configSnapshot.revision();
        QiandaoConfigSnapshot candidate = CONFIG_MANAGER.load(source.getServer());
        if (candidate.revision() == previousRevision) {
            source.sendFailure(Component.translatable("command.omnitools.reload.failed"));
            return 0;
        }
        if (isModuleEnabled(ModuleId.ONLINE_REWARD) && !candidate.enabled(ModuleId.ONLINE_REWARD)) {
            onlineTimeRewardService().flushAll(source.getServer());
        }
        applySnapshot(candidate);
        closeDisabledMenus(source.getServer(), candidate);
        TitleDisplayService.refreshAll(source.getServer());
        if (isModuleEnabled(ModuleId.TITLE_EFFECTS)) {
            TitleEffectService.refreshAll(source.getServer());
        } else {
            TitleEffectService.removeAll(source.getServer());
        }
        if (isModuleEnabled(ModuleId.ACHIEVEMENTS)) {
            achievementService().checkAll(source.getServer());
        }
        source.sendSuccess(() -> Component.translatable("command.omnitools.reload.success",
                configSnapshot.revision()), true);
        return 1;
    }

    private static void closeDisabledMenus(net.minecraft.server.MinecraftServer server,
                                            QiandaoConfigSnapshot snapshot) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            boolean close = (!snapshot.enabled(ModuleId.DAILY_CHECKIN)
                    && (player.containerMenu instanceof CheckinScreenHandler
                    || player.containerMenu instanceof CheckinRecordsScreenHandler))
                    || (!snapshot.enabled(ModuleId.ONLINE_REWARD)
                    && player.containerMenu instanceof OnlineTimeRewardScreenHandler)
                    || (!snapshot.enabled(ModuleId.SHOP) && player.containerMenu instanceof ShopScreenHandler)
                    || (!snapshot.enabled(ModuleId.TITLES) && player.containerMenu instanceof TitleScreenHandler)
                    || (!snapshot.enabled(ModuleId.ACHIEVEMENTS)
                    && player.containerMenu instanceof AchievementScreenHandler)
                    || (!snapshot.enabled(ModuleId.CLOUD_STORAGE)
                    && player.containerMenu instanceof CloudStorageScreenHandler);
            if (close) {
                player.closeContainer();
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
        Component action = Component.translatable("message.omnitools.join_reminder.action")
                .withStyle(style -> style
                        .withColor(ChatFormatting.GOLD)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand("/omnitools")));
        player.sendSystemMessage(Component.translatable("message.omnitools.join_reminder.prefix").append(action));
    }

    static int openCheckinMenu(ServerPlayer player) {
        if (!isModuleEnabled(ModuleId.DAILY_CHECKIN)) {
            player.displayClientMessage(Component.translatable("message.omnitools.module_disabled"), true);
            return 0;
        }
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> CheckinScreenHandler.createServer(syncId, inventory, player),
                Component.translatable("gui.omnitools.title")));
        return 1;
    }

    static int openOnlineTimeRewardMenu(ServerPlayer player) {
        if (!isModuleEnabled(ModuleId.ONLINE_REWARD)) {
            player.displayClientMessage(Component.translatable("message.omnitools.module_disabled"), true);
            return 0;
        }
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> OnlineTimeRewardScreenHandler.createServer(syncId, inventory, player),
                Component.translatable("gui.omnitools.online_reward.menu_title")));
        return 1;
    }

    static int openShopMenu(ServerPlayer player) {
        if (!isModuleEnabled(ModuleId.SHOP)) {
            player.displayClientMessage(Component.translatable("message.omnitools.module_disabled"), true);
            return 0;
        }
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> ShopScreenHandler.createServer(syncId, inventory, player,
                        shopConfig(), 0),
                Component.translatable("gui.omnitools.shop.title")));
        return 1;
    }

    static int openTitleMenu(ServerPlayer player) {
        if (!isModuleEnabled(ModuleId.TITLES)) {
            player.displayClientMessage(Component.translatable("message.omnitools.module_disabled"), true);
            return 0;
        }
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> TitleScreenHandler.createServer(syncId, inventory, player, titleConfig()),
                Component.translatable("gui.omnitools.title.menu_title")));
        return 1;
    }

    static int openCloudStorageMenu(ServerPlayer player) {
        if (!isModuleEnabled(ModuleId.CLOUD_STORAGE) || !hasCloudStoragePermission(player.createCommandSourceStack())) {
            player.displayClientMessage(Component.translatable("message.omnitools.module_disabled"), true);
            return 0;
        }
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> CloudStorageScreenHandler.createServer(syncId, inventory, player,
                        cloudStorageConfig(), 0),
                Component.translatable("gui.omnitools.storage.title")));
        return 1;
    }

    static int openAchievementMenu(ServerPlayer player) {
        if (!isModuleEnabled(ModuleId.ACHIEVEMENTS)) {
            player.displayClientMessage(Component.translatable("message.omnitools.module_disabled"), true);
            return 0;
        }
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> AchievementScreenHandler.createServer(syncId, inventory, player,
                        achievementService(), 0),
                Component.translatable("gui.omnitools.achievement.title")));
        return 1;
    }
}
