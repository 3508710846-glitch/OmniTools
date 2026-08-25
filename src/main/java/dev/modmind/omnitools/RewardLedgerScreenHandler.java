package dev.modmind.omnitools;

import dev.modmind.omnitools.permissions.CommandAction;
import dev.modmind.omnitools.reward.RewardClaimLedger;
import dev.modmind.omnitools.reward.RewardEvent;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Server-side administrator view. Resolution changes only the ledger; it never replays an effect. */
public final class RewardLedgerScreenHandler extends ChestMenu {
    public static final int ROWS = 6;
    public static final int CONTAINER_SIZE = 54;
    private static final int CONTENT_SLOTS = 45;
    private static final int ALL_SLOT = 45;
    private static final int PENDING_SLOT = 46;
    private static final int APPLYING_SLOT = 47;
    private static final int BLOCKED_SLOT = 48;
    private static final int FAILED_SLOT = 49;
    private static final int GRANTED_SLOT = 50;
    private static final int PREVIOUS_PAGE_SLOT = 51;
    private static final int PAGE_INFO_SLOT = 52;
    private static final int NEXT_PAGE_SLOT = 53;

    private final SimpleContainer container;
    private final ServerPlayer owner;
    private final UUID ownerId;
    private List<RewardClaimLedger.LedgerEntry> filteredEntries = List.of();
    private RewardClaimLedger.EntryStatus filter;
    private int page;
    private int pageCount = 1;

