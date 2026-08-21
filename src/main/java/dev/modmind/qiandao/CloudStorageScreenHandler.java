package dev.modmind.qiandao;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.flag.FeatureFlags;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A two-page, server-authoritative player inventory stored in world SavedData. */
public final class CloudStorageScreenHandler extends ChestMenu {
    public static final int ROWS = 6;
    public static final int CONTAINER_SIZE = ROWS * 9;
    public static final int STORAGE_SLOT_COUNT = CloudStorageData.SLOTS_PER_PAGE;
    public static final int PREVIOUS_PAGE_SLOT = 45;
    public static final int BALANCE_SLOT = 47;
    public static final int STATUS_SLOT = 49;
    public static final int UPGRADE_SLOT = 51;
    public static final int NEXT_PAGE_SLOT = 53;
    public static final MenuType<CloudStorageScreenHandler> TYPE = Registry.register(
            BuiltInRegistries.MENU,
            Identifier.fromNamespaceAndPath(ModMindEntry.MOD_ID, "cloud_storage"),
            new MenuType<>(CloudStorageScreenHandler::new, FeatureFlags.DEFAULT_FLAGS));

    private final SimpleContainer storageContainer;
    private final UUID ownerId;
    private final ServerPlayer owner;
    private final CloudStorageConfig config;
    private int page;
    private long displayedBalance = Long.MIN_VALUE;
    private int displayedUnlockedPages = Integer.MIN_VALUE;

    public static void register() {
        // Loading this class registers TYPE before the client creates its screen.
    }

