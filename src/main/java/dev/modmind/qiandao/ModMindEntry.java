package dev.modmind.qiandao;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.SimpleMenuProvider;

import java.time.LocalDate;
import java.util.Collection;

public final class ModMindEntry implements ModInitializer {
    public static final String MOD_ID = "qiandao";
    private static CheckinRewardService rewardService;

    @Override
    public void onInitialize() {
        CheckinScreenHandler.register();
        CheckinRecordsScreenHandler.register();
        ServerLifecycleEvents.SERVER_STARTING.register(server -> rewardService = CheckinRewardService.load());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            LocalDate date = CheckinData.today();
            if (!CheckinData.get(server).hasSigned(player.getUUID(), date.toEpochDay())) {
                sendCheckinReminder(player);
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var command = Commands.literal("qiandao")
                    .executes(context -> openCheckinMenu(context.getSource().getPlayerOrException()))
                    .then(Commands.literal("open")
                            .executes(context -> openCheckinMenu(context.getSource().getPlayerOrException())))
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
                    .then(clearCommand())
                    .then(walletCommand("currency"))
                    .then(Commands.literal("balance")
                            .executes(context -> queryOwnBalance(context.getSource()))
                            .then(targetBalanceArgument())));
            dispatcher.register(walletCommand("money"));
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

    private static int reloadRewards(CommandSourceStack source) {
        rewardService().reload();
        source.sendSuccess(() -> Component.translatable("command.qiandao.reload.success",
                CheckinRewardConfig.path().toString()), true);
        return 1;
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
}
