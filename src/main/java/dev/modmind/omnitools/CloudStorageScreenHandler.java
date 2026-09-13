package dev.modmind.omnitools;

import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.diagnostics.ModuleFaultBoundary;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.List;

/**
 * Vanilla menu with 45 normal cloud slots backed by a session mirror and a separate read-only
 * navigation row. Generic click, drag, double-click, number keys and outside-slot drops stay in
 * {@link AbstractContainerMenu}; no cloud write occurs until a checkpoint or close.
 */
public final class CloudStorageScreenHandler extends AbstractContainerMenu {
    public static final int ROWS = 6;
    public static final int CONTAINER_SIZE = ROWS * 9;
    public static final int STORAGE_SLOT_COUNT = CloudStorageData.SLOTS_PER_PAGE;
    public static final int PREVIOUS_PAGE_SLOT = GuiSlots.FIRST_ACTION_SLOT_54;
    public static final int BALANCE_SLOT = 47;
    public static final int STATUS_SLOT = GuiSlots.CENTER_54;
    public static final int UPGRADE_SLOT = 51;
    public static final int NEXT_PAGE_SLOT = 52;
    public static final int CLOSE_SLOT = GuiSlots.LAST_SLOT_54;
    private static final int PLAYER_SLOT_START = CONTAINER_SIZE;

    private final ServerPlayer owner;
    private final CloudStorageConfig config;
    private final CloudStorageSession session;
    private final CloudStorageSessionInventory sessionInventory;
    private final SimpleContainer controls = new SimpleContainer(9);
    private boolean closeHandled;

    public CloudStorageScreenHandler(int syncId, Inventory inventory) {
        this(syncId, inventory, null, CloudStorageConfig.defaultConfig(), null, null);
    }

    private CloudStorageScreenHandler(int syncId, Inventory inventory, ServerPlayer owner, CloudStorageConfig config,
                                      CloudStorageSession session, CloudStorageSessionInventory sessionInventory) {
        super(MenuType.GENERIC_9x6, syncId);
        this.owner = owner;
        this.config = config == null ? CloudStorageConfig.defaultConfig() : config;
        this.session = session;
        this.sessionInventory = sessionInventory;
        addCloudSlots();
        addControlSlots();
        addPlayerSlots(inventory);
        if (owner != null) refreshControls();
    }

    public static CloudStorageScreenHandler createServer(int syncId, Inventory inventory, ServerPlayer owner,
                                                           CloudStorageConfig config, int page) {
        CloudStorageSessionManager.Handle handle = CloudStorageSessionManager.global().open(owner, page);
        return new CloudStorageScreenHandler(syncId, inventory, owner, config, handle.session(), handle.inventory());
    }

