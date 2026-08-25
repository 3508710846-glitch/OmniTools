package dev.modmind.omnitools;

import dev.modmind.omnitools.text.TextTemplateRenderer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A six-row, server-authoritative menu for spending omnitools currency on configured items. */
public final class ShopScreenHandler extends ChestMenu {
    public static final int ROWS = 6;
    public static final int CONTAINER_SIZE = ROWS * 9;
    public static final int PRODUCT_SLOT_COUNT = ShopConfig.PRODUCTS_PER_PAGE;
    public static final int PREVIOUS_PAGE_SLOT = 45;
    public static final int PLAYER_HEAD_SLOT = 49;
    public static final int NEXT_PAGE_SLOT = 53;
    private final SimpleContainer shopContainer;
    private final UUID ownerId;
    private final ServerPlayer owner;
    private final ShopConfig config;
    private int page;
    private int pageCount;
    private long displayedBalance = Long.MIN_VALUE;
    private long lastBalanceCheckTick = Long.MIN_VALUE;

    public ShopScreenHandler(int syncId, Inventory inventory) {
        this(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), null, ShopConfig.empty(), 0);
    }

    private ShopScreenHandler(int syncId, Inventory inventory, SimpleContainer container, ServerPlayer owner,
                              ShopConfig config, int page) {
        super(MenuType.GENERIC_9x6, syncId, inventory, container, ROWS);
        this.shopContainer = container;
        this.ownerId = owner == null ? null : owner.getUUID();
        this.owner = owner;
        this.config = config;
        this.pageCount = config.pageCount();
        this.page = clampPage(page);
        if (owner != null) {
            refreshContents(owner);
        }
    }

    public static ShopScreenHandler createServer(int syncId, Inventory inventory, ServerPlayer owner,
                                                  ShopConfig config, int page) {
        return new ShopScreenHandler(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), owner, config, page);
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (!ModMindEntry.isModuleEnabled(dev.modmind.omnitools.config.ModuleId.SHOP)
                || (player instanceof ServerPlayer serverPlayerForPermission
                && !ModMindEntry.hasCommandPermission(serverPlayerForPermission,
                dev.modmind.omnitools.permissions.CommandAction.SHOP_OPEN))) {
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.closeContainer();
            }
            return;
        }
        if (slotId < 0 || slotId >= CONTAINER_SIZE) {
            super.clicked(slotId, button, clickType, player);
            return;
        }
        if (!(player instanceof ServerPlayer serverPlayer) || ownerId == null
                || !ownerId.equals(serverPlayer.getUUID()) || clickType != ClickType.PICKUP) {
            return;
        }

        if (slotId == PREVIOUS_PAGE_SLOT && page > 0) {
            openPage(serverPlayer, page - 1);
            return;
        }
        if (slotId == NEXT_PAGE_SLOT && page + 1 < pageCount) {
            openPage(serverPlayer, page + 1);
            return;
        }
        if (slotId >= PRODUCT_SLOT_COUNT) {
            return;
        }

        ShopConfig.ShopItem product = config.get(page * PRODUCT_SLOT_COUNT + slotId);
        if (product != null) {
            purchase(serverPlayer, product);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void broadcastChanges() {
        if (owner != null) {
            long tick = owner.level().getServer().getTickCount();
            if (lastBalanceCheckTick == Long.MIN_VALUE || tick - lastBalanceCheckTick >= 10L) {
                lastBalanceCheckTick = tick;
                long balance = CheckinData.get(owner).getBalance(ownerId);
                if (balance != displayedBalance) {
                    refreshContents(owner);
                }
            }
        }
        super.broadcastChanges();
    }

    private void purchase(ServerPlayer player, ShopConfig.ShopItem product) {
        if (!ModMindEntry.isModuleEnabled(dev.modmind.omnitools.config.ModuleId.SHOP)) {
            player.closeContainer();
            return;
        }
        CheckinData data = CheckinData.get(player);
        long balance = data.getBalance(player.getUUID());
        if (balance < product.price()) {
            player.displayClientMessage(ServerText.translatable(
                    "message.omnitools.shop.insufficient", product.price(), balance), true);
            return;
        }

        long removed = data.removeCurrency(player.getUUID(), product.price(), player.getGameProfile().name());
        if (removed != product.price()) {
            player.displayClientMessage(ServerText.translatable("message.omnitools.shop.insufficient",
                    product.price(), data.getBalance(player.getUUID())), true);
            return;
        }

        ItemStack purchasedStack = product.createStack();
        player.getInventory().add(purchasedStack);
        if (!purchasedStack.isEmpty()) {
            player.drop(purchasedStack, false);
        }
        long newBalance = data.getBalance(player.getUUID());
        player.displayClientMessage(ServerText.translatable("message.omnitools.shop.purchased",
                product.stack().getCount(), product.price(), newBalance), true);
        refreshContents(player);
        broadcastChanges();
    }

    private void refreshContents(ServerPlayer owner) {
        pageCount = config.pageCount();
        page = clampPage(page);
        for (int slot = 0; slot < CONTAINER_SIZE; slot++) {
            shopContainer.setItem(slot, filler());
        }

        int firstIndex = page * PRODUCT_SLOT_COUNT;
        for (int slot = 0; slot < PRODUCT_SLOT_COUNT; slot++) {
            ShopConfig.ShopItem product = config.get(firstIndex + slot);
            if (product != null) {
                shopContainer.setItem(slot, displayProduct(owner, product));
            }
        }

        if (page > 0) {
            shopContainer.setItem(PREVIOUS_PAGE_SLOT, namedItem(Items.ARROW,
                    ServerText.translatable("gui.omnitools.shop.previous").withStyle(ChatFormatting.AQUA),
                    List.of(ServerText.translatable("gui.omnitools.shop.previous_hint").withStyle(ChatFormatting.GRAY))));
        }
        if (page + 1 < pageCount) {
            shopContainer.setItem(NEXT_PAGE_SLOT, namedItem(Items.ARROW,
                    ServerText.translatable("gui.omnitools.shop.next").withStyle(ChatFormatting.AQUA),
                    List.of(ServerText.translatable("gui.omnitools.shop.next_hint").withStyle(ChatFormatting.GRAY))));
        }

        CheckinData data = CheckinData.get(owner);
        long balance = data.getBalance(owner.getUUID());
        ItemStack profile = new ItemStack(Items.PLAYER_HEAD);
        profile.set(DataComponents.PROFILE, ResolvableProfile.createResolved(owner.getGameProfile()));
        profile.set(DataComponents.CUSTOM_NAME, ServerText.translatable("gui.omnitools.shop.balance_title")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        profile.set(DataComponents.LORE, new ItemLore(List.of(
                ServerText.translatable("gui.omnitools.shop.balance", balance)
                        .withStyle(ChatFormatting.GOLD),
                ServerText.translatable("gui.omnitools.shop.page", page + 1, pageCount)
                        .withStyle(ChatFormatting.GRAY))));
        shopContainer.setItem(PLAYER_HEAD_SLOT, profile);
        displayedBalance = balance;
    }

    private ItemStack displayProduct(ServerPlayer player, ShopConfig.ShopItem product) {
        // Rendering only affects this menu copy; purchases keep the exact configured item stack.
        ItemStack display = TextTemplateRenderer.renderItemText(player, product.createStack());
        ItemLore existingLore = display.get(DataComponents.LORE);
        List<Component> lore = new ArrayList<>(existingLore == null ? List.of() : existingLore.lines());
        if (lore.size() >= ItemLore.MAX_LINES) {
            lore = new ArrayList<>(lore.subList(0, ItemLore.MAX_LINES - 1));
        }
        lore.add(ServerText.translatable("gui.omnitools.shop.price", product.price()).withStyle(ChatFormatting.GOLD));
        display.set(DataComponents.LORE, new ItemLore(lore));
        return display;
    }

    private void openPage(ServerPlayer player, int targetPage) {
        player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                (syncId, inventory, ignored) -> createServer(syncId, inventory, player, config, targetPage),
                ServerText.translatable("gui.omnitools.shop.title")));
    }

    private int clampPage(int candidate) {
        return Math.max(0, Math.min(candidate, pageCount - 1));
    }

    private static ItemStack filler() {
        return namedItem(Items.GRAY_STAINED_GLASS_PANE, ServerText.translatable("gui.omnitools.empty"), List.of());
    }

    private static ItemStack namedItem(net.minecraft.world.item.Item item, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, name);
        if (!lore.isEmpty()) {
            stack.set(DataComponents.LORE, new ItemLore(lore));
        }
        return stack;
    }
}
