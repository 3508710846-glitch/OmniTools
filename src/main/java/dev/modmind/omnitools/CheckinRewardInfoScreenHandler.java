package dev.modmind.omnitools;

import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.permissions.CommandAction;
import dev.modmind.omnitools.reward.RewardClaimLedger;
import dev.modmind.omnitools.reward.RewardDefinition;
import dev.modmind.omnitools.reward.RewardEvent;
import dev.modmind.omnitools.reward.RewardType;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Read-only paged reward catalogue opened from the check-in calendar. */
public final class CheckinRewardInfoScreenHandler extends ChestMenu {
    public static final int ROWS = 6;
    public static final int CONTAINER_SIZE = 54;
    public static final int CONTENT_SLOTS = GuiSlots.CONTENT_SLOT_COUNT_54;
    public static final int BACK_SLOT = GuiSlots.FIRST_ACTION_SLOT_54;
    public static final int PREVIOUS_PAGE_SLOT = 46;
    public static final int PAGE_INFO_SLOT = GuiSlots.CENTER_54;
    public static final int NEXT_PAGE_SLOT = GuiSlots.LAST_SLOT_54;
    public static final int HEADER_TITLE_SLOT = GuiSlots.HEADER_CENTER_54;
    public static final int CLOSE_SLOT = GuiSlots.HEADER_CLOSE_54;

    private final SimpleContainer rewardContainer;
    private final ServerPlayer owner;
    private final UUID ownerId;
    private int page;
    private int pageCount = 1;
    private LocalDate openedDate;
    private long lastRevision = Long.MIN_VALUE;