    public RewardLedgerScreenHandler(int syncId, Inventory inventory) {
        this(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), null, null, 0);
    }

    private RewardLedgerScreenHandler(int syncId, Inventory inventory, SimpleContainer container, ServerPlayer owner,
                                      RewardClaimLedger.EntryStatus filter, int page) {
        super(MenuType.GENERIC_9x6, syncId, inventory, container, ROWS);
        this.container = container;
        this.owner = owner;
        this.ownerId = owner == null ? null : owner.getUUID();
        this.filter = filter;
        this.page = Math.max(0, page);
        if (owner != null) {
            refreshContents();
        }
    }

    public static RewardLedgerScreenHandler createServer(int syncId, Inventory inventory, ServerPlayer owner) {
        return new RewardLedgerScreenHandler(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), owner, null, 0);
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer) || ownerId == null
                || !ownerId.equals(serverPlayer.getUUID()) || clickType != ClickType.PICKUP) {
            return;
        }
        if (!ModMindEntry.hasCommandPermission(serverPlayer, CommandAction.REWARDS_ADMIN)) {
            serverPlayer.closeContainer();
            return;
        }
        if (slotId < 0 || slotId >= CONTAINER_SIZE) {
            super.clicked(slotId, button, clickType, player);
            return;
        }
        if (applyFilter(slotId)) {
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
            if (index < filteredEntries.size()) {
                resolve(serverPlayer, filteredEntries.get(index), button == 0
                        ? RewardClaimLedger.EntryStatus.GRANTED
                        : button == 1 ? RewardClaimLedger.EntryStatus.FAILED : null);
            }
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void broadcastChanges() {
        if (owner != null && !ModMindEntry.hasCommandPermission(owner, CommandAction.REWARDS_ADMIN)) {
            owner.closeContainer();
            return;
        }
        super.broadcastChanges();
    }

    private boolean applyFilter(int slotId) {
        RewardClaimLedger.EntryStatus requested = switch (slotId) {
            case ALL_SLOT -> null;
            case PENDING_SLOT -> RewardClaimLedger.EntryStatus.PENDING;
            case APPLYING_SLOT -> RewardClaimLedger.EntryStatus.APPLYING;
            case BLOCKED_SLOT -> RewardClaimLedger.EntryStatus.BLOCKED;
            case FAILED_SLOT -> RewardClaimLedger.EntryStatus.FAILED;
            case GRANTED_SLOT -> RewardClaimLedger.EntryStatus.GRANTED;
            default -> null;
        };
        if (slotId < ALL_SLOT || slotId > GRANTED_SLOT) {
            return false;
        }
        filter = requested;
        page = 0;
        refreshContents();
        return true;
    }

    private void resolve(ServerPlayer operator, RewardClaimLedger.LedgerEntry entry,
                         RewardClaimLedger.EntryStatus target) {
        if (target == null || entry.playerId() == null) {
            return;
        }
        RewardClaimLedger.ResolutionResult result = RewardClaimLedger.get(operator.level().getServer())
                .resolveEntry(new RewardEvent(entry.eventId(), entry.playerId()), entry.rewardId(), target,
                        operator.getGameProfile().name());
        if (result.resolved()) {
            if (target == RewardClaimLedger.EntryStatus.GRANTED) {
                ModMindEntry.finalizeResolvedLedgerEntry(operator.level().getServer(), entry);
            }
            operator.displayClientMessage(ServerText.translatable(
                    target == RewardClaimLedger.EntryStatus.GRANTED
                            ? "message.omnitools.reward.admin_marked_granted"
                            : "message.omnitools.reward.admin_marked_failed"), true);
        } else {
            operator.displayClientMessage(ServerText.translatable("message.omnitools.reward.admin_resolution_rejected"),
                    true);
        }
        refreshContents();
    }

    private void refreshContents() {
        filteredEntries = RewardClaimLedger.get(owner).allEntries().stream()
                .filter(entry -> filter == null || entry.entry().status() == filter)
                .sorted(Comparator.comparing(RewardClaimLedger.LedgerEntry::eventId)
                        .thenComparing(RewardClaimLedger.LedgerEntry::rewardId))
                .toList();
        pageCount = Math.max(1, (filteredEntries.size() + CONTENT_SLOTS - 1) / CONTENT_SLOTS);
        page = Math.min(page, pageCount - 1);
        for (int slot = 0; slot < CONTAINER_SIZE; slot++) {
            container.setItem(slot, background());
        }
        int first = page * CONTENT_SLOTS;
        int visible = Math.min(CONTENT_SLOTS, filteredEntries.size() - first);
        for (int index = 0; index < visible; index++) {
            container.setItem(index, displayStack(filteredEntries.get(first + index)));
        }
        filterButton(ALL_SLOT, null, Items.COMPASS);
        filterButton(PENDING_SLOT, RewardClaimLedger.EntryStatus.PENDING, Items.CLOCK);
        filterButton(APPLYING_SLOT, RewardClaimLedger.EntryStatus.APPLYING, Items.BLAZE_POWDER);
        filterButton(BLOCKED_SLOT, RewardClaimLedger.EntryStatus.BLOCKED, Items.BARRIER);
        filterButton(FAILED_SLOT, RewardClaimLedger.EntryStatus.FAILED, Items.REDSTONE);
        filterButton(GRANTED_SLOT, RewardClaimLedger.EntryStatus.GRANTED, Items.EMERALD);
        if (page > 0) {
            container.setItem(PREVIOUS_PAGE_SLOT, namedItem(Items.ARROW,
                    ServerText.translatable("gui.omnitools.rewards.previous"), List.of()));
        }
        container.setItem(PAGE_INFO_SLOT, namedItem(Items.PAPER,
                ServerText.translatable("gui.omnitools.rewards.admin_page", page + 1, pageCount, filteredEntries.size()),
                List.of()));
        if (page + 1 < pageCount) {
            container.setItem(NEXT_PAGE_SLOT, namedItem(Items.ARROW,
                    ServerText.translatable("gui.omnitools.rewards.next"), List.of()));
        }
    }

    private void filterButton(int slot, RewardClaimLedger.EntryStatus status, Item icon) {
        boolean active = filter == status;
        Component name = status == null ? ServerText.translatable("gui.omnitools.rewards.filter_all")
                : ServerText.translatable("gui.omnitools.rewards.filter_status", status.name());
        ItemStack stack = namedItem(icon, name.copy().withStyle(active ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                List.of());
        if (active) {
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        container.setItem(slot, stack);
    }

    private static ItemStack displayStack(RewardClaimLedger.LedgerEntry entry) {
        ItemStack stack = iconFor(entry);
        List<Component> lore = new ArrayList<>();
        lore.add(ServerText.translatable("gui.omnitools.rewards.admin.event", entry.eventId())
                .withStyle(ChatFormatting.DARK_GRAY));
        lore.add(ServerText.translatable("gui.omnitools.rewards.admin.player", entry.displayPlayer(),
                entry.playerId() == null ? "unknown" : entry.playerId()).withStyle(ChatFormatting.DARK_GRAY));
        lore.add(ServerText.translatable("gui.omnitools.rewards.reward_id", entry.rewardId()).withStyle(ChatFormatting.DARK_GRAY));
        lore.add(ServerText.translatable("gui.omnitools.rewards.admin.type", entry.entry().rewardType())
                .withStyle(ChatFormatting.GRAY));
        lore.add(ServerText.translatable("gui.omnitools.rewards.admin.status", entry.entry().status().name())
                .withStyle(statusColor(entry.entry().status())));
        if (!entry.entry().reason().isBlank()) {
            lore.add(ServerText.translatable("gui.omnitools.rewards.reason", entry.entry().reason())
                    .withStyle(ChatFormatting.GOLD));
        }
        if (!entry.entry().dispatchedCommand().isBlank()) {
            lore.add(ServerText.translatable("gui.omnitools.rewards.admin.command", entry.entry().dispatchedCommand())
                    .withStyle(ChatFormatting.AQUA));
        }
        if (!entry.entry().audit().isBlank()) {
            lore.add(ServerText.translatable("gui.omnitools.rewards.admin.audit", entry.entry().audit())
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        if (entry.playerId() != null) {
            lore.add(ServerText.translatable("gui.omnitools.rewards.admin.left_grant").withStyle(ChatFormatting.GREEN));
            lore.add(ServerText.translatable("gui.omnitools.rewards.admin.right_fail").withStyle(ChatFormatting.RED));
        } else {
            lore.add(ServerText.translatable("gui.omnitools.rewards.admin.invalid_event").withStyle(ChatFormatting.RED));
        }
        stack.set(DataComponents.CUSTOM_NAME, ServerText.translatable("gui.omnitools.rewards.admin.entry",
                entry.rewardId()).withStyle(statusColor(entry.entry().status()), ChatFormatting.BOLD));
        stack.set(DataComponents.LORE, new ItemLore(lore));
        return stack;
    }

    private static ItemStack iconFor(RewardClaimLedger.LedgerEntry entry) {
        if (!entry.entry().itemPayload().isEmpty()) {
            ItemStack item = RewardClaimLedger.itemForDisplay(entry.entry());
            if (!item.isEmpty()) {
                return item;
            }
        }
        return switch (entry.entry().rewardType()) {
            case "currency" -> new ItemStack(Items.GOLD_INGOT);
            case "title" -> new ItemStack(Items.NAME_TAG);
            case "command" -> new ItemStack(Items.COMMAND_BLOCK);
            case "item" -> new ItemStack(Items.CHEST);
            default -> new ItemStack(Items.PAPER);
        };
    }

    private static ChatFormatting statusColor(RewardClaimLedger.EntryStatus status) {
        return switch (status) {
            case PENDING -> ChatFormatting.YELLOW;
            case APPLYING -> ChatFormatting.GOLD;
            case GRANTED -> ChatFormatting.GREEN;
            case BLOCKED -> ChatFormatting.RED;
            case FAILED -> ChatFormatting.DARK_RED;
        };
    }

    private static ItemStack background() {
        return new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
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
