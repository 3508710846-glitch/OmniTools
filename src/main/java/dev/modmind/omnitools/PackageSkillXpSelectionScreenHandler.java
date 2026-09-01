package dev.modmind.omnitools;

import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.packages.PackageData;
import dev.modmind.omnitools.packages.PackageInstance;
import dev.modmind.omnitools.packages.PackageSkillXpGrant;
import dev.modmind.omnitools.packages.PackageService;
import dev.modmind.omnitools.permissions.CommandAction;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.UUID;

/** Server-side tree selection for a self-selected package skill-XP reward. */
public final class PackageSkillXpSelectionScreenHandler extends ChestMenu {
    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;
    private static final int BACK_SLOT = 47;
    private static final int CLOSE_SLOT = GuiSlots.HEADER_CLOSE_54;

    private final SimpleContainer container;
    private final ServerPlayer owner;
    private final UUID ownerId;
    private final UUID instanceId;
    private final int listPage;
    private final int previewPage;
    private PackageSkillXpGrant pendingGrant;

    private PackageSkillXpSelectionScreenHandler(int syncId, Inventory inventory, SimpleContainer container,
                                                 ServerPlayer owner, UUID instanceId, int listPage, int previewPage) {
        super(MenuType.GENERIC_9x6, syncId, inventory, container, ROWS);
        this.container = container;
        this.owner = owner;
        this.ownerId = owner == null ? null : owner.getUUID();
        this.instanceId = instanceId;
        this.listPage = Math.max(0, listPage);
        this.previewPage = Math.max(0, previewPage);
        if (owner != null) {
            render();
        }
    }

    static void open(ServerPlayer player, UUID instanceId, int listPage, int previewPage) {
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> new PackageSkillXpSelectionScreenHandler(syncId, inventory,
                        new SimpleContainer(SIZE), player, instanceId, listPage, previewPage),
                Component.literal("选择技能树")));
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer) || ownerId == null || !ownerId.equals(serverPlayer.getUUID())
                || clickType != ClickType.PICKUP || button != 0) {
            return;
        }
        if (!ModMindEntry.isModuleEnabled(ModuleId.PACKAGES)
                || !ModMindEntry.hasCommandPermission(serverPlayer, CommandAction.PACKAGE_OPEN)) {
            serverPlayer.closeContainer();
            return;
        }
        if (slotId == CLOSE_SLOT) {
            serverPlayer.closeContainer();
            return;
        }
        if (slotId == BACK_SLOT) {
            PackagePreviewScreenHandler.open(serverPlayer, instanceId, listPage, previewPage);
            return;
        }
        int index = GuiSlots.contentIndex54(slotId);
        if (pendingGrant == null || index < 0 || index >= pendingGrant.options().size()) {
            return;
        }
        PackageSkillXpGrant.TreeOption option = pendingGrant.options().get(index);
        if (!ModMindEntry.packageService().resolveSkillXpChoice(serverPlayer, instanceId, pendingGrant.id(), option.treeId())) {
            render();
            broadcastChanges();
            return;
        }
        serverPlayer.displayClientMessage(Component.literal("已选择技能树：" + option.display()).withStyle(ChatFormatting.GREEN), true);
        PackageService.OpenResult result = ModMindEntry.packageService().open(serverPlayer, instanceId);
        if (result.result() == PackageService.Result.SELECTION_REQUIRED) {
            open(serverPlayer, instanceId, listPage, previewPage);
        } else {
            if (result.result() == PackageService.Result.OPENED) {
                serverPlayer.displayClientMessage(Component.literal("技能经验礼包已领取：" + pendingGrant.amount()
                        + " 点经验已发放").withStyle(ChatFormatting.GREEN), true);
            }
            PackageScreenHandler.open(serverPlayer, listPage);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    private void render() {
        GuiTheme.clear(container);
        pendingGrant = findInstance(owner) == null ? null : findInstance(owner).skillXpGrants().stream()
                .filter(PackageSkillXpGrant::requiresPlayerChoice).findFirst().orElse(null);
        if (pendingGrant == null) {
            container.setItem(GuiSlots.HEADER_CENTER_54, GuiTheme.status(Items.BARRIER, Component.literal("无需选择"),
                    ChatFormatting.RED, List.of(Component.literal("该礼包没有待选择的技能树")), false));
            container.setItem(BACK_SLOT, GuiTheme.navigation(Items.ARROW, Component.literal("返回预览"), null));
            container.setItem(CLOSE_SLOT, GuiNavigationService.close());
            return;
        }
        for (int index = 0; index < pendingGrant.options().size(); index++) {
            PackageSkillXpGrant.TreeOption option = pendingGrant.options().get(index);
            Identifier identifier = Identifier.tryParse(option.iconId());
            ItemStack icon = new ItemStack(identifier == null ? Items.EXPERIENCE_BOTTLE
                    : BuiltInRegistries.ITEM.getOptional(identifier).orElse(Items.EXPERIENCE_BOTTLE));
            container.setItem(GuiSlots.contentSlot54(index), GuiTheme.status(icon.getItem(),
                    Component.literal(option.display()), ChatFormatting.AQUA,
                    List.of(Component.literal("获得 " + pendingGrant.amount() + " 技能经验").withStyle(ChatFormatting.GOLD),
                            Component.literal("点击选择").withStyle(ChatFormatting.GREEN)), false));
        }
        container.setItem(GuiSlots.HEADER_LEFT_54, GuiTheme.status(Items.EXPERIENCE_BOTTLE,
                Component.literal("自选技能经验"), ChatFormatting.LIGHT_PURPLE,
                List.of(Component.literal("经验：" + pendingGrant.amount()).withStyle(ChatFormatting.GOLD)), false));
        container.setItem(GuiSlots.HEADER_CENTER_54, GuiTheme.status(Items.BOOK, Component.literal("选择技能树"),
                ChatFormatting.AQUA, List.of(Component.literal("选择后立即开始领取礼包").withStyle(ChatFormatting.GRAY)), false));
        container.setItem(BACK_SLOT, GuiTheme.navigation(Items.ARROW, Component.literal("返回预览"),
                Component.literal("暂不选择")));
        container.setItem(CLOSE_SLOT, GuiNavigationService.close());
    }

    private PackageInstance findInstance(ServerPlayer player) {
        if (player == null || instanceId == null) {
            return null;
        }
        return PackageData.get(player.level().getServer()).find(player.getUUID(), instanceId).orElse(null);
    }
}
