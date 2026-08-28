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
    public static final int PRODUCT_SLOT_COUNT = GuiSlots.CONTENT_SLOT_COUNT_54;
    public static final int PREVIOUS_PAGE_SLOT = GuiSlots.FIRST_ACTION_SLOT_54;
    public static final int PLAYER_HEAD_SLOT = GuiSlots.HEADER_LEFT_54;
    public static final int HEADER_TITLE_SLOT = GuiSlots.HEADER_CENTER_54;
    public static final int CLOSE_SLOT = GuiSlots.HEADER_CLOSE_54;
    public static final int PAGE_SLOT = GuiSlots.CENTER_54;
    public static final int NEXT_PAGE_SLOT = GuiSlots.LAST_SLOT_54;
    private final SimpleContainer shopContainer;
    private final UUID ownerId;
    private final ServerPlayer owner;
    private ShopConfig config;
    private int page;
    private int pageCount;
    private long displayedBalance = Long.MIN_VALUE;
    private long lastBalanceCheckTick = Long.MIN_VALUE;
    private long lastConfigRevision = Long.MIN_VALUE;

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
        refreshConfigIfChanged();

        if (slotId == CLOSE_SLOT) {
            serverPlayer.closeContainer();
            GuiFeedbackService.click(serverPlayer);
            return;
        }
        if (slotId == PREVIOUS_PAGE_SLOT && page > 0) {
            openPage(serverPlayer, page - 1);
            GuiFeedbackService.click(serverPlayer);
            return;
        }
        if (slotId == NEXT_PAGE_SLOT && page + 1 < pageCount) {
            openPage(serverPlayer, page + 1);
            GuiFeedbackService.click(serverPlayer);
            return;
        }
        int localIndex = GuiSlots.contentIndex54(slotId);
        if (localIndex < 0) {
            return;
        }

        int productIndex = page * PRODUCT_SLOT_COUNT + localIndex;
        ShopConfig.ShopItem product = config.get(productIndex);
        if (product != null) {
            purchase(serverPlayer, productIndex, product);
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
                if (!refreshConfigIfChanged() && balance != displayedBalance) {
                    refreshContents(owner);
                }
            }
        }
        super.broadcastChanges();
    }

    private void purchase(ServerPlayer player, int productIndex, ShopConfig.ShopItem product) {
        if (!ModMindEntry.isModuleEnabled(dev.modmind.omnitools.config.ModuleId.SHOP)) {
            player.closeContainer();
            return;
        }
        if (product.type() == ShopConfig.ProductType.PACKAGE) {
            purchasePackage(player, productIndex, product);
            return;
        }
        CheckinData data = CheckinData.get(player);
        long balance = data.getBalance(player.getUUID());
        if (balance < product.price()) {
            GuiFeedbackService.failure(player);
            player.displayClientMessage(ServerText.translatable(
                    "message.omnitools.shop.insufficient", product.price(), balance), true);
            return;
        }

        long removed = data.removeCurrency(player.getUUID(), product.price(), player.getGameProfile().name());
        if (removed != product.price()) {
            GuiFeedbackService.failure(player);
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
        GuiFeedbackService.success(player);
        player.displayClientMessage(ServerText.translatable("message.omnitools.shop.purchased",
                product.stack().getCount(), product.price(), newBalance), true);
        refreshContents(player);
        broadcastChanges();
    }

    private void purchasePackage(ServerPlayer player, int productIndex, ShopConfig.ShopItem product) {
        ShopPurchaseService.PurchaseResult result = ModMindEntry.shopPurchaseService()
                .purchasePackage(player, productIndex, product);
        if (result.result() == ShopPurchaseService.Result.INSUFFICIENT_CURRENCY) {
            long balance = CheckinData.get(player).getBalance(player.getUUID());
            GuiFeedbackService.failure(player);
            player.displayClientMessage(ServerText.translatable("message.omnitools.shop.insufficient",
                    product.price(), balance), true);
            return;
        }
        if (result.result() == ShopPurchaseService.Result.COMPLETED && result.transaction() != null) {
            long balance = CheckinData.get(player).getBalance(player.getUUID());
            GuiFeedbackService.success(player);
            player.displayClientMessage(ServerText.translatable("message.omnitools.shop.package_purchased",
                    result.transaction().packageSnapshot().displayName(), product.price(), balance), true);
            refreshContents(player);
            broadcastChanges();
            return;
        }
        if (result.result() != ShopPurchaseService.Result.COOLDOWN) {
            GuiFeedbackService.failure(player);
            player.displayClientMessage(ServerText.translatable("message.omnitools.shop.purchase_blocked"), true);
        }
    }

    private void refreshContents(ServerPlayer owner) {
        pageCount = Math.max(1, (config.highestProductIndex() + PRODUCT_SLOT_COUNT) / PRODUCT_SLOT_COUNT);
        page = clampPage(page);
        GuiTheme.clear(shopContainer);

        int firstIndex = page * PRODUCT_SLOT_COUNT;
        for (int index = 0; index < PRODUCT_SLOT_COUNT; index++) {
            ShopConfig.ShopItem product = config.get(firstIndex + index);
            if (product != null) {
                shopContainer.setItem(GuiSlots.contentSlot54(index), displayProduct(owner, product));
            }
        }

        CheckinData data = CheckinData.get(owner);
        long balance = data.getBalance(owner.getUUID());
        ItemStack profile = new ItemStack(Items.PLAYER_HEAD);
        profile.set(DataComponents.PROFILE, ResolvableProfile.createResolved(owner.getGameProfile()));
        profile.set(DataComponents.CUSTOM_NAME, ServerText.translatable("gui.omnitools.shop.balance_title")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        profile.set(DataComponents.LORE, new ItemLore(List.of(
                ServerText.translatable("gui.omnitools.shop.balance", balance).withStyle(ChatFormatting.GOLD),
                ServerText.translatable("gui.omnitools.shop.page", page + 1, pageCount).withStyle(ChatFormatting.GRAY))));
        shopContainer.setItem(PLAYER_HEAD_SLOT, profile);
        shopContainer.setItem(HEADER_TITLE_SLOT, GuiTheme.status(Items.EMERALD,
                ServerText.translatable("gui.omnitools.shop.title"), ChatFormatting.GOLD,
                List.of(ServerText.translatable("gui.omnitools.shop.page", page + 1, pageCount)
                        .withStyle(ChatFormatting.GRAY)), false));
        shopContainer.setItem(CLOSE_SLOT, GuiNavigationService.close());

        if (page > 0) {
            shopContainer.setItem(PREVIOUS_PAGE_SLOT, GuiNavigationService.previous());
        }
        shopContainer.setItem(PAGE_SLOT, GuiNavigationService.page(page + 1, pageCount, config.productCount()));
        if (page + 1 < pageCount) {
            shopContainer.setItem(NEXT_PAGE_SLOT, GuiNavigationService.next());
        }
        displayedBalance = balance;
        lastConfigRevision = ModMindEntry.configSnapshot().revision();
    }

    private ItemStack displayProduct(ServerPlayer player, ShopConfig.ShopItem product) {
        // Rendering only affects this menu copy; purchases keep the exact configured item stack.
        ItemStack display = product.createDisplayStack();
        List<Component> lore = new ArrayList<>();
        if (product.type() == ShopConfig.ProductType.PACKAGE) {
            var definition = ModMindEntry.configSnapshot().packages().definition(product.packageId()).orElse(null);
            if (definition != null) {
                display = new ItemStack(definition.icon());
                display.set(DataComponents.CUSTOM_NAME, TextTemplateRenderer.render(player, definition.display()));
                for (String line : definition.description()) {
                    lore.add(TextTemplateRenderer.render(player, line).copy().withStyle(ChatFormatting.GRAY));
                }
            }
            lore.add(ServerText.translatable("gui.omnitools.shop.package", product.packageId())
                    .withStyle(ChatFormatting.AQUA));
        }
        display = TextTemplateRenderer.renderItemText(player, display);
        boolean affordable = CheckinData.get(player).getBalance(player.getUUID()) >= product.price();
        ItemLore existingLore = display.get(DataComponents.LORE);
        if (existingLore != null) {
            lore.addAll(0, existingLore.lines());
        }
        lore.add(ServerText.translatable("gui.omnitools.shop.price", product.price()).withStyle(ChatFormatting.GOLD));
        if (product.type() == ShopConfig.ProductType.ITEM) {
            lore.add(ServerText.translatable("gui.omnitools.shop.quantity", product.stack().getCount())
                    .withStyle(ChatFormatting.WHITE));
        }
        lore.add(ServerText.translatable(affordable ? "gui.omnitools.shop.affordable"
                : "gui.omnitools.shop.insufficient").withStyle(affordable ? ChatFormatting.GOLD : ChatFormatting.RED));
        return GuiStatusItem.create(display, display.getHoverName(), affordable
                        ? GuiStatusItem.State.ACTIONABLE : GuiStatusItem.State.BLOCKED,
                GuiTextService.cardLore(lore, ServerText.translatable("gui.omnitools.shop.purchase_hint")
                        .withStyle(affordable ? ChatFormatting.GREEN : ChatFormatting.RED)));
    }

    private void openPage(ServerPlayer player, int targetPage) {
        player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                (syncId, inventory, ignored) -> createServer(syncId, inventory, player, config, targetPage),
                ServerText.translatable("gui.omnitools.shop.title")));
    }

    private int clampPage(int candidate) {
        return Math.max(0, Math.min(candidate, pageCount - 1));
    }

    private boolean refreshConfigIfChanged() {
        if (ModMindEntry.configSnapshot().revision() == lastConfigRevision) {
            return false;
        }
        config = ModMindEntry.shopConfig();
        refreshContents(owner);
        return true;
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
