package dev.modmind.omnitools;

import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.diagnostics.ModuleFaultBoundary;
import dev.modmind.omnitools.permissions.CommandAction;
import dev.modmind.omnitools.reward.RewardClaimLedger;
import dev.modmind.omnitools.reward.RewardEvent;
import dev.modmind.omnitools.reward.RewardGrantResult;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
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
    private static final int CONTENT_SLOTS = GuiSlots.CONTENT_SLOT_COUNT_54;
    private static final int PREVIOUS_PAGE_SLOT = GuiSlots.FIRST_ACTION_SLOT_54;
    private static final int PAGE_INFO_SLOT = GuiSlots.CENTER_54;
    private static final int NEXT_PAGE_SLOT = GuiSlots.LAST_SLOT_54;
    private static final int HEADER_TITLE_SLOT = GuiSlots.HEADER_CENTER_54;
    private static final int CLOSE_SLOT = GuiSlots.HEADER_CLOSE_54;

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
        if (player instanceof ServerPlayer serverPlayer) {
            ModuleFaultBoundary.runPlayerAction(ModuleId.DAILY_CHECKIN, "reward_inbox_click", serverPlayer,
                    "reward_claim_ledger_retained", () -> handleClick(slotId, button, clickType, player));
            return;
        }
        handleClick(slotId, button, clickType, player);
    }

    private void handleClick(int slotId, int button, ClickType clickType, Player player) {
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
        if (slotId == CLOSE_SLOT) {
            serverPlayer.closeContainer();
            GuiFeedbackService.click(serverPlayer);
            return;
        }
        if (slotId == PREVIOUS_PAGE_SLOT && page > 0) {
            page--;
            refreshContents();
            GuiFeedbackService.click(serverPlayer);
            return;
        }
        if (slotId == NEXT_PAGE_SLOT && page + 1 < pageCount) {
            page++;
            refreshContents();
            GuiFeedbackService.click(serverPlayer);
            return;
        }
        int localIndex = GuiSlots.contentIndex54(slotId);
        if (localIndex >= 0) {
            int index = page * CONTENT_SLOTS + localIndex;
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
            player.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            player.displayClientMessage(ServerText.translatable("message.omnitools.reward.inbox_delivered"), true);
        } else if (result.status() == RewardGrantResult.Status.PENDING) {
            player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 0.6f);
            player.displayClientMessage(ServerText.translatable("message.omnitools.reward.inbox_inventory_full"), true);
        } else {
            player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 0.5f);
            player.displayClientMessage(ServerText.translatable("message.omnitools.reward.inbox_not_retryable"), true);
        }
        refreshContents();
    }

    private void refreshContents() {
        visibleEntries = RewardClaimLedger.get(owner).pendingItemEntries(ownerId);
        pageCount = Math.max(1, (visibleEntries.size() + CONTENT_SLOTS - 1) / CONTENT_SLOTS);
        page = Math.min(page, pageCount - 1);
        GuiTheme.clear(container);
        int first = page * CONTENT_SLOTS;
        int visible = Math.min(CONTENT_SLOTS, visibleEntries.size() - first);
        for (int index = 0; index < visible; index++) {
            container.setItem(GuiSlots.contentSlot54(index), displayStack(visibleEntries.get(first + index)));
        }
        container.setItem(GuiSlots.HEADER_LEFT_54, GuiTheme.status(Items.CHEST,
                ServerText.translatable("gui.omnitools.rewards.inbox.title"), ChatFormatting.GOLD,
                List.of(ServerText.translatable("gui.omnitools.rewards.inbox_page",
                        page + 1, pageCount, visibleEntries.size()).withStyle(ChatFormatting.GRAY)), false));
        container.setItem(HEADER_TITLE_SLOT, GuiTheme.status(Items.HOPPER,
                ServerText.translatable("gui.omnitools.rewards.inbox.title"), ChatFormatting.AQUA,
                List.of(ServerText.translatable("gui.omnitools.rewards.inbox.click_deliver")
                        .withStyle(ChatFormatting.GRAY)), false));
        container.setItem(CLOSE_SLOT, GuiNavigationService.close());
        if (page > 0) {
            container.setItem(PREVIOUS_PAGE_SLOT, GuiNavigationService.previous());
        }
        container.setItem(PAGE_INFO_SLOT, GuiNavigationService.page(page + 1, pageCount, visibleEntries.size()));
        if (page + 1 < pageCount) {
            container.setItem(NEXT_PAGE_SLOT, GuiNavigationService.next());
        }
    }

    private ItemStack displayStack(RewardClaimLedger.LedgerEntry entry) {
        ItemStack stack = RewardClaimLedger.itemForDisplay(entry.entry(), owner.level().registryAccess());
        if (stack.isEmpty()) {
            stack = new ItemStack(Items.BARRIER);
        }
        ItemLore existingLore = stack.get(DataComponents.LORE);
        List<Component> lore = new ArrayList<>(existingLore == null ? List.of() : existingLore.lines());
        lore.add(ServerText.translatable(sourceKey(entry.eventId())).withStyle(ChatFormatting.GRAY));
        if (!entry.entry().reason().isBlank()) {
            lore.add(ServerText.translatable("gui.omnitools.rewards.reason", entry.entry().reason())
                    .withStyle(ChatFormatting.GOLD));
        }
        return GuiStatusItem.create(stack, stack.getHoverName(), GuiStatusItem.State.ACTIONABLE,
                GuiTextService.cardLore(lore, ServerText.translatable("gui.omnitools.rewards.inbox.click_deliver")
                        .withStyle(ChatFormatting.GREEN)));
    }

    private static String sourceKey(String eventId) {
        if (eventId.startsWith("checkin:")) {
            return "gui.omnitools.reward.inbox.source.checkin";
        }
        if (eventId.startsWith("achievement:")) {
            return "gui.omnitools.reward.inbox.source.achievement";
        }
        if (eventId.startsWith("online:")) {
            return "gui.omnitools.reward.inbox.source.online";
        }
        return "gui.omnitools.reward.inbox.source.other";
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
