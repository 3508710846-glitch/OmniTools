package dev.modmind.omnitools;

import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.packages.PackageData;
import dev.modmind.omnitools.packages.PackageInstance;
import dev.modmind.omnitools.permissions.CommandAction;
import dev.modmind.omnitools.text.TextTemplateRenderer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
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
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Paginated owner-only entry point for virtual package previews. */
public final class PackageScreenHandler extends ChestMenu {
    private static final int ROWS = 6;
    private static final int CONTAINER_SIZE = ROWS * 9;
    private static final int CONTENT_SLOTS = GuiSlots.CONTENT_SLOT_COUNT_54;
    private static final int PREVIOUS_SLOT = GuiSlots.FIRST_ACTION_SLOT_54;
    private static final int INBOX_SLOT = 47;
    private static final int PAGE_SLOT = GuiSlots.CENTER_54;
    private static final int NEXT_SLOT = GuiSlots.LAST_SLOT_54;
    private static final int HEADER_TITLE_SLOT = GuiSlots.HEADER_CENTER_54;
    private static final int CLOSE_SLOT = GuiSlots.HEADER_CLOSE_54;

    private final SimpleContainer container;
    private final ServerPlayer owner;
    private final UUID ownerId;
    private List<PackageInstance> entries = List.of();
    private int page;
    private int pageCount = 1;

    private PackageScreenHandler(int syncId, Inventory inventory, SimpleContainer container,
                                 ServerPlayer owner, int page) {
        super(MenuType.GENERIC_9x6, syncId, inventory, container, ROWS);
        this.container = container;
        this.owner = owner;
        this.ownerId = owner == null ? null : owner.getUUID();
        this.page = Math.max(0, page);
        if (owner != null) {
            refreshContents();
        }
    }

    public static PackageScreenHandler createServer(int syncId, Inventory inventory, ServerPlayer owner) {
        return createServer(syncId, inventory, owner, 0);
    }