    public CloudStorageScreenHandler(int syncId, Inventory inventory) {
        this(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), null, null, 0);
    }

    private CloudStorageScreenHandler(int syncId, Inventory inventory, SimpleContainer container,
                                      ServerPlayer owner, CloudStorageConfig config, int page) {
        super(TYPE, syncId, inventory, container, ROWS);
        this.storageContainer = container;
        this.owner = owner;
        this.ownerId = owner == null ? null : owner.getUUID();
        this.config = config;
        this.page = page;
        if (owner != null && config != null) {
            this.page = clampPage(page);
            loadStoragePage();
            refreshControls();
        }
    }

    public static CloudStorageScreenHandler createServer(int syncId, Inventory inventory, ServerPlayer owner,
                                                           CloudStorageConfig config, int page) {
        return new CloudStorageScreenHandler(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), owner, config,
                page);
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer) || ownerId == null
                || !ownerId.equals(serverPlayer.getUUID())) {
            return;
        }

        // A double-click could otherwise collect the decorative action-row items from a player inventory slot.
        if (clickType == ClickType.PICKUP_ALL) {
            return;
        }
        if (slotId >= PREVIOUS_PAGE_SLOT && slotId < CONTAINER_SIZE) {
            if (clickType == ClickType.PICKUP) {
                handleActionClick(serverPlayer, slotId);
            }
            return;
        }
        if (slotId < 0 || slotId >= CONTAINER_SIZE) {
            super.clicked(slotId, button, clickType, player);
            return;
        }

        super.clicked(slotId, button, clickType, player);
        saveStoragePage();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        if (!(player instanceof ServerPlayer serverPlayer) || ownerId == null
                || !ownerId.equals(serverPlayer.getUUID()) || slotIndex < 0 || slotIndex >= slots.size()) {
            return ItemStack.EMPTY;
        }
        if (slotIndex >= PREVIOUS_PAGE_SLOT && slotIndex < CONTAINER_SIZE) {
            return ItemStack.EMPTY;
        }

        Slot sourceSlot = slots.get(slotIndex);
        if (!sourceSlot.hasItem()) {
            return ItemStack.EMPTY;
        }

        ItemStack sourceStack = sourceSlot.getItem();
        ItemStack originalStack = sourceStack.copy();
        boolean moved;
        if (slotIndex < STORAGE_SLOT_COUNT) {
            moved = moveItemStackTo(sourceStack, CONTAINER_SIZE, slots.size(), true);
        } else {
            moved = moveItemStackTo(sourceStack, 0, STORAGE_SLOT_COUNT, false);
        }
        if (!moved) {
            return ItemStack.EMPTY;
        }

        if (sourceStack.isEmpty()) {
            sourceSlot.set(ItemStack.EMPTY);
        } else {
            sourceSlot.setChanged();
        }
        saveStoragePage();
        return originalStack;
    }

    @Override
    public void broadcastChanges() {
        if (owner != null && config != null) {
            long balance = CheckinData.get(owner).getBalance(ownerId);
            int unlockedPages = availablePages();
            if (balance != displayedBalance || unlockedPages != displayedUnlockedPages) {
                refreshControls();
            }
        }
        super.broadcastChanges();
    }

    @Override
    public void removed(Player player) {
        saveStoragePage();
        super.removed(player);
    }

    private void handleActionClick(ServerPlayer player, int slotId) {
        if (slotId == PREVIOUS_PAGE_SLOT && page > 0) {
            openPage(player, page - 1);
            return;
        }
        if (slotId == NEXT_PAGE_SLOT && page + 1 < availablePages()) {
            openPage(player, page + 1);
            return;
        }
        if (slotId == UPGRADE_SLOT) {
            unlockNextPage(player);
        }
    }

    private void unlockNextPage(ServerPlayer player) {
        int unlockedPages = availablePages();
        if (unlockedPages >= config.maxPages()) {
            player.displayClientMessage(Component.translatable("message.qiandao.storage.maxed"), true);
            refreshControls();
            return;
        }

        CheckinData currency = CheckinData.get(player);
        long cost = config.expansionCost();
        long balance = currency.getBalance(ownerId);
        if (balance < cost) {
            player.displayClientMessage(Component.translatable("message.qiandao.storage.insufficient", cost, balance),
                    true);
            return;
        }

        long removed = currency.removeCurrency(ownerId, cost, player.getGameProfile().name());
        if (removed != cost) {
            player.displayClientMessage(Component.translatable("message.qiandao.storage.insufficient", cost,
                    currency.getBalance(ownerId)), true);
            return;
        }

        CloudStorageData.PageUnlockResult result = CloudStorageData.get(player)
                .unlockNextPage(ownerId, config.maxPages());
        if (!result.unlocked()) {
            currency.addCurrency(ownerId, removed, player.getGameProfile().name());
            player.displayClientMessage(Component.translatable("message.qiandao.storage.maxed"), true);
            refreshControls();
            return;
        }

        long remainingBalance = currency.getBalance(ownerId);
        player.displayClientMessage(Component.translatable("message.qiandao.storage.expanded", result.unlockedPages(),
                cost, remainingBalance), true);
        openPage(player, result.unlockedPages() - 1);
    }

    private void openPage(ServerPlayer player, int targetPage) {
        saveStoragePage();
        player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                (syncId, inventory, ignored) -> createServer(syncId, inventory, player, config, targetPage),
                Component.translatable("gui.qiandao.storage.title")));
    }

    private void loadStoragePage() {
        List<ItemStack> items = CloudStorageData.get(owner).page(ownerId, page);
        for (int slot = 0; slot < STORAGE_SLOT_COUNT; slot++) {
            storageContainer.setItem(slot, items.get(slot));
        }
    }

    private void saveStoragePage() {
        if (owner == null || ownerId == null) {
            return;
        }
        List<ItemStack> items = new ArrayList<>(STORAGE_SLOT_COUNT);
        for (int slot = 0; slot < STORAGE_SLOT_COUNT; slot++) {
            items.add(storageContainer.getItem(slot));
        }
        CloudStorageData.get(owner).savePage(ownerId, page, items);
    }

    private void refreshControls() {
        if (owner == null || config == null) {
            return;
        }
        for (int slot = PREVIOUS_PAGE_SLOT; slot < CONTAINER_SIZE; slot++) {
            storageContainer.setItem(slot, filler());
        }

        int unlockedPages = availablePages();
        long balance = CheckinData.get(owner).getBalance(ownerId);
        if (page > 0) {
            storageContainer.setItem(PREVIOUS_PAGE_SLOT, namedItem(Items.ARROW,
                    Component.translatable("gui.qiandao.storage.previous").withStyle(ChatFormatting.AQUA),
                    List.of(Component.translatable("gui.qiandao.storage.previous_hint").withStyle(ChatFormatting.GRAY))));
        }
        if (page + 1 < unlockedPages) {
            storageContainer.setItem(NEXT_PAGE_SLOT, namedItem(Items.ARROW,
                    Component.translatable("gui.qiandao.storage.next").withStyle(ChatFormatting.AQUA),
                    List.of(Component.translatable("gui.qiandao.storage.next_hint").withStyle(ChatFormatting.GRAY))));
        }

        storageContainer.setItem(BALANCE_SLOT, namedItem(Items.GOLD_INGOT,
                Component.translatable("gui.qiandao.storage.balance_title").withStyle(ChatFormatting.GOLD,
                        ChatFormatting.BOLD),
                List.of(Component.translatable("gui.qiandao.storage.balance", balance).withStyle(ChatFormatting.GOLD))));

        ItemStack status = new ItemStack(Items.PLAYER_HEAD);
        status.set(DataComponents.PROFILE, ResolvableProfile.createResolved(owner.getGameProfile()));
        status.set(DataComponents.CUSTOM_NAME, Component.translatable("gui.qiandao.storage.status_title")
                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        status.set(DataComponents.LORE, new ItemLore(List.of(
                Component.translatable("gui.qiandao.storage.page", page + 1, unlockedPages)
                        .withStyle(ChatFormatting.AQUA),
                Component.translatable("gui.qiandao.storage.capacity", unlockedPages * STORAGE_SLOT_COUNT)
                        .withStyle(ChatFormatting.GRAY))));
        storageContainer.setItem(STATUS_SLOT, status);

        if (unlockedPages < config.maxPages()) {
            storageContainer.setItem(UPGRADE_SLOT, namedItem(Items.EMERALD,
                    Component.translatable("gui.qiandao.storage.upgrade").withStyle(ChatFormatting.GREEN,
                            ChatFormatting.BOLD),
                    List.of(
                            Component.translatable("gui.qiandao.storage.upgrade_price", config.expansionCost())
                                    .withStyle(ChatFormatting.GOLD),
                            Component.translatable("gui.qiandao.storage.upgrade_balance", balance)
                                    .withStyle(ChatFormatting.GRAY),
                            Component.translatable("gui.qiandao.storage.upgrade_hint").withStyle(ChatFormatting.GRAY))));
        } else {
            storageContainer.setItem(UPGRADE_SLOT, namedItem(Items.LIME_STAINED_GLASS_PANE,
                    Component.translatable("gui.qiandao.storage.maxed").withStyle(ChatFormatting.GREEN),
                    List.of(Component.translatable("gui.qiandao.storage.maxed_hint").withStyle(ChatFormatting.GRAY))));
        }
        displayedBalance = balance;
        displayedUnlockedPages = unlockedPages;
    }

    private int availablePages() {
        if (owner == null || config == null) {
            return CloudStorageConfig.MIN_PAGES;
        }
        return Math.max(CloudStorageConfig.MIN_PAGES,
                Math.min(config.maxPages(), CloudStorageData.get(owner).unlockedPages(ownerId)));
    }

    private int clampPage(int candidate) {
        return Math.max(0, Math.min(candidate, availablePages() - 1));
    }

    private static ItemStack filler() {
        return namedItem(Items.GRAY_STAINED_GLASS_PANE, Component.translatable("gui.qiandao.empty"), List.of());
    }

    private static ItemStack namedItem(Item item, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, name);
        if (!lore.isEmpty()) {
            stack.set(DataComponents.LORE, new ItemLore(lore));
        }
        return stack;
    }
}
