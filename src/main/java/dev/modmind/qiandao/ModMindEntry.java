package dev.modmind.qiandao;

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
import java.util.Collection;
import java.util.Optional;

public final class ModMindEntry implements ModInitializer {
    public static final String MOD_ID = "qiandao";
    private static CheckinRewardService rewardService;
    private static OnlineTimeRewardService onlineTimeRewardService;
    private static ShopConfig shopConfig = ShopConfig.empty();
    private static TitleConfig titleConfig = TitleConfig.empty();
    private static TitleEffectConfig titleEffectConfig = TitleEffectConfig.empty();

    @Override
    public void onInitialize() {
        CheckinScreenHandler.register();
        CheckinRecordsScreenHandler.register();
        OnlineTimeRewardScreenHandler.register();
        ShopScreenHandler.register();
        TitleScreenHandler.register();
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            rewardService = CheckinRewardService.load();
            onlineTimeRewardService = new OnlineTimeRewardService();
            titleConfig = TitleConfig.load();
            titleEffectConfig = TitleEffectConfig.load();
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> shopConfig = ShopConfig.load(server.registryAccess()));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> onlineTimeRewardService().flushAll(server));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            onlineTimeRewardService().tick(server);
            TitleEffectService.tick(server);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            onlineTimeRewardService().onJoin(player);
            titleConfig().rememberPlayer(player.getUUID(), player.getGameProfile().name());
            TitleDisplayService.refreshPlayer(player);
            TitleEffectService.refresh(player);
            LocalDate date = CheckinData.today();
            if (!CheckinData.get(server).hasSigned(player.getUUID(), date.toEpochDay())) {
                sendCheckinReminder(player);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            TitleEffectService.remove(handler.getPlayer());
            onlineTimeRewardService().onDisconnect(handler.getPlayer());
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            TitleEffectService.forget(oldPlayer);
            TitleDisplayService.refreshPlayer(newPlayer);
            TitleEffectService.refresh(newPlayer);
        });
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(ModMindEntry::broadcastTitledChatMessage);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var command = Commands.literal("qiandao")
                    .executes(context -> openCheckinMenu(context.getSource().getPlayerOrException()))
                    .then(Commands.literal("open")
                            .executes(context -> openCheckinMenu(context.getSource().getPlayerOrException())))
                    .then(onlineTimeCommand())
                    .then(shopCommand())
                    .then(titleCommand())
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
                    .then(clearCommand())
                    .then(walletCommand("currency"))
                    .then(Commands.literal("balance")
                            .executes(context -> queryOwnBalance(context.getSource()))
                            .then(targetBalanceArgument())));
            dispatcher.register(walletCommand("money"));
            dispatcher.register(titleCommand());
            dispatcher.register(Commands.literal("balance")
                    .executes(context -> queryOwnBalance(context.getSource()))
                    .then(Commands.argument("player", GameProfileArgument.gameProfile())
                            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                            .executes(context -> queryTargetBalance(context))));
        });
        System.out.println("[ModMind] qiandao initialized");
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

    private static LiteralArgumentBuilder<CommandSourceStack> onlineTimeCommand() {
        return Commands.literal("online")
                .executes(context -> openOnlineTimeRewardMenu(context.getSource().getPlayerOrException()))
                .then(Commands.literal("rewards")
                        .executes(context -> openOnlineTimeRewardMenu(context.getSource().getPlayerOrException())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> shopCommand() {
        return Commands.literal("shop")
                .executes(context -> openShopMenu(context.getSource().getPlayerOrException()))
                .then(Commands.literal("open")
                        .executes(context -> openShopMenu(context.getSource().getPlayerOrException())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> titleCommand() {
        return Commands.literal("title")
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
                "command.qiandao.clear.success", clearedPlayers), true);
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
                "command.qiandao.balance.self", balance), false);
        return 1;
    }

    private static int queryTargetBalance(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(context, "player");
        CheckinData data = CheckinData.get(context.getSource().getServer());
        for (NameAndId profile : profiles) {
            long balance = data.getBalance(profile.id());
            context.getSource().sendSuccess(() -> Component.translatable(
                    "command.qiandao.balance.other", profile.name(), balance), true);
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
                    add ? "command.qiandao.currency.add" : "command.qiandao.currency.remove",
                    finalChanged, profile.name(), finalBalance), true);
        }
        return profiles.size();
    }

    private static int changeTitle(CommandContext<CommandSourceStack> context, boolean give)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String titleId = StringArgumentType.getString(context, "title");
        Optional<TitleConfig.TitleDefinition> title = titleConfig().definition(titleId);
        if (title.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("command.qiandao.title.unknown", titleId));
            return 0;
        }

        Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(context, "player");
        for (NameAndId profile : profiles) {
            if (give) {
                TitleConfig.GrantResult result = titleConfig().grant(profile.id(), profile.name(), titleId);
                context.getSource().sendSuccess(() -> Component.translatable(
                        result == TitleConfig.GrantResult.GRANTED
                                ? "command.qiandao.title.give" : "command.qiandao.title.already_owned",
                        title.get().displayComponent(), profile.name()), true);
            } else {
                TitleConfig.RevokeResult result = titleConfig().revoke(profile.id(), profile.name(), titleId);
                context.getSource().sendSuccess(() -> Component.translatable(
                        result == TitleConfig.RevokeResult.REVOKED
                                ? "command.qiandao.title.remove" : "command.qiandao.title.not_owned",
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
        rewardService().reload();
        shopConfig = ShopConfig.load(source.getServer().registryAccess());
        titleConfig = TitleConfig.load();
        titleEffectConfig = TitleEffectConfig.load();
        TitleDisplayService.refreshAll(source.getServer());
        TitleEffectService.refreshAll(source.getServer());
        source.sendSuccess(() -> Component.translatable("command.qiandao.reload.success",
                CheckinRewardConfig.path().toString(), ShopConfig.path().toString(), TitleConfig.path().toString(),
                TitleEffectConfig.path().toString()), true);
        return 1;
    }

    private static boolean broadcastTitledChatMessage(PlayerChatMessage message, ServerPlayer sender, ChatType.Bound parameters) {
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
        Component action = Component.translatable("message.qiandao.join_reminder.action")
                .withStyle(style -> style
                        .withColor(ChatFormatting.GOLD)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand("/qiandao")));
        player.sendSystemMessage(Component.translatable("message.qiandao.join_reminder.prefix").append(action));
    }

    static int openCheckinMenu(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> CheckinScreenHandler.createServer(syncId, inventory, player),
                Component.translatable("gui.qiandao.title")));
        return 1;
    }

    static int openOnlineTimeRewardMenu(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> OnlineTimeRewardScreenHandler.createServer(syncId, inventory, player),
                Component.translatable("gui.qiandao.online_reward.menu_title")));
        return 1;
    }

    static int openShopMenu(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> ShopScreenHandler.createServer(syncId, inventory, player,
                        shopConfig(), 0),
                Component.translatable("gui.qiandao.shop.title")));
        return 1;
    }

    static int openTitleMenu(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> TitleScreenHandler.createServer(syncId, inventory, player, titleConfig()),
                Component.translatable("gui.qiandao.title.menu_title")));
        return 1;
    }
}
