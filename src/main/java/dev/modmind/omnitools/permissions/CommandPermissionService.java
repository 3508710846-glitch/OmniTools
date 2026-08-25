package dev.modmind.omnitools.permissions;

import dev.modmind.omnitools.ServerText;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;

import java.util.function.Predicate;

/** Runtime permission checks backed by the current configuration snapshot. */
public final class CommandPermissionService {
    private static final Permission CLOUD_STORAGE = Permission.Atom.create(
            Identifier.fromNamespaceAndPath("omnitools", "cloud_storage"));
    private volatile CommandPermissionConfig config;

    public CommandPermissionService(CommandPermissionConfig config) {
        this.config = config;
    }

    public void update(CommandPermissionConfig config) {
        this.config = config;
    }

    public CommandPermissionConfig config() {
        return config;
    }

    public boolean canUse(CommandSourceStack source, CommandAction action) {
        if (source == null || source.getEntity() == null) {
            return true;
        }
        CommandPermissionConfig current = config;
        if (action == CommandAction.STORAGE_OPEN && current.storageAllowNativeNode()
                && source.permissions().hasPermission(CLOUD_STORAGE)) {
            return true;
        }
        return current.role(action).allows(source);
    }

    public boolean canUse(ServerPlayer player, CommandAction action) {
        return player != null && canUse(player.createCommandSourceStack(), action);
    }

    public Predicate<CommandSourceStack> requirement(CommandAction action) {
        return source -> canUse(source, action);
    }

    public Predicate<CommandSourceStack> requirementAny(CommandAction... actions) {
        return source -> canUseAny(source, actions);
    }

    public boolean canUseAny(CommandSourceStack source, CommandAction... actions) {
        for (CommandAction action : actions) {
            if (canUse(source, action)) {
                return true;
            }
        }
        return false;
    }

    public boolean deny(ServerPlayer player) {
        if (player != null) {
            player.displayClientMessage(ServerText.translatable("message.omnitools.permission_denied"), true);
        }
        return false;
    }
}
