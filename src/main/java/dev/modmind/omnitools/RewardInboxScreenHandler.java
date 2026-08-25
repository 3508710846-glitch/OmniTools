package dev.modmind.omnitools;

import dev.modmind.omnitools.permissions.CommandAction;
import dev.modmind.omnitools.reward.RewardClaimLedger;
import dev.modmind.omnitools.reward.RewardEvent;
import dev.modmind.omnitools.reward.RewardGrantResult;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A player-owned, server-side inbox for safely retryable persisted item rewards. */
public final class RewardInboxScreenHandler extends ChestMenu {
    public static final int ROWS = 6;
    public static final int CONTAINER_SIZE = 54;
    private static final int CONTENT_SLOTS = 45;
    private static final int PREVIOUS_PAGE_SLOT = 45;
    private static final int PAGE_INFO_SLOT = 49;
    private static final int NEXT_PAGE_SLOT = 53;

    private final SimpleContainer container;
    private final ServerPlayer owner;
    private final UUID ownerId;
    private List<RewardClaimLedger.LedgerEntry> visibleEntries = List.of();
    private int page;
    private int pageCount = 1;

    public RewardInboxScreenHandler(int syncId, Inventory inventory) {
        this(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), null, 0);
    }

    private RewardInboxScreenHandler(int syncId, Inventory inventory, SimpleContainer container,
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

    public static RewardInboxScreenHandler createServer(int syncId, Inventory inventory,
                                                         ServerPlayer owner, int page) {
        return new RewardInboxScreenHandler(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), owner, page);
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer) || ownerId == null
                || !ownerId.equals(serverPlayer.getUUID()) || clickType != ClickType.PICKUP) {
            return;
        }
        if (!ModMindEntry.hasCommandPermission(serverPlayer, CommandAction.REWARDS_RETRY)) {
            serverPlayer.closeContainer();
            return;
        }
        if (slotId < 0 || slotId >= CONTAINER_SIZE) {
            super.clicked(slotId, button, clickType, player);
            return;
        }
        if (slotId == PREVIOUS_PAGE_SLOT && page > 0) {
            page--;
            refreshContents();
            return;
        }
        if (slotId == NEXT_PAGE_SLOT && page + 1 < pageCount) {
            page++;
            refreshContents();
            return;
        }
        if (slotId < CONTENT_SLOTS) {
            int index = page * CONTENT_SLOTS + slotId;
            if (index < visibleEntries.size()) {
                retryDelivery(serverPlayer, visibleEntries.get(index));
            }
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void broadcastChanges() {
        if (owner != null && !ModMindEntry.hasCommandPermission(owner, CommandAction.REWARDS_RETRY)) {
            owner.closeContainer();
            return;
        }
        super.broadcastChanges();
    }

    public void refreshAfterRewardRetry() {
        if (owner != null) {
            refreshContents();
            broadcastChanges();
        }
    }

    private void retryDelivery(ServerPlayer player, RewardClaimLedger.LedgerEntry entry) {
        if (entry.playerId() == null) {
            player.displayClientMessage(ServerText.translatable("message.omnitools.reward.inbox_invalid"), true);
            return;
        }
        RewardGrantResult result = ModMindEntry.rewardGrantService().retryQueuedItem(player,
                new RewardEvent(entry.eventId(), entry.playerId()), entry.rewardId());
        if (result.complete()) {
            ModMindEntry.finalizeRewardInboxDelivery(player, entry.eventId());
            player.displayClientMessage(ServerText.translatable("message.omnitools.reward.inbox_delivered"), true);
        } else if (result.status() == RewardGrantResult.Status.PENDING) {
            player.displayClientMessage(ServerText.translatable("message.omnitools.reward.inbox_inventory_full"), true);
        } else {
            player.displayClientMessage(ServerText.translatable("message.omnitools.reward.inbox_not_retryable"), true);
        }
        refreshContents();
    }

    private void refreshContents() {
        visibleEntries = RewardClaimLedger.get(owner).pendingItemEntries(ownerId);
        pageCount = Math.max(1, (visibleEntries.size() + CONTENT_SLOTS - 1) / CONTENT_SLOTS);
        page = Math.min(page, pageCount - 1);
        for (int slot = 0; slot < CONTAINER_SIZE; slot++) {
            container.setItem(slot, background());
        }
        int first = page * CONTENT_SLOTS;
        int visible = Math.min(CONTENT_SLOTS, visibleEntries.size() - first);
        for (int index = 0; index < visible; index++) {
            container.setItem(index, displayStack(visibleEntries.get(first + index)));
        }
        if (page > 0) {
            container.setItem(PREVIOUS_PAGE_SLOT, namedItem(Items.ARROW,
                    ServerText.translatable("gui.omnitools.rewards.previous"), List.of()));
        }
        container.setItem(PAGE_INFO_SLOT, namedItem(Items.PAPER,
                ServerText.translatable("gui.omnitools.rewards.inbox_page", page + 1, pageCount, visibleEntries.size()),
                List.of()));
        if (page + 1 < pageCount) {
            container.setItem(NEXT_PAGE_SLOT, namedItem(Items.ARROW,
                    ServerText.translatable("gui.omnitools.rewards.next"), List.of()));
        }
    }

    private static ItemStack displayStack(RewardClaimLedger.LedgerEntry entry) {
        ItemStack stack = RewardClaimLedger.itemForDisplay(entry.entry());
        if (stack.isEmpty()) {
            stack = new ItemStack(Items.BARRIER);
        }
        List<Component> lore = new ArrayList<>();
        lore.add(ServerText.translatable("gui.omnitools.rewards.inbox.click_deliver").withStyle(ChatFormatting.YELLOW));
        lore.add(ServerText.translatable("gui.omnitools.rewards.event", entry.eventId()).withStyle(ChatFormatting.DARK_GRAY));
        lore.add(ServerText.translatable("gui.omnitools.rewards.reward_id", entry.rewardId()).withStyle(ChatFormatting.DARK_GRAY));
        if (!entry.entry().reason().isBlank()) {
            lore.add(ServerText.translatable("gui.omnitools.rewards.reason", entry.entry().reason())
                    .withStyle(ChatFormatting.GOLD));
        }
        stack.set(DataComponents.LORE, new ItemLore(lore));
        return stack;
    }

    private static ItemStack background() {
        return new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
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
