package dev.modmind.omnitools.commandmenu;

import dev.modmind.omnitools.ModMindEntry;
import dev.modmind.omnitools.diagnostics.ModuleFaultBoundary;
import dev.modmind.omnitools.GuiTheme;
import dev.modmind.omnitools.ServerText;
import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.permissions.CommandAction;
import net.minecraft.ChatFormatting;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;

import java.util.UUID;
import java.util.List;

/** Server-authoritative vanilla generic chest menu for a configured command page. */
public final class CommandMenuScreenHandler extends ChestMenu {
    private final SimpleContainer menuContainer;
    private final UUID ownerId;
    private final ServerPlayer owner;
    private final String menuId;
    private int size;
    private long lastRevision = Long.MIN_VALUE;

    public CommandMenuScreenHandler(int syncId, Inventory inventory) {
        this(syncId, inventory, new SimpleContainer(27), null, "");
    }

    private CommandMenuScreenHandler(int syncId, Inventory inventory, SimpleContainer container,
                                     ServerPlayer owner, String menuId) {
        super(container.getContainerSize() == 54 ? MenuType.GENERIC_9x6 : MenuType.GENERIC_9x3,
                syncId, inventory, container, container.getContainerSize() / 9);
        this.menuContainer = container;
        this.owner = owner;
        this.ownerId = owner == null ? null : owner.getUUID();
        this.menuId = menuId;
        this.size = container.getContainerSize();
        if (owner != null) {
            refreshContents();
        }
    }

    public static CommandMenuScreenHandler createServer(int syncId, Inventory inventory,
                                                         ServerPlayer owner, String menuId) {
        CommandMenuDefinition definition = ModMindEntry.commandMenuConfig().menu(menuId);
        int size = definition == null ? 27 : definition.page().size();
        return new CommandMenuScreenHandler(syncId, inventory, new SimpleContainer(size), owner, menuId);
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            ModuleFaultBoundary.runPlayerAction(ModuleId.COMMAND_MENU, "menu_action", serverPlayer,
                    "command_action_stopped_before_next_action", () -> handleClick(slotId, button, clickType, player));
            return;
        }
        handleClick(slotId, button, clickType, player);
    }

    private void handleClick(int slotId, int button, ClickType clickType, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer) || ownerId == null
                || !ownerId.equals(serverPlayer.getUUID()) || clickType != ClickType.PICKUP
                || (button != 0 && button != 1) || slotId < 0 || slotId >= size) {
            return;
        }
        if (!isAccessible(serverPlayer)) {
            serverPlayer.closeContainer();
            return;
        }
        CommandMenuDefinition definition = ModMindEntry.commandMenuConfig().menu(menuId);
        CommandMenuItem item = definition.page().items().get(slotId);
        if (item == null) {
            return;
        }
        var actions = button == 0 ? item.leftClick() : item.rightClick();
        for (CommandMenuAction action : actions) {
            CommandMenuService.execute(serverPlayer, action);
            if (action.type() == CommandMenuAction.Type.OPEN_MENU
                    || action.type() == CommandMenuAction.Type.CLOSE_MENU) {
                break;
            }
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        return ItemStack.EMPTY;
    }

    @Override
    public void broadcastChanges() {
        if (owner != null) {
            if (!isAccessible(owner)) {
                owner.closeContainer();
            } else {
                CommandMenuDefinition definition = ModMindEntry.commandMenuConfig().menu(menuId);
                if (definition == null) {
                    owner.closeContainer();
                } else if (definition.page().size() != size) {
                    owner.closeContainer();
                    CommandMenuService.open(owner, menuId);
                } else if (lastRevision != ModMindEntry.configSnapshot().revision()) {
                    refreshContents();
                }
            }
        }
        super.broadcastChanges();
    }

    private boolean isAccessible(ServerPlayer player) {
        if (!ModMindEntry.isModuleEnabled(ModuleId.COMMAND_MENU)
                || !ModMindEntry.hasCommandPermission(player, CommandAction.COMMAND_MENU_OPEN)) {
            return false;
        }
        CommandMenuDefinition definition = ModMindEntry.commandMenuConfig().menu(menuId);
        return definition != null && definition.permission().allows(player.createCommandSourceStack());
    }

    private void refreshContents() {
        CommandMenuDefinition definition = ModMindEntry.commandMenuConfig().menu(menuId);
        if (definition == null) {
            return;
        }
        for (int slot = 0; slot < size; slot++) {
            menuContainer.setItem(slot, definition.page().fillerStack(owner));
        }
        for (CommandMenuItem item : definition.page().items().values()) {
            menuContainer.setItem(item.slot(), item.displayStack(owner));
        }
        if (definition.page().items().isEmpty()) {
            menuContainer.setItem(size / 2, GuiTheme.status(Items.BOOK,
                    ServerText.translatable("gui.omnitools.command_menu.empty"), ChatFormatting.GRAY,
                    List.of(ServerText.translatable("gui.omnitools.command_menu.empty_hint")), false));
        }
        lastRevision = ModMindEntry.configSnapshot().revision();
    }

    public void refreshFromConfig() {
        if (owner != null && ModMindEntry.commandMenuConfig().menu(menuId) != null) {
            if (ModMindEntry.commandMenuConfig().menu(menuId).page().size() != size) {
                owner.closeContainer();
                CommandMenuService.open(owner, menuId);
                return;
            }
            refreshContents();
        }
    }

    public String menuId() {
        return menuId;
    }
}
