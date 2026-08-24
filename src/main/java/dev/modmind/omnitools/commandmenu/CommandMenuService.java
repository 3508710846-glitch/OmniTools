package dev.modmind.omnitools.commandmenu;

import dev.modmind.omnitools.LegacyTitleText;
import dev.modmind.omnitools.ModMindEntry;
import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.permissions.CommandAction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

/** Server-side command-menu access, permission checks and action execution. */
public final class CommandMenuService {
    private CommandMenuService() {
    }

    public static boolean open(ServerPlayer player, String menuId) {
        if (!ModMindEntry.isModuleEnabled(ModuleId.COMMAND_MENU)
                || !ModMindEntry.hasCommandPermission(player, CommandAction.COMMAND_MENU_OPEN)) {
            player.displayClientMessage(Component.translatable("message.omnitools.command_menu.disabled"), true);
            return false;
        }
        CommandMenuDefinition definition = ModMindEntry.commandMenuConfig().menu(menuId);
        if (definition == null) {
            player.displayClientMessage(Component.translatable("message.omnitools.command_menu.unknown", menuId), true);
            return false;
        }
        if (!definition.permission().allows(player.createCommandSourceStack())) {
            player.displayClientMessage(Component.translatable("message.omnitools.command_menu.no_permission"), true);
            return false;
        }
        player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                (syncId, inventory, ignored) -> CommandMenuScreenHandler.createServer(syncId, inventory, player, menuId),
                definition.page().title()));
        return true;
    }

    public static void execute(ServerPlayer player, CommandMenuAction action) {
        switch (action.type()) {
            case OPEN_MENU -> {
                open(player, action.value());
                return;
            }
            case CLOSE_MENU -> {
                player.closeContainer();
                return;
            }
            case MESSAGE -> player.sendSystemMessage(LegacyTitleText.parse(colored(action.value())));
            case COMMAND -> executeCommand(player, action);
        }
    }

    private static void executeCommand(ServerPlayer player, CommandMenuAction action) {
        MinecraftServer server = player.level().getServer();
        String command = substitute(action.value(), player);
        if (action.runAs() == CommandMenuAction.RunAs.CONSOLE) {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
        } else {
            server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
        }
    }

    private static String substitute(String command, ServerPlayer player) {
        Map<String, String> values = Map.of(
                "player_name", player.getGameProfile().name(),
                "player_uuid", player.getUUID().toString(),
                "player_x", Integer.toString(player.blockPosition().getX()),
                "player_y", Integer.toString(player.blockPosition().getY()),
                "player_z", Integer.toString(player.blockPosition().getZ()),
                "player_world", player.level().dimension().toString());
        String result = command;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private static String colored(String text) {
        return text.replace('&', '\u00a7');
    }
}