    private void addCloudSlots() {
        net.minecraft.world.Container container = sessionInventory == null ? new SimpleContainer(STORAGE_SLOT_COUNT) : sessionInventory;
        for (int row = 0; row < 5; row++) {
            for (int column = 0; column < 9; column++) {
                int index = column + row * 9;
                addSlot(new Slot(container, index, 8 + column * 18, 18 + row * 18) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        return CloudStorageData.canStore(stack);
                    }
                });
            }
        }
    }

    private void addControlSlots() {
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(controls, column, 8 + column * 18, 108) {
                @Override public boolean mayPlace(ItemStack stack) { return false; }
                @Override public boolean mayPickup(Player player) { return false; }
            });
        }
    }

    private void addPlayerSlots(Inventory inventory) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9, 8 + column * 18, 140 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 8 + column * 18, 198));
        }
    }

    @Override
    public boolean stillValid(Player player) {
        // The client builds a menu from the registered vanilla type without a ServerPlayer/session;
        // it must remain locally valid long enough to receive authoritative slot synchronisation.
        if (owner == null) return true;
        return session != null && sessionInventory != null && session.canInteract()
                && player instanceof ServerPlayer serverPlayer && player.getUUID().equals(owner.getUUID())
                && CloudStorageSessionManager.global().owns(serverPlayer, session)
                && ModMindEntry.isModuleEnabled(ModuleId.CLOUD_STORAGE)
                && ModMindEntry.hasCloudStoragePermissionForPlayer(serverPlayer);
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (!stillValid(player)) {
            if (player instanceof ServerPlayer serverPlayer && owner != null
                    && serverPlayer.getUUID().equals(owner.getUUID())) {
                closeHandled = true;
                serverPlayer.closeContainer();
            }
            return;
        }
        if (slotId >= PREVIOUS_PAGE_SLOT && slotId < CONTAINER_SIZE) {
            if (player instanceof ServerPlayer serverPlayer && clickType == ClickType.PICKUP) {
                ModuleFaultBoundary.runPlayerAction(ModuleId.CLOUD_STORAGE, "session_navigation", serverPlayer,
                        "session_retained_or_committed", () -> handleControlClick(serverPlayer, slotId));
            }
            return;
        }
        // Vanilla handles every inventory/cursor gesture, including -999 outside drops and Q.
        super.clicked(slotId, button, clickType, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        if (!(player instanceof ServerPlayer serverPlayer) || !stillValid(serverPlayer)
                || slotIndex < 0 || slotIndex >= slots.size() || slotIndex >= PLAYER_SLOT_START + 36) return ItemStack.EMPTY;
        Slot source = slots.get(slotIndex);
        if (!source.hasItem()) return ItemStack.EMPTY;
        ItemStack original = source.getItem().copy();
        boolean moved = slotIndex < STORAGE_SLOT_COUNT
                ? moveItemStackTo(source.getItem(), PLAYER_SLOT_START, PLAYER_SLOT_START + 36, true)
                : moveItemStackTo(source.getItem(), 0, STORAGE_SLOT_COUNT, false);
        if (!moved) return ItemStack.EMPTY;
        if (source.getItem().isEmpty()) source.set(ItemStack.EMPTY); else source.setChanged();
        return original;
    }

    @Override
    public void removed(Player player) {
        if (!closeHandled && player instanceof ServerPlayer serverPlayer && owner != null
                && serverPlayer.getUUID().equals(owner.getUUID())) {
            closeHandled = true;
            // Vanilla must first resolve the carried cursor stack into the player's inventory or a
            // world drop.  The journal only covers the page mirror, never an in-flight cursor.
            super.removed(player);
            ModuleFaultBoundary.run(ModuleId.CLOUD_STORAGE, "session_close_commit", "session_journal_retained",
                    () -> closeSession(serverPlayer));
            return;
        }
        super.removed(player);
    }

    private void handleControlClick(ServerPlayer player, int slotId) {
        if (!stillValid(player)) return;
        if (slotId == CLOSE_SLOT) {
            player.closeContainer();
            return;
        }
        if (slotId == PREVIOUS_PAGE_SLOT && session.page() > 0) {
            switchPage(player, session.page() - 1);
        } else if (slotId == NEXT_PAGE_SLOT && session.page() + 1 < availablePages()) {
            switchPage(player, session.page() + 1);
        } else if (slotId == UPGRADE_SLOT) {
            unlockNextPage(player);
        }
    }

    private void switchPage(ServerPlayer player, int targetPage) {
        CloudStorageCommitService.Result result = checkpoint(CloudStorageCommitService.Reason.PAGE_SWITCH);
        if (!result.committed()) {
            abortFailedSession(result.reason());
            return;
        }
        closeHandled = true;
        session.close();
        CloudStorageSessionManager.global().release(player, session);
        player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                (syncId, inventory, ignored) -> createServer(syncId, inventory, player, config, targetPage),
                ServerText.translatable("gui.omnitools.storage.title")));
    }

    private void unlockNextPage(ServerPlayer player) {
        CloudStorageCommitService.Result committed = checkpoint(CloudStorageCommitService.Reason.PAGE_SWITCH);
        if (!committed.committed()) {
            abortFailedSession(committed.reason());
            return;
        }
        int unlockedPages = availablePages();
        if (unlockedPages >= config.maxPages()) {
            GuiFeedbackService.failure(player);
            return;
        }
        CheckinData currency = CheckinData.get(player);
        long cost = config.expansionCost();
        if (currency.getBalance(owner.getUUID()) < cost) {
            GuiFeedbackService.failure(player);
            return;
        }
        long removed = currency.removeCurrency(owner.getUUID(), cost, player.getGameProfile().name());
        CloudStorageData.PageUnlockResult result = CloudStorageData.get(player)
                .unlockNextPage(owner.getUUID(), config.maxPages());
        if (!result.unlocked()) {
            currency.addCurrency(owner.getUUID(), removed, player.getGameProfile().name());
            GuiFeedbackService.failure(player);
            return;
        }
        switchPage(player, result.unlockedPages() - 1);
    }

    private void closeSession(ServerPlayer player) {
        if (session == null || sessionInventory == null) return;
        CloudStorageCommitService.Result result = checkpoint(CloudStorageCommitService.Reason.CLOSE);
        if (result.committed()) {
            session.close();
            // Keep the ownership lock through the next menu/bag synchronisation tick only.
            CloudStorageSessionManager.global().releaseAfter(player, session, player.level().getGameTime());
            player.containerMenu.broadcastFullState();
        } else {
            // Retain the exact mirror and its journal evidence. Opening another cloud menu here
            // could otherwise overwrite a page whose durable outcome is not yet provable.
            session.fail();
        }
        if (!result.committed() && !result.reason().isBlank()) notifyCommitFailure(result.reason());
    }

    private CloudStorageCommitService.Result checkpoint(CloudStorageCommitService.Reason reason) {
        if (owner == null || sessionInventory == null) return CloudStorageCommitService.Result.noChanges();
        return CloudStorageCommitService.checkpoint(owner, sessionInventory, reason);
    }

    private int availablePages() {
        return Math.max(CloudStorageConfig.MIN_PAGES,
                Math.min(config.maxPages(), CloudStorageData.get(owner).unlockedPages(owner.getUUID())));
    }

    private void notifyCommitFailure(String reason) {
        System.err.println("[omnitools] Cloud storage session commit failed: session=" + session.sessionId()
                + " player=" + owner.getUUID() + " page=" + (session.page() + 1) + " reason=" + reason);
        GuiFeedbackService.failure(owner);
        owner.displayClientMessage(ServerText.translatable("message.omnitools.storage.save_failed"), true);
    }

    /** Close the vanilla menu but deliberately retain the failed session lock and journal evidence. */
    private void abortFailedSession(String reason) {
        notifyCommitFailure(reason);
        if (session != null && session.state() == CloudStorageSession.State.FAILED) {
            closeHandled = true;
            owner.closeContainer();
        }
    }

    private void refreshControls() {
        for (int index = 0; index < 9; index++) controls.setItem(index, ItemStack.EMPTY);
        int unlocked = availablePages();
        if (session.page() > 0) controls.setItem(0, GuiNavigationService.previous());
        if (session.page() + 1 < unlocked) controls.setItem(7, GuiNavigationService.next());
        controls.setItem(2, namedItem(Items.GOLD_INGOT, ServerText.translatable("gui.omnitools.storage.balance_title")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), List.of(ServerText.translatable(
                "gui.omnitools.storage.balance", CheckinData.get(owner).getBalance(owner.getUUID())))));
        ItemStack status = new ItemStack(Items.PLAYER_HEAD);
        status.set(DataComponents.PROFILE, ResolvableProfile.createResolved(owner.getGameProfile()));
        status.set(DataComponents.CUSTOM_NAME, ServerText.translatable("gui.omnitools.storage.status_title")
                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        status.set(DataComponents.LORE, new ItemLore(List.of(ServerText.translatable("gui.omnitools.storage.page",
                session.page() + 1, unlocked), Component.literal("session=" + session.sessionId()).withStyle(ChatFormatting.DARK_GRAY))));
        controls.setItem(4, status);
        controls.setItem(8, GuiNavigationService.close());
        if (unlocked < config.maxPages()) controls.setItem(6, namedItem(Items.EMERALD,
                ServerText.translatable("gui.omnitools.storage.upgrade").withStyle(ChatFormatting.GREEN),
                List.of(ServerText.translatable("gui.omnitools.storage.upgrade_price", config.expansionCost()))));
    }

    private static ItemStack namedItem(net.minecraft.world.item.Item item, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, name);
        stack.set(DataComponents.LORE, new ItemLore(lore));
        return stack;
    }

    public CloudStorageSession session() { return session; }
}
