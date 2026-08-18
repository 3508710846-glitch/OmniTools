package dev.modmind.qiandao;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
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
                            .executes(context -> openCheckinMenu(context.getSource().getPlayerOrException())));
            dispatcher.register(command);
            dispatcher.register(Commands.literal("checkin")
                    .executes(context -> openCheckinMenu(context.getSource().getPlayerOrException())));
        });
        System.out.println("[ModMind] qiandao initialized");
    }

    private static void sendCheckinReminder(ServerPlayer player) {
        Component action = Component.translatable("message.qiandao.join_reminder.action")
                .withStyle(style -> style
                        .withColor(ChatFormatting.GOLD)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand("/qiandao")));
        player.sendSystemMessage(Component.translatable("message.qiandao.join_reminder.prefix").append(action));
    }

    private static int openCheckinMenu(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> CheckinScreenHandler.createServer(syncId, inventory, player),
                Component.translatable("gui.qiandao.title")));
        return 1;
    }
}
