package dev.modmind.omnitools;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
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
import net.minecraft.world.item.component.ResolvableProfile;

import java.time.LocalDate;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import dev.modmind.omnitools.reward.RewardClaimLedger;
import dev.modmind.omnitools.reward.RewardDefinition;
import dev.modmind.omnitools.reward.RewardEvent;
import dev.modmind.omnitools.reward.RewardType;

public final class CheckinScreenHandler extends ChestMenu {
    public static final int ROWS = 5;
    public static final int CONTAINER_SIZE = ROWS * 9;
    public static final int DATE_SLOT_COUNT = 4 * 9;
    public static final int RECORDS_SLOT = DATE_SLOT_COUNT;
    public static final int PROFILE_SLOT = 4 * 9 + 4;
    public static final int ACHIEVEMENTS_SLOT = CONTAINER_SIZE - 1;
    private final SimpleContainer checkinContainer;
    private final UUID ownerId;
    private LocalDate openedDate;

    public CheckinScreenHandler(int syncId, Inventory inventory) {
        this(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), null, null);
    }

    private CheckinScreenHandler(int syncId, Inventory inventory, SimpleContainer container, ServerPlayer owner,
                                 LocalDate openedDate) {
        super(MenuType.GENERIC_9x5, syncId, inventory, container, ROWS);
        this.checkinContainer = container;
        this.ownerId = owner == null ? null : owner.getUUID();
        this.openedDate = openedDate;
        if (owner != null) {
            refreshContents(owner, openedDate);
        }
    }

    public static CheckinScreenHandler createServer(int syncId, Inventory inventory, ServerPlayer owner) {
        ModMindEntry.rewardService().retryPending(owner);
        LocalDate openedDate = today();
        return new CheckinScreenHandler(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), owner, openedDate);
    }

    public LocalDate getOpenedDate() {
        return openedDate == null ? today() : openedDate;
    }

    public boolean hasSignedToday() {
        int todaySlot = getOpenedDate().getDayOfMonth() - 1;
        return todaySlot >= 0 && todaySlot < DATE_SLOT_COUNT
                && checkinContainer.getItem(todaySlot).is(Items.ENCHANTED_BOOK);
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (!ModMindEntry.isModuleEnabled(dev.modmind.omnitools.config.ModuleId.DAILY_CHECKIN)
                || (player instanceof ServerPlayer serverPlayerForPermission
                && !ModMindEntry.hasCommandPermission(serverPlayerForPermission,
                dev.modmind.omnitools.permissions.CommandAction.CHECKIN_OPEN))) {
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.closeContainer();
            }
            return;
        }
        // Keep the player's inventory usable, but never let UI items be moved out of the menu.
        if (slotId < 0 || slotId >= CONTAINER_SIZE) {
            super.clicked(slotId, button, clickType, player);
            return;
        }

        // Date slots are server-authoritative; the client cannot trigger sign-in locally.
        if (!(player instanceof ServerPlayer serverPlayer) || ownerId == null
                || !ownerId.equals(serverPlayer.getUUID())) {
            return;
        }
        if (slotId == RECORDS_SLOT && clickType == ClickType.PICKUP) {
            openRecordsMenu(serverPlayer);
            return;
        }
        if (slotId == ACHIEVEMENTS_SLOT && clickType == ClickType.PICKUP) {
            openAchievementsMenu(serverPlayer);
            return;
        }
        if (slotId >= DATE_SLOT_COUNT || clickType != ClickType.PICKUP) {
            return;
        }

        LocalDate currentDate = today();
        int expectedSlot = currentDate.getDayOfMonth() - 1;
        boolean dateChanged = openedDate == null || !currentDate.equals(openedDate);
        if (dateChanged || slotId != expectedSlot) {
            serverPlayer.displayClientMessage(ServerText.translatable(
                    dateChanged ? "message.omnitools.invalid_date" : "message.omnitools.only_today"), true);
            if (dateChanged) {
                refreshContents(serverPlayer, currentDate);
                broadcastChanges();
            }
            return;
        }

        CheckinData data = CheckinData.get(serverPlayer);
        CheckinData.SignInResult result = data.signIn(
                serverPlayer.getUUID(), currentDate.toEpochDay(), serverPlayer.getGameProfile().name());
        refreshContents(serverPlayer, currentDate);
        broadcastChanges();
        if (result.newlySigned()) {
            serverPlayer.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            serverPlayer.displayClientMessage(
                    ServerText.translatable("message.omnitools.success", result.stats().todayOrdinal()), true);
            ModMindEntry.rewardService().grant(serverPlayer, result);
            Component broadcastMessage = result.stats().todayOrdinal() == 1
                    ? ServerText.translatable("message.omnitools.broadcast.first", serverPlayer.getName())
                    : ServerText.translatable("message.omnitools.broadcast", serverPlayer.getName(),
                    result.stats().todayOrdinal());
            serverPlayer.level().getServer().getPlayerList().broadcastSystemMessage(broadcastMessage, false);
        } else {
            serverPlayer.displayClientMessage(ServerText.translatable("message.omnitools.already_signed"), true);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    private void refreshContents(ServerPlayer owner, LocalDate date) {
        this.openedDate = date;
        CheckinData data = CheckinData.get(owner);
        CheckinData.PlayerStats stats = data.getStats(owner.getUUID(), date.toEpochDay());
        int daysInMonth = date.lengthOfMonth();
        for (int day = 1; day <= DATE_SLOT_COUNT; day++) {
            ItemStack stack;
            if (day <= daysInMonth) {
                LocalDate slotDate = date.withDayOfMonth(day);
                boolean signed = data.hasSigned(owner.getUUID(), slotDate.toEpochDay());
                String statusKey;
                ChatFormatting statusColor;
                if (day == date.getDayOfMonth()) {
                    statusKey = signed ? "gui.omnitools.status.signed" : "gui.omnitools.status.today";
                    statusColor = signed ? ChatFormatting.GREEN : ChatFormatting.YELLOW;
                } else if (day < date.getDayOfMonth()) {
                    statusKey = signed ? "gui.omnitools.status.signed" : "gui.omnitools.status.missed";
                    statusColor = signed ? ChatFormatting.GREEN : ChatFormatting.RED;
                } else {
                    statusKey = "gui.omnitools.status.future";
                    statusColor = ChatFormatting.DARK_GRAY;
                }
                stack = new ItemStack(signed ? Items.ENCHANTED_BOOK : Items.BOOK);
                if (signed) {
                    stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
                }
                stack.set(DataComponents.CUSTOM_NAME, ServerText.translatable(
                        "gui.omnitools.day", day).withStyle(statusColor, ChatFormatting.BOLD));
                List<Component> lore = new ArrayList<>();
                lore.add(ServerText.translatable("gui.omnitools.date", date.getMonthValue(), day)
                        .withStyle(ChatFormatting.GRAY));
                lore.add(ServerText.translatable(statusKey).withStyle(statusColor));
                if (day == date.getDayOfMonth()) {
                    appendRewardLore(owner, date.toEpochDay(), stats.monthlyDays(), lore);
                }
                stack.set(DataComponents.LORE, new ItemLore(lore));
            } else {
                stack = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
                stack.set(DataComponents.CUSTOM_NAME, ServerText.translatable("gui.omnitools.empty"));
            }
            checkinContainer.setItem(day - 1, stack);
        }

        for (int slot = DATE_SLOT_COUNT; slot < CONTAINER_SIZE; slot++) {
            if (slot != PROFILE_SLOT) {
                ItemStack filler = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
                filler.set(DataComponents.CUSTOM_NAME, ServerText.translatable("gui.omnitools.empty"));
                checkinContainer.setItem(slot, filler);
            }
        }

        ItemStack recordsButton = new ItemStack(Items.CLOCK);
        recordsButton.set(DataComponents.CUSTOM_NAME, ServerText.translatable("gui.omnitools.records.button")
                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        recordsButton.set(DataComponents.LORE, new ItemLore(List.of(
                ServerText.translatable("gui.omnitools.records.button_hint").withStyle(ChatFormatting.GRAY))));
        checkinContainer.setItem(RECORDS_SLOT, recordsButton);

        ItemStack achievementsButton = new ItemStack(Items.NETHER_STAR);
        achievementsButton.set(DataComponents.CUSTOM_NAME, ServerText.translatable("gui.omnitools.achievements.button")
                .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
        achievementsButton.set(DataComponents.LORE, new ItemLore(List.of(
                ServerText.translatable("gui.omnitools.achievements.button_hint").withStyle(ChatFormatting.GRAY))));
        checkinContainer.setItem(ACHIEVEMENTS_SLOT, achievementsButton);

        ItemStack profile = new ItemStack(Items.PLAYER_HEAD);
        profile.set(DataComponents.PROFILE, ResolvableProfile.createResolved(owner.getGameProfile()));
        profile.set(DataComponents.CUSTOM_NAME, ServerText.translatable("gui.omnitools.profile", owner.getName())
                .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
        String ordinalKey = stats.signedToday()
                ? "gui.omnitools.profile.ordinal"
                : "gui.omnitools.profile.ordinal_waiting";
        profile.set(DataComponents.LORE, new ItemLore(List.of(
                ServerText.translatable(ordinalKey, stats.todayOrdinal()).withStyle(ChatFormatting.GOLD),
                ServerText.translatable("gui.omnitools.profile.total", stats.totalDays())
                        .withStyle(ChatFormatting.AQUA),
                ServerText.translatable("gui.omnitools.profile.streak", stats.streakDays())
                        .withStyle(ChatFormatting.LIGHT_PURPLE),
                ServerText.translatable("gui.omnitools.profile.monthly", stats.monthlyDays())
                        .withStyle(ChatFormatting.YELLOW),
                ServerText.translatable("gui.omnitools.profile.balance", data.getBalance(owner.getUUID()))
                        .withStyle(ChatFormatting.GOLD),
                ServerText.translatable(stats.signedToday()
                        ? "gui.omnitools.status.signed"
                        : "gui.omnitools.status.today")
                        .withStyle(stats.signedToday() ? ChatFormatting.GREEN : ChatFormatting.YELLOW))));
        checkinContainer.setItem(PROFILE_SLOT, profile);
    }

    private static void openRecordsMenu(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> CheckinRecordsScreenHandler.createServer(syncId, inventory, player),
                ServerText.translatable("gui.omnitools.records.title")));
    }

    private static void openAchievementsMenu(ServerPlayer player) {
        if (!ModMindEntry.hasCommandPermission(player,
                dev.modmind.omnitools.permissions.CommandAction.ACHIEVEMENTS_OPEN)
                || !ModMindEntry.isModuleEnabled(dev.modmind.omnitools.config.ModuleId.ACHIEVEMENTS)) {
            player.displayClientMessage(ServerText.translatable("message.omnitools.permission_denied"), true);
            return;
        }
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> AchievementScreenHandler.createServer(syncId, inventory, player,
                        ModMindEntry.achievementService(), 0),
                ServerText.translatable("gui.omnitools.achievement.title")));
    }

    private static LocalDate today() {
        return CheckinData.today();
    }

    private static void appendRewardLore(ServerPlayer player, long day, int monthlyDays, List<Component> lore) {
        List<RewardDefinition> rewards = ModMindEntry.rewardService().dailyRewards();
        appendRewardList(player, RewardEvent.checkinDaily(player.getUUID(), day), rewards, lore, false, 0);
        java.time.YearMonth month = java.time.YearMonth.from(LocalDate.ofEpochDay(day));
        for (java.util.Map.Entry<Integer, List<RewardDefinition>> entry : ModMindEntry.rewardService().monthlyRewards().entrySet()) {
            if (monthlyDays >= entry.getKey()) {
                appendRewardList(player, RewardEvent.checkinMonthly(player.getUUID(), month, entry.getKey()),
                        entry.getValue(), lore, true, entry.getKey());
            }
        }
    }

    private static void appendRewardList(ServerPlayer player, RewardEvent event, List<RewardDefinition> rewards,
                                         List<Component> lore, boolean monthly, int milestone) {
        if (monthly && !rewards.isEmpty()) {
            lore.add(ServerText.translatable("gui.omnitools.reward.monthly_prefix", milestone)
                    .withStyle(ChatFormatting.YELLOW));
        }
        for (RewardDefinition reward : rewards) {
            switch (reward.type()) {
                case CURRENCY -> lore.add(ServerText.translatable("gui.omnitools.reward.currency", reward.amount())
                        .withStyle(ChatFormatting.GOLD));
                case ITEM -> lore.add(ServerText.translatable("gui.omnitools.reward.item",
                                reward.createItemStack().getHoverName(), reward.createItemStack().getCount())
                        .withStyle(ChatFormatting.AQUA));
                case TITLE -> lore.add(ServerText.translatable("gui.omnitools.reward.title",
                                ModMindEntry.titleConfig().definition(reward.titleId())
                                .map(TitleConfig.TitleDefinition::displayComponent).orElse(Component.literal(reward.titleId())))
                        .withStyle(ChatFormatting.LIGHT_PURPLE));
                case COMMAND -> lore.add(ServerText.translatable("gui.omnitools.reward.command")
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        RewardClaimLedger ledger = RewardClaimLedger.get(player);
        if (!rewards.isEmpty() && ledger.hasEvent(event) && !ledger.allGranted(event, rewards)) {
            for (RewardDefinition reward : rewards) {
                RewardClaimLedger.Entry entry = ledger.entry(event, reward.id());
                if (entry.status() != RewardClaimLedger.EntryStatus.GRANTED) {
                    lore.add(ServerText.translatable("gui.omnitools.reward.pending", entry.reason())
                            .withStyle(ChatFormatting.YELLOW));
                    break;
                }
            }
        }
    }

}
