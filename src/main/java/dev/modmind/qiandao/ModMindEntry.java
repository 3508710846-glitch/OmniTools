package dev.modmind.qiandao;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;

public final class ModMindEntry implements ModInitializer {
    public static final String MOD_ID = "qiandao";

    @Override
    public void onInitialize() {
        CheckinScreenHandler.register();
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

    private static int openCheckinMenu(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> CheckinScreenHandler.createServer(syncId, inventory, player),
                Component.translatable("gui.qiandao.title")));
        return 1;
    }
}
