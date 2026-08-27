package dev.modmind.omnitools;

import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.packages.PackageData;
import dev.modmind.omnitools.packages.PackageInstance;
import dev.modmind.omnitools.packages.PackageService;
import dev.modmind.omnitools.permissions.CommandAction;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Virtual package list; all actions are revalidated against the owning UUID. */
public final class PackageScreenHandler extends ChestMenu {
    private final SimpleContainer container;
    private final ServerPlayer owner;
    private final List<PackageInstance> entries;
    private PackageScreenHandler(int syncId, Inventory inventory, ServerPlayer owner) {
        super(MenuType.GENERIC_9x6, syncId, inventory, new SimpleContainer(54), 6);
        this.container = (SimpleContainer) getContainer(); this.owner = owner;
        this.entries = PackageData.get(owner.level().getServer()).list(owner.getUUID()).stream().filter(p -> p.status() != PackageInstance.Status.OPENED).toList();
        for (int i=0;i<Math.min(45, entries.size());i++) container.setItem(i, icon(entries.get(i)));
        container.setItem(49, GuiNavigationService.close());
    }
    public static PackageScreenHandler createServer(int syncId, Inventory inventory, ServerPlayer owner) { return new PackageScreenHandler(syncId, inventory, owner); }
    private ItemStack icon(PackageInstance instance) { var id = net.minecraft.resources.Identifier.tryParse(instance.iconId()); var item = id == null ? net.minecraft.world.item.Items.CHEST : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id).orElse(net.minecraft.world.item.Items.CHEST); ItemStack stack = new ItemStack(item); stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(instance.displayName()).withStyle(ChatFormatting.AQUA)); return stack; }
    @Override public void clicked(int slot, int button, ClickType type, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        if (owner == null || !owner.getUUID().equals(serverPlayer.getUUID()) || !ModMindEntry.isModuleEnabled(ModuleId.PACKAGES) || !ModMindEntry.hasCommandPermission(serverPlayer, CommandAction.PACKAGE_OPEN)) { serverPlayer.closeContainer(); return; }
        if (slot == 49) { serverPlayer.closeContainer(); return; }
        if (type == ClickType.PICKUP && slot >= 0 && slot < entries.size()) {
            PackageService.OpenResult result = ModMindEntry.packageService().open(serverPlayer, entries.get(slot).instanceId());
            serverPlayer.displayClientMessage(Component.literal(result.result().name()).withStyle(result.result() == PackageService.Result.OPENED ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
            serverPlayer.closeContainer();
        }
    }
    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
}