    public static PackageScreenHandler createServer(int syncId, Inventory inventory, ServerPlayer owner, int page) {
        return new PackageScreenHandler(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), owner, page);
    }

    static void open(ServerPlayer player, int page) {
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> createServer(syncId, inventory, player, page),
                ServerText.translatable("gui.omnitools.packages.title")));
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
            GuiFeedbackService.click(serverPlayer);
            return;
        }
        if (slotId == PREVIOUS_SLOT && page > 0) {
            open(serverPlayer, page - 1);
            GuiFeedbackService.click(serverPlayer);
            return;
        }
        if (slotId == NEXT_SLOT && page + 1 < pageCount) {
            open(serverPlayer, page + 1);
            GuiFeedbackService.click(serverPlayer);
            return;
        }
        if (slotId == INBOX_SLOT) {
            if (ModMindEntry.hasCommandPermission(serverPlayer, CommandAction.REWARDS_RETRY)) {
                ModMindEntry.openRewardInbox(serverPlayer);
                GuiFeedbackService.click(serverPlayer);
            } else {
                serverPlayer.displayClientMessage(ServerText.translatable("message.omnitools.permission_denied"), true);
                GuiFeedbackService.failure(serverPlayer);
            }
            return;
        }
        int localIndex = GuiSlots.contentIndex54(slotId);
        int index = localIndex < 0 ? -1 : page * CONTENT_SLOTS + localIndex;
        if (index >= 0 && index < entries.size()) {
            PackagePreviewScreenHandler.open(serverPlayer, entries.get(index).instanceId(), page, 0);
            GuiFeedbackService.click(serverPlayer);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void broadcastChanges() {
        if (owner != null && (!ModMindEntry.isModuleEnabled(ModuleId.PACKAGES)
                || !ModMindEntry.hasCommandPermission(owner, CommandAction.PACKAGE_OPEN))) {
            owner.closeContainer();
            return;
        }
        if (owner != null && !pendingEntries().equals(entries)) {
            refreshContents();
        }
        super.broadcastChanges();
    }

    private void refreshContents() {
        entries = pendingEntries();
        pageCount = Math.max(1, (entries.size() + CONTENT_SLOTS - 1) / CONTENT_SLOTS);
        page = Math.max(0, Math.min(page, pageCount - 1));
        GuiTheme.clear(container);
        int first = page * CONTENT_SLOTS;
        int visible = Math.min(CONTENT_SLOTS, entries.size() - first);
        for (int index = 0; index < visible; index++) {
            container.setItem(GuiSlots.contentSlot54(index), displayInstance(entries.get(first + index)));
        }
        container.setItem(GuiSlots.HEADER_LEFT_54, GuiTheme.status(Items.CHEST, Component.literal("我的礼包"),
                ChatFormatting.GOLD, List.of(Component.literal("未开启：" + entries.size())
                        .withStyle(ChatFormatting.GRAY)), false));
        container.setItem(HEADER_TITLE_SLOT, GuiTheme.status(Items.ENDER_CHEST, Component.literal("礼包列表"),
                ChatFormatting.AQUA, List.of(Component.literal("选择礼包查看内容")
                        .withStyle(ChatFormatting.GRAY)), false));
        container.setItem(CLOSE_SLOT, GuiNavigationService.close());
        if (page > 0) {
            container.setItem(PREVIOUS_SLOT, GuiNavigationService.previous());
        }
        container.setItem(INBOX_SLOT, GuiTheme.navigation(Items.HOPPER, Component.literal("奖励箱"),
                Component.literal("查看待投递奖励")));
        container.setItem(PAGE_SLOT, GuiNavigationService.page(page + 1, pageCount, entries.size()));
        if (page + 1 < pageCount) {
            container.setItem(NEXT_SLOT, GuiNavigationService.next());
        }
    }

    private List<PackageInstance> pendingEntries() {
        return PackageData.get(owner.level().getServer()).list(ownerId).stream()
                .filter(instance -> instance.status() != PackageInstance.Status.OPENED)
                .toList();
    }

    private ItemStack displayInstance(PackageInstance instance) {
        Identifier id = Identifier.tryParse(instance.iconId());
        ItemStack icon = new ItemStack(id == null ? Items.CHEST
                : BuiltInRegistries.ITEM.getOptional(id).orElse(Items.CHEST));
        List<Component> lore = new ArrayList<>();
        for (String line : instance.description()) {
            lore.add(TextTemplateRenderer.render(owner, line).copy().withStyle(ChatFormatting.GRAY));
        }
        lore.add(Component.literal(instance.mode() == dev.modmind.omnitools.packages.PackageDefinition.Mode.ALL
                ? "模式：全部获得" : "模式：随机一种").withStyle(ChatFormatting.AQUA));
        if (!instance.skillXpGrants().isEmpty()) {
            lore.add(Component.literal("技能经验：" + instance.skillXpGrants().size() + " 项").withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        if (instance.hasPendingSkillXpChoice()) {
            lore.add(Component.literal("打开后选择技能树").withStyle(ChatFormatting.YELLOW));
        }
        lore.add(Component.literal("状态：" + statusLabel(instance.status())).withStyle(statusColor(instance.status())));
        return GuiStatusItem.create(icon, TextTemplateRenderer.render(owner, instance.displayName()),
                statusStyle(instance.status()), GuiTextService.cardLore(lore,
                        Component.literal("点击预览").withStyle(ChatFormatting.GREEN)));
    }

    static boolean canOpen(PackageInstance instance) {
        return instance != null && (instance.status() == PackageInstance.Status.PENDING
                || instance.status() == PackageInstance.Status.WAITING_INBOX);
    }

    static String statusLabel(PackageInstance.Status status) {
        return switch (status) {
            case PENDING -> "待开启";
            case OPENING, DELIVERING -> "投递中";
            case WAITING_INBOX -> "待投递";
            case OPENED -> "已开启";
            case BLOCKED -> "已阻塞";
        };
    }

    static ChatFormatting statusColor(PackageInstance.Status status) {
        return switch (status) {
            case PENDING -> ChatFormatting.GREEN;
            case OPENING, DELIVERING, WAITING_INBOX -> ChatFormatting.YELLOW;
            case OPENED -> ChatFormatting.DARK_GREEN;
            case BLOCKED -> ChatFormatting.RED;
        };
    }

    static GuiStatusItem.State statusStyle(PackageInstance.Status status) {
        return switch (status) {
            case PENDING -> GuiStatusItem.State.ACTIONABLE;
            case OPENING, DELIVERING -> GuiStatusItem.State.IN_PROGRESS;
            case WAITING_INBOX -> GuiStatusItem.State.PENDING;
            case OPENED -> GuiStatusItem.State.COMPLETED;
            case BLOCKED -> GuiStatusItem.State.BLOCKED;
        };
    }
}
