package dev.modmind.omnitools;

import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.packages.PackageData;
import dev.modmind.omnitools.packages.PackageDefinition;
import dev.modmind.omnitools.packages.PackageInstance;
import dev.modmind.omnitools.packages.PackageSkillXpGrant;
import dev.modmind.omnitools.permissions.CommandAction;
import dev.modmind.omnitools.text.TextTemplateRenderer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Read-only package contents preview. It never selects a random reward or starts delivery. */
public final class PackagePreviewScreenHandler extends ChestMenu {
    private static final int ROWS = 6;
    private static final int CONTAINER_SIZE = ROWS * 9;
    private static final int CONTENT_SLOTS = GuiSlots.CONTENT_SLOT_COUNT_54;
    private static final int PREVIOUS_SLOT = GuiSlots.FIRST_ACTION_SLOT_54;
    private static final int BACK_SLOT = 47;
    private static final int PAGE_SLOT = GuiSlots.CENTER_54;
    private static final int CONFIRM_SLOT = 51;
    private static final int NEXT_SLOT = GuiSlots.LAST_SLOT_54;
    private static final int CLOSE_SLOT = GuiSlots.HEADER_CLOSE_54;

    private final SimpleContainer container;
    private final ServerPlayer owner;
    private final UUID ownerId;
    private final UUID instanceId;
    private final int listPage;
    private int page;
    private int pageCount = 1;
    private List<ItemStack> entries = List.of();

    private PackagePreviewScreenHandler(int syncId, Inventory inventory, SimpleContainer container, ServerPlayer owner,
                                        UUID instanceId, int listPage, int page) {
        super(MenuType.GENERIC_9x6, syncId, inventory, container, ROWS);
        this.container = container;
        this.owner = owner;
        this.ownerId = owner == null ? null : owner.getUUID();
        this.instanceId = instanceId;
        this.listPage = Math.max(0, listPage);
        this.page = Math.max(0, page);
        if (owner != null) {
            refreshContents();
        }
    }