    public CheckinRewardInfoScreenHandler(int syncId, Inventory inventory) {
        this(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), null, 0);
    }

    private CheckinRewardInfoScreenHandler(int syncId, Inventory inventory, SimpleContainer container,
                                            ServerPlayer owner, int page) {
        super(MenuType.GENERIC_9x6, syncId, inventory, container, ROWS);
        this.rewardContainer = container;
        this.owner = owner;
        this.ownerId = owner == null ? null : owner.getUUID();
        this.page = Math.max(0, page);
        this.openedDate = owner == null ? null : CheckinData.today(owner.level().getServer());
        if (owner != null) {
            ModMindEntry.rewardService().retryPending(owner);
            refreshContents();
        }
    }

    public static CheckinRewardInfoScreenHandler createServer(int syncId, Inventory inventory,
                                                               ServerPlayer owner, int page) {
        return new CheckinRewardInfoScreenHandler(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), owner, page);
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer) || ownerId == null
                || !ownerId.equals(serverPlayer.getUUID()) || clickType != ClickType.PICKUP) {
            return;
        }
        if (!ModMindEntry.isModuleEnabled(ModuleId.DAILY_CHECKIN)
                || !ModMindEntry.hasCommandPermission(serverPlayer, CommandAction.CHECKIN_OPEN)) {
            serverPlayer.closeContainer();
            return;
        }
        if (slotId < 0 || slotId >= CONTAINER_SIZE) {
            super.clicked(slotId, button, clickType, player);
            return;
        }
        if (slotId == BACK_SLOT) {
            ModMindEntry.openCheckinMenu(serverPlayer);
            GuiFeedbackService.click(serverPlayer);
        } else if (slotId == PREVIOUS_PAGE_SLOT && page > 0) {
            page--;
            refreshContents();
            GuiFeedbackService.click(serverPlayer);
        } else if (slotId == NEXT_PAGE_SLOT && page + 1 < pageCount) {
            page++;
            refreshContents();
            GuiFeedbackService.click(serverPlayer);
        } else if (slotId == CLOSE_SLOT) {
            serverPlayer.closeContainer();
            GuiFeedbackService.click(serverPlayer);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void broadcastChanges() {
        if (owner != null) {
            LocalDate current = CheckinData.today(owner.level().getServer());
            if (!ModMindEntry.isModuleEnabled(ModuleId.DAILY_CHECKIN)
                    || !ModMindEntry.hasCommandPermission(owner, CommandAction.CHECKIN_OPEN)) {
                owner.closeContainer();
                return;
            }
            if (!current.equals(openedDate) || lastRevision != ModMindEntry.configSnapshot().revision()) {
                refreshContents();
            }
        }
        super.broadcastChanges();
    }

    private void refreshContents() {
        openedDate = CheckinData.today(owner.level().getServer());
        List<DisplayEntry> entries = buildEntries(owner, openedDate);
        pageCount = Math.max(1, (entries.size() + CONTENT_SLOTS - 1) / CONTENT_SLOTS);
        page = Math.min(page, pageCount - 1);
        GuiTheme.clear(rewardContainer);
        int first = page * CONTENT_SLOTS;
        int visible = Math.min(CONTENT_SLOTS, Math.max(0, entries.size() - first));
        for (int index = 0; index < visible; index++) {
            rewardContainer.setItem(GuiSlots.contentSlot54(index), displayStack(owner, entries.get(first + index)));
        }
        rewardContainer.setItem(GuiSlots.HEADER_LEFT_54, GuiTheme.status(Items.CHEST,
                ServerText.translatable("gui.omnitools.checkin.reward_info"), ChatFormatting.GOLD,
                List.of(ServerText.translatable("gui.omnitools.checkin.daily_count",
                        ModMindEntry.rewardService().dailyRewards().size()).withStyle(ChatFormatting.GRAY)), false));
        rewardContainer.setItem(HEADER_TITLE_SLOT, GuiTheme.status(Items.WRITABLE_BOOK,
                ServerText.translatable("gui.omnitools.checkin.reward_info"), ChatFormatting.AQUA,
                List.of(ServerText.translatable("gui.omnitools.checkin.page", page + 1, pageCount)
                        .withStyle(ChatFormatting.GRAY)), false));
        rewardContainer.setItem(CLOSE_SLOT, GuiNavigationService.close());
        rewardContainer.setItem(BACK_SLOT, namedItem(Items.ARROW,
                ServerText.translatable("gui.omnitools.checkin.back"),
                List.of(ServerText.translatable("gui.omnitools.checkin.back_hint"))));
        if (page > 0) {
            rewardContainer.setItem(PREVIOUS_PAGE_SLOT, GuiNavigationService.previous());
        }
        rewardContainer.setItem(PAGE_INFO_SLOT, GuiNavigationService.page(page + 1, pageCount, entries.size()));
        if (page + 1 < pageCount) {
            rewardContainer.setItem(NEXT_PAGE_SLOT, GuiNavigationService.next());
        }
        lastRevision = ModMindEntry.configSnapshot().revision();
    }

    private static List<DisplayEntry> buildEntries(ServerPlayer player, LocalDate date) {
        List<DisplayEntry> entries = new ArrayList<>();
        List<RewardDefinition> daily = ModMindEntry.rewardService().dailyRewards();
        if (daily.isEmpty()) {
            entries.add(DisplayEntry.summary("daily", 0, null, null));
        } else {
            daily.forEach(reward -> entries.add(DisplayEntry.reward("daily", 0, reward,
                    RewardEvent.checkinDaily(player.getUUID(), date.toEpochDay()))));
        }
        YearMonth month = YearMonth.from(date);
        for (var milestone : ModMindEntry.rewardService().monthlyRewards().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey()).toList()) {
            entries.add(DisplayEntry.summary("monthly", milestone.getKey(), null,
                    RewardEvent.checkinMonthly(player.getUUID(), month, milestone.getKey())));
            for (RewardDefinition reward : milestone.getValue()) {
                entries.add(DisplayEntry.reward("monthly", milestone.getKey(), reward,
                        RewardEvent.checkinMonthly(player.getUUID(), month, milestone.getKey())));
            }
        }
        return entries;
    }

    private static ItemStack displayStack(ServerPlayer player, DisplayEntry entry) {
        ItemStack stack;
        if (entry.summary()) {
            stack = new ItemStack(entry.kind().equals("daily") ? Items.CHEST : Items.CHEST);
        } else {
            RewardDefinition reward = entry.reward();
            stack = switch (reward.type()) {
                case CURRENCY -> new ItemStack(reward.amount() >= 1_000 ? Items.GOLD_INGOT : Items.GOLD_NUGGET);
                case MAKEUP_CARD -> new ItemStack(Items.CLOCK);
                case ITEM -> TextTemplateRenderer.renderItemText(player, reward.createItemStack());
                case TITLE -> new ItemStack(Items.NAME_TAG);
                case COMMAND -> new ItemStack(Items.COMMAND_BLOCK);
                case PACKAGE -> new ItemStack(Items.CHEST);
            };
        }
        String status = status(player, entry);
        ChatFormatting color = status.equals("gui.omnitools.checkin.granted")
                ? ChatFormatting.GREEN : status.equals("gui.omnitools.checkin.pending")
                ? ChatFormatting.YELLOW : status.equals("gui.omnitools.checkin.failed")
                ? ChatFormatting.RED : status.equals("gui.omnitools.checkin.available")
                ? ChatFormatting.AQUA : ChatFormatting.GRAY;
        Component name = entry.summary()
                ? ServerText.translatable(entry.kind().equals("daily") ? "gui.omnitools.checkin.daily_rewards"
                : "gui.omnitools.checkin.monthly_milestone", entry.milestone())
                : rewardName(player, entry.reward());
        ItemLore existingLore = stack.get(DataComponents.LORE);
        List<Component> lore = new ArrayList<>(existingLore == null ? List.of() : existingLore.lines());
        if (!entry.summary()) {
            if (entry.reward().type() == RewardType.CURRENCY) {
                lore.add(ServerText.translatable("gui.omnitools.reward.currency", entry.reward().amount())
                        .withStyle(ChatFormatting.GOLD));
            } else if (entry.reward().type() == RewardType.MAKEUP_CARD) {
                lore.add(ServerText.translatable("gui.omnitools.reward.makeup_card", entry.reward().amount())
                        .withStyle(ChatFormatting.AQUA));
            } else if (entry.reward().type() == RewardType.ITEM) {
                ItemStack displayItem = TextTemplateRenderer.renderItemText(player, entry.reward().createItemStack());
                lore.add(ServerText.translatable("gui.omnitools.reward.item", displayItem.getHoverName(),
                        displayItem.getCount()).withStyle(ChatFormatting.AQUA));
            }
            RewardClaimLedger.Entry ledger = entry.event() == null ? null
                    : RewardClaimLedger.get(player).entry(entry.event(), entry.reward().id());
            if (ledger != null && !ledger.reason().isBlank()
                    && ledger.status() != RewardClaimLedger.EntryStatus.GRANTED) {
                lore.add(ServerText.translatable("gui.omnitools.reward.pending", ledger.reason())
                        .withStyle(ChatFormatting.YELLOW));
            }
        }
        return GuiStatusItem.create(stack, name, visualState(status), GuiTextService.cardLore(lore,
                ServerText.translatable("gui.omnitools.checkin.reward_status", ServerText.translatable(status))
                        .withStyle(color)));
    }

    private static Component rewardName(ServerPlayer player, RewardDefinition reward) {
        return switch (reward.type()) {
            case CURRENCY -> ServerText.translatable("gui.omnitools.checkin.currency_reward");
            case MAKEUP_CARD -> ServerText.translatable("gui.omnitools.checkin.makeup_card_reward");
            case ITEM -> TextTemplateRenderer.renderItemText(player, reward.createItemStack()).getHoverName();
            case TITLE -> ModMindEntry.titleConfig().definition(reward.titleId())
                    .<Component>map(title -> TextTemplateRenderer.render(player, title.display()))
                    .orElseGet(() -> ServerText.translatable("gui.omnitools.checkin.title_reward", reward.titleId()));
            case COMMAND -> ServerText.translatable("gui.omnitools.checkin.command_reward");
            case PACKAGE -> ServerText.translatable("gui.omnitools.checkin.package_reward");
        };
    }

    private static String status(ServerPlayer player, DisplayEntry entry) {
        LocalDate today = CheckinData.today(player.level().getServer());
        CheckinData data = CheckinData.get(player);
        if (entry.kind().equals("daily")) {
            if (!data.hasSigned(player.getUUID(), today.toEpochDay())) {
                return "gui.omnitools.checkin.not_claimed";
            }
            if (entry.summary()) {
                return "gui.omnitools.checkin.granted";
            }
        } else {
            if (data.getStats(player.getUUID(), today.toEpochDay()).monthlyDays() < entry.milestone()) {
                return "gui.omnitools.checkin.not_reached";
            }
            if (data.hasClaimedMonthlyReward(player.getUUID(), YearMonth.from(today), entry.milestone())) {
                return "gui.omnitools.checkin.granted";
            }
        }
        if (entry.event() == null) {
            return "gui.omnitools.checkin.not_claimed";
        }
        RewardClaimLedger ledger = RewardClaimLedger.get(player);
        if (entry.summary()) {
            List<RewardDefinition> rewards = ModMindEntry.rewardService().monthlyRewards()
                    .getOrDefault(entry.milestone(), List.of());
            if (rewards.isEmpty()) {
                return "gui.omnitools.checkin.granted";
            }
            if (!ledger.hasEvent(entry.event())) {
                return "gui.omnitools.checkin.available";
            }
            return ledger.allGranted(entry.event(), rewards)
                    ? "gui.omnitools.checkin.granted" : "gui.omnitools.checkin.pending";
        }
        if (!ledger.hasEvent(entry.event())) {
            return "gui.omnitools.checkin.available";
        }
        RewardClaimLedger.Entry ledgerEntry = ledger.entry(entry.event(), entry.reward().id());
        return ledgerEntry.status() == RewardClaimLedger.EntryStatus.GRANTED
                ? "gui.omnitools.checkin.granted"
                : ledgerEntry.status() == RewardClaimLedger.EntryStatus.FAILED
                ? "gui.omnitools.checkin.failed" : "gui.omnitools.checkin.pending";
    }

    private static GuiStatusItem.State visualState(String status) {
        return switch (status) {
            case "gui.omnitools.checkin.available" -> GuiStatusItem.State.ACTIONABLE;
            case "gui.omnitools.checkin.granted" -> GuiStatusItem.State.COMPLETED;
            case "gui.omnitools.checkin.pending" -> GuiStatusItem.State.PENDING;
            case "gui.omnitools.checkin.failed" -> GuiStatusItem.State.BLOCKED;
            default -> GuiStatusItem.State.INACTIVE;
        };
    }
    private static ItemStack namedItem(Item item, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, name);
        if (!lore.isEmpty()) {
            stack.set(DataComponents.LORE, new ItemLore(lore));
        }
        return stack;
    }

    public void refreshAfterRewardRetry() {
        if (owner != null) {
            refreshContents();
            broadcastChanges();
        }
    }

    private record DisplayEntry(String kind, int milestone, RewardDefinition reward, RewardEvent event) {
        static DisplayEntry summary(String kind, int milestone, RewardDefinition reward, RewardEvent event) {
            return new DisplayEntry(kind, milestone, reward, event);
        }

        static DisplayEntry reward(String kind, int milestone, RewardDefinition reward, RewardEvent event) {
            return new DisplayEntry(kind, milestone, reward, event);
        }

        boolean summary() {
            return reward == null;
        }
    }
}
