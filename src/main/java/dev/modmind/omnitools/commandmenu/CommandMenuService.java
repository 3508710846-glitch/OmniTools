package dev.modmind.omnitools.commandmenu;

import dev.modmind.omnitools.ServerText;

import dev.modmind.omnitools.LegacyTitleText;
import dev.modmind.omnitools.ModMindEntry;
import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.text.TextTemplateRenderer;
import dev.modmind.omnitools.permissions.CommandAction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

/** Server-side command-menu access, permission checks and action execution. */
public final class CommandMenuService {
    private static final Map<UUID, Long> LAST_COMMAND_TICK = new HashMap<>();
    private CommandMenuService() {
    }

    public static boolean open(ServerPlayer player, String menuId) {
        if (!ModMindEntry.isModuleEnabled(ModuleId.COMMAND_MENU)
                || !ModMindEntry.hasCommandPermission(player, CommandAction.COMMAND_MENU_OPEN)) {
            player.displayClientMessage(ServerText.translatable("message.omnitools.command_menu.disabled"), true);
            return false;
        }
        CommandMenuDefinition definition = ModMindEntry.commandMenuConfig().menu(menuId);
        if (definition == null) {
            player.displayClientMessage(ServerText.translatable("message.omnitools.command_menu.unknown", menuId), true);
            return false;
        }
        if (!definition.permission().allows(player.createCommandSourceStack())) {
            player.displayClientMessage(ServerText.translatable("message.omnitools.command_menu.no_permission"), true);
            return false;
        }
        player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                (syncId, inventory, ignored) -> CommandMenuScreenHandler.createServer(syncId, inventory, player, menuId),
                definition.page().title(player)));
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
            case MESSAGE -> player.sendSystemMessage(TextTemplateRenderer.render(player, action.value()));
            case COMMAND -> executeCommand(player, action);
        }
    }

    private static void executeCommand(ServerPlayer player, CommandMenuAction action) {
        MinecraftServer server = player.level().getServer();
        String command = substitute(action.value(), player);
        var security = ModMindEntry.configSnapshot().root().commandSecurity();
        if (!security.allows(command)) {
            player.sendSystemMessage(ServerText.translatable("message.omnitools.command_menu.command_blocked"));
            System.err.println("[omnitools] Blocked menu command for " + player.getUUID() + ": " + command);
            return;
        }
        long tick = server.getTickCount();
        long previous = LAST_COMMAND_TICK.getOrDefault(player.getUUID(), Long.MIN_VALUE);
        if (security.cooldownTicks() > 0 && previous != Long.MIN_VALUE
                && tick - previous < security.cooldownTicks()) {
            player.sendSystemMessage(ServerText.translatable("message.omnitools.command_menu.command_cooldown"));
            return;
        }
        LAST_COMMAND_TICK.put(player.getUUID(), tick);
        if (action.runAs() == CommandMenuAction.RunAs.CONSOLE) {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
        } else {
            server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
        }
        System.out.println("[omnitools] Executed " + action.runAs().name().toLowerCase(java.util.Locale.ROOT)
                + " menu command for " + player.getUUID() + ": " + command);
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