    static void open(ServerPlayer player, UUID instanceId, int listPage, int page) {
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> new PackagePreviewScreenHandler(syncId, inventory,
                        new SimpleContainer(CONTAINER_SIZE), player, instanceId, listPage, page),
                Component.literal("礼包预览")));
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
            PackageScreenHandler.open(serverPlayer, listPage);
            return;
        }
        if (slotId == PREVIOUS_SLOT && page > 0) {
            open(serverPlayer, instanceId, listPage, page - 1);
            return;
        }
        if (slotId == NEXT_SLOT && page + 1 < pageCount) {
            open(serverPlayer, instanceId, listPage, page + 1);
            return;
        }
        if (slotId == CONFIRM_SLOT) {
            PackageInstance instance = findInstance(serverPlayer);
            if (PackageScreenHandler.canOpen(instance)) {
                PackageConfirmScreenHandler.open(serverPlayer, instanceId, listPage, page);
            } else {
                refreshContents();
                broadcastChanges();
            }
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    private void refreshContents() {
        GuiTheme.clear(container);
        PackageInstance instance = findInstance(owner);
        if (instance == null) {
            container.setItem(GuiSlots.HEADER_CENTER_54, GuiTheme.status(Items.BARRIER, Component.literal("礼包不存在"),
                    ChatFormatting.RED, List.of(Component.literal("该礼包已被移除")), false));
            container.setItem(BACK_SLOT, GuiTheme.navigation(Items.ARROW, Component.literal("返回礼包列表"), null));
            container.setItem(CLOSE_SLOT, GuiNavigationService.close());
            return;
        }
        entries = displayContents(instance);
        pageCount = Math.max(1, (entries.size() + CONTENT_SLOTS - 1) / CONTENT_SLOTS);
        page = Math.max(0, Math.min(page, pageCount - 1));
        int first = page * CONTENT_SLOTS;
        int visible = Math.min(CONTENT_SLOTS, entries.size() - first);
        for (int index = 0; index < visible; index++) {
            container.setItem(GuiSlots.contentSlot54(index), entries.get(first + index));
        }
        String mode = instance.mode() == PackageDefinition.Mode.ALL ? "全部获得" : "随机获得一种";
        container.setItem(GuiSlots.HEADER_LEFT_54, GuiTheme.status(Items.CHEST,
                TextTemplateRenderer.render(owner, instance.displayName()), ChatFormatting.GOLD,
                List.of(Component.literal(mode).withStyle(ChatFormatting.AQUA)), false));
        container.setItem(GuiSlots.HEADER_CENTER_54, GuiTheme.status(Items.BOOK,
                Component.literal("礼包预览"), ChatFormatting.AQUA,
                List.of(Component.literal(instance.mode() == PackageDefinition.Mode.ALL
                        ? "以下内容将在打开后发放" : "物品随机一种，技能经验按其配置发放").withStyle(ChatFormatting.GRAY)), false));
        container.setItem(CLOSE_SLOT, GuiNavigationService.close());
        if (page > 0) {
            container.setItem(PREVIOUS_SLOT, GuiNavigationService.previous());
        }
        container.setItem(BACK_SLOT, GuiTheme.navigation(Items.ARROW, Component.literal("返回礼包列表"),
                Component.literal("不打开礼包")));
        container.setItem(PAGE_SLOT, GuiNavigationService.page(page + 1, pageCount, entries.size()));
        if (PackageScreenHandler.canOpen(instance)) {
            container.setItem(CONFIRM_SLOT, GuiTheme.navigation(Items.LIME_DYE, Component.literal("确认打开"),
                    Component.literal("打开后开始发放")));
        } else {
            container.setItem(CONFIRM_SLOT, GuiTheme.navigation(Items.BARRIER,
                    Component.literal(PackageScreenHandler.statusLabel(instance.status())),
                    Component.literal("当前状态不可打开")));
        }
        if (page + 1 < pageCount) {
            container.setItem(NEXT_SLOT, GuiNavigationService.next());
        }
    }

    private List<ItemStack> displayContents(PackageInstance instance) {
        List<ItemStack> result = new ArrayList<>();
        List<ItemStack> items = instance.items();
        List<Long> quantities = instance.quantities();
        for (int index = 0; index < items.size(); index++) {
            ItemStack display = TextTemplateRenderer.renderItemText(owner, items.get(index));
            long quantity = quantities.get(index);
            display.setCount((int) Math.min(display.getMaxStackSize(), quantity));
            ItemLore existingLore = display.get(DataComponents.LORE);
            List<Component> lore = new ArrayList<>(existingLore == null ? List.of() : existingLore.lines());
            lore.add(Component.literal("数量：" + quantity).withStyle(ChatFormatting.GOLD));
            if (instance.mode() == PackageDefinition.Mode.RANDOM_ONE) {
                lore.add(Component.literal("随机候选").withStyle(ChatFormatting.AQUA));
            }
            result.add(GuiStatusItem.create(display, display.getHoverName(), GuiStatusItem.State.OWNED,
                    GuiTextService.compactLore(lore, 6)));
        }
        for (PackageSkillXpGrant grant : instance.skillXpGrants()) {
            ItemStack display = new ItemStack(Items.EXPERIENCE_BOTTLE);
            List<Component> lore = new ArrayList<>();
            lore.add(Component.literal("经验：" + grant.amount()).withStyle(ChatFormatting.GOLD));
            String mode = switch (grant.mode()) {
                case FIXED -> "指定技能树";
                case RANDOM -> "随机技能树";
                case PLAYER_CHOICE -> "自选技能树";
            };
            lore.add(Component.literal(mode).withStyle(ChatFormatting.AQUA));
            if (!grant.resolvedTreeId().isBlank()) {
                PackageSkillXpGrant.TreeOption selected = grant.options().stream()
                        .filter(option -> option.treeId().equals(grant.resolvedTreeId())).findFirst().orElse(null);
                lore.add(Component.literal("目标：" + (selected == null ? grant.resolvedTreeId() : selected.display()))
                        .withStyle(ChatFormatting.GREEN));
            } else {
                for (PackageSkillXpGrant.TreeOption option : grant.options()) {
                    lore.add(Component.literal("候选：" + option.display()).withStyle(ChatFormatting.GRAY));
                }
            }
            Component title = Component.literal("技能经验：" + grant.id()).withStyle(ChatFormatting.LIGHT_PURPLE);
            result.add(GuiStatusItem.create(display, title, GuiStatusItem.State.OWNED, GuiTextService.compactLore(lore, 6)));
        }
        return List.copyOf(result);
    }

    private PackageInstance findInstance(ServerPlayer player) {
        if (player == null || instanceId == null) {
            return null;
        }
        return PackageData.get(player.level().getServer()).find(player.getUUID(), instanceId).orElse(null);
    }
}
