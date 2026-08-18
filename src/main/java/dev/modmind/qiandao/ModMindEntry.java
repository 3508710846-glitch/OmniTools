package dev.modmind.qiandao;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;

import java.time.LocalDate;

public final class ModMindEntry implements ModInitializer {
    public static final String MOD_ID = "qiandao";

    @Override
    public void onInitialize() {
        CheckinScreenHandler.register();
        CheckinRecordsScreenHandler.register();
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
                    .then(clearCommand());
            dispatcher.register(command);
            dispatcher.register(Commands.literal("checkin")
                    .executes(context -> openCheckinMenu(context.getSource().getPlayerOrException()))
                    .then(clearCommand()));
        });
        System.out.println("[ModMind] qiandao initialized");
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
