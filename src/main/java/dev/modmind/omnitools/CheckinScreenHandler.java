package dev.modmind.omnitools;

import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.permissions.CommandAction;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
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
import net.minecraft.world.item.component.ResolvableProfile;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Server-authoritative six-row vanilla chest menu containing a real Monday-first calendar. */
public final class CheckinScreenHandler extends ChestMenu {
    public static final int ROWS = 6;
    public static final int CONTAINER_SIZE = ROWS * 9;
    public static final int PROFILE_SLOT = 7;
    public static final int TODAY_SLOT = 8;
    public static final int REWARD_INFO_SLOT = 16;
    public static final int PROGRESS_SLOT = 17;
    public static final int RECORDS_SLOT = 25;
    public static final int ACHIEVEMENTS_SLOT = 26;
    public static final int STREAK_SLOT = 34;
    public static final int BALANCE_SLOT = 35;

    private final SimpleContainer checkinContainer;
    private final UUID ownerId;
    private final ServerPlayer owner;
    private LocalDate openedDate;
    private long lastRevision = Long.MIN_VALUE;

    public CheckinScreenHandler(int syncId, Inventory inventory) {
        this(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), null, null);
    }

    private CheckinScreenHandler(int syncId, Inventory inventory, SimpleContainer container,
                                 ServerPlayer owner, LocalDate openedDate) {
        super(MenuType.GENERIC_9x6, syncId, inventory, container, ROWS);
        this.checkinContainer = container;
        this.owner = owner;
        this.ownerId = owner == null ? null : owner.getUUID();
        this.openedDate = openedDate;
        if (owner != null && openedDate != null) {
            refreshContents(owner, openedDate);
        }
    }

    public static CheckinScreenHandler createServer(int syncId, Inventory inventory, ServerPlayer owner) {
        ModMindEntry.rewardService().retryPending(owner);
        return new CheckinScreenHandler(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), owner,
                CheckinData.today(owner.level().getServer()));
    }

    public LocalDate getOpenedDate() {
        return openedDate == null ? today() : openedDate;
    }

    /** Reads the persistent record instead of inferring state from a displayed item. */
    public boolean hasSignedToday() {
        return owner != null && CheckinData.get(owner).hasSigned(owner.getUUID(), today().toEpochDay());
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (!ModMindEntry.isModuleEnabled(ModuleId.DAILY_CHECKIN)
                || (player instanceof ServerPlayer permissionPlayer
                && !ModMindEntry.hasCommandPermission(permissionPlayer, CommandAction.CHECKIN_OPEN))) {
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
        if (slotId == REWARD_INFO_SLOT) {
            openRewardInfo(serverPlayer);
            return;
        }
        if (slotId == RECORDS_SLOT) {
            openRecordsMenu(serverPlayer);
            return;
        }
        if (slotId == ACHIEVEMENTS_SLOT) {
            openAchievementsMenu(serverPlayer);
            return;
        }
        if (slotId != TODAY_SLOT && slotToDay(getOpenedDate(), slotId) == null) {
            return;
        }
        LocalDate currentDate = today();
        if (!currentDate.equals(openedDate)) {
            serverPlayer.displayClientMessage(ServerText.translatable("message.omnitools.invalid_date"), true);
            refreshContents(serverPlayer, currentDate);
            broadcastChanges();
            return;
        }
        int todaySlot = slotForDay(currentDate, currentDate.getDayOfMonth());
        if (slotId != TODAY_SLOT && slotId != todaySlot) {
            serverPlayer.displayClientMessage(ServerText.translatable("message.omnitools.only_today"), true);
            return;
        }

        CheckinData data = CheckinData.get(serverPlayer);
        CheckinData.SignInResult result = data.signIn(serverPlayer.getUUID(), currentDate.toEpochDay(),
                serverPlayer.getGameProfile().name());
        refreshContents(serverPlayer, currentDate);
        broadcastChanges();
        if (result.newlySigned()) {
            serverPlayer.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            serverPlayer.displayClientMessage(ServerText.translatable("message.omnitools.success",
                    result.stats().todayOrdinal()), true);
            ModMindEntry.rewardService().grant(serverPlayer, result);
            Component broadcastMessage = result.stats().todayOrdinal() == 1
                    ? ServerText.translatable("message.omnitools.broadcast.first", serverPlayer.getName())
                    : ServerText.translatable("message.omnitools.broadcast", serverPlayer.getName(),
                    result.stats().todayOrdinal());
            serverPlayer.level().getServer().getPlayerList().broadcastSystemMessage(broadcastMessage, false);
            refreshContents(serverPlayer, currentDate);
            broadcastChanges();
        } else {
            serverPlayer.displayClientMessage(ServerText.translatable("message.omnitools.already_signed"), true);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void broadcastChanges() {
        if (owner != null) {
            LocalDate currentDate = today();
            if (!ModMindEntry.isModuleEnabled(ModuleId.DAILY_CHECKIN)
                    || !ModMindEntry.hasCommandPermission(owner, CommandAction.CHECKIN_OPEN)) {
                owner.closeContainer();
                return;
            }
            if (!currentDate.equals(openedDate)
                    || lastRevision != ModMindEntry.configSnapshot().revision()) {
                refreshContents(owner, currentDate);
            }
        }
        super.broadcastChanges();
    }

    private void refreshContents(ServerPlayer player, LocalDate date) {
        openedDate = date;
        CheckinData data = CheckinData.get(player);
        CheckinData.PlayerStats stats = data.getStats(player.getUUID(), date.toEpochDay());
        for (int slot = 0; slot < CONTAINER_SIZE; slot++) {
            checkinContainer.setItem(slot, emptySlot());
        }

        int offset = monthStartOffset(date);
        for (int day = 1; day <= date.lengthOfMonth(); day++) {
            int slot = ((offset + day - 1) / 7) * 9 + ((offset + day - 1) % 7);
            LocalDate slotDate = date.withDayOfMonth(day);
            checkinContainer.setItem(slot, createDateStack(data, player, slotDate, date));
        }

        checkinContainer.setItem(PROFILE_SLOT, profileStack(player, stats));
        checkinContainer.setItem(TODAY_SLOT, todayStack(data, player, date, stats));
        checkinContainer.setItem(REWARD_INFO_SLOT, rewardInfoStack(player, stats));
        checkinContainer.setItem(PROGRESS_SLOT, progressStack(date, stats.monthlyDays()));
        checkinContainer.setItem(RECORDS_SLOT, namedItem(Items.CLOCK,
                ServerText.translatable("gui.omnitools.checkin.records"),
                List.of(ServerText.translatable("gui.omnitools.checkin.records_hint"))));
        checkinContainer.setItem(ACHIEVEMENTS_SLOT, namedItem(Items.NETHER_STAR,
                ServerText.translatable("gui.omnitools.checkin.achievements"),
                List.of(ServerText.translatable("gui.omnitools.checkin.achievements_hint"))));
        checkinContainer.setItem(STREAK_SLOT, namedItem(Items.CAMPFIRE,
                ServerText.translatable("gui.omnitools.checkin.streak", stats.streakDays()), List.of()));
        checkinContainer.setItem(BALANCE_SLOT, namedItem(Items.GOLD_INGOT,
                ServerText.translatable("gui.omnitools.checkin.balance", data.getBalance(player.getUUID())), List.of()));
        lastRevision = ModMindEntry.configSnapshot().revision();
    }

    private static ItemStack createDateStack(CheckinData data, ServerPlayer player, LocalDate slotDate,
                                             LocalDate month) {
        boolean signed = data.hasSigned(player.getUUID(), slotDate.toEpochDay());
        boolean today = slotDate.equals(month);
        ItemStack stack;
        ChatFormatting color;
        String nameKey;
        if (today && !signed) {
            stack = new ItemStack(Items.CLOCK);
            color = ChatFormatting.GOLD;
            nameKey = "gui.omnitools.checkin.today_available";
        } else if (today) {
            stack = new ItemStack(Items.LIME_STAINED_GLASS_PANE);
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            color = ChatFormatting.GREEN;
            nameKey = "gui.omnitools.checkin.today_signed";
        } else if (slotDate.isBefore(month)) {
            stack = new ItemStack(signed ? Items.LIME_STAINED_GLASS_PANE : Items.RED_STAINED_GLASS_PANE);
            color = signed ? ChatFormatting.GREEN : ChatFormatting.RED;
            nameKey = signed ? "gui.omnitools.checkin.day_signed" : "gui.omnitools.checkin.day_missed";
        } else {
            stack = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
            color = ChatFormatting.DARK_GRAY;
            nameKey = "gui.omnitools.checkin.day_future";
        }
        stack.set(DataComponents.CUSTOM_NAME, ServerText.translatable(nameKey, slotDate.getDayOfMonth())
                .withStyle(color, ChatFormatting.BOLD));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                ServerText.translatable("gui.omnitools.checkin.date", slotDate.getMonthValue(),
                        slotDate.getDayOfMonth(), ServerText.translatable(weekdayName(slotDate.getDayOfWeek())))
                        .withStyle(ChatFormatting.GRAY),
                ServerText.translatable(today && !signed ? "gui.omnitools.checkin.today_hint"
                        : "gui.omnitools.checkin.no_action").withStyle(color))));
        return stack;
    }

    private ItemStack profileStack(ServerPlayer player, CheckinData.PlayerStats stats) {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(player.getGameProfile()));
        stack.set(DataComponents.CUSTOM_NAME, ServerText.translatable("gui.omnitools.checkin.profile", player.getName())
                .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                ServerText.translatable(stats.signedToday() ? "gui.omnitools.checkin.today_signed"
                        : "gui.omnitools.checkin.today_available").withStyle(stats.signedToday()
                        ? ChatFormatting.GREEN : ChatFormatting.GOLD),
                ServerText.translatable("gui.omnitools.checkin.total", stats.totalDays())
                        .withStyle(ChatFormatting.AQUA))));
        return stack;
    }

    private ItemStack todayStack(CheckinData data, ServerPlayer player, LocalDate date,
                                 CheckinData.PlayerStats stats) {
        boolean signed = data.hasSigned(player.getUUID(), date.toEpochDay());
        List<Component> lore = new ArrayList<>();
        lore.add(ServerText.translatable(signed ? "gui.omnitools.checkin.no_action"
                : "gui.omnitools.checkin.today_hint"));
        if (signed && hasPendingRewards(player, date, stats.monthlyDays())) {
            lore.add(ServerText.translatable("gui.omnitools.checkin.rewards_pending")
                    .withStyle(ChatFormatting.YELLOW));
        }
        return namedItem(signed ? Items.LIME_STAINED_GLASS_PANE : Items.CLOCK,
                ServerText.translatable(signed ? "gui.omnitools.checkin.today_signed"
                        : "gui.omnitools.checkin.today_available").withStyle(signed
                        ? ChatFormatting.GREEN : ChatFormatting.GOLD, ChatFormatting.BOLD),
                lore);
    }

    private ItemStack rewardInfoStack(ServerPlayer player, CheckinData.PlayerStats stats) {
        int dailyCount = ModMindEntry.rewardService().dailyRewards().size();
        int next = nextMilestone(stats.monthlyDays());
        List<Component> lore = new ArrayList<>();
        lore.add(ServerText.translatable("gui.omnitools.checkin.daily_count", dailyCount));
        lore.add(ServerText.translatable("gui.omnitools.checkin.month_progress", stats.monthlyDays()));
        lore.add(next > 0 ? ServerText.translatable("gui.omnitools.checkin.next_milestone", next)
                : ServerText.translatable("gui.omnitools.checkin.milestone_complete"));
        lore.add(ServerText.translatable("gui.omnitools.checkin.reward_hint"));
        return namedItem(Items.CHEST, ServerText.translatable("gui.omnitools.checkin.reward_info"), lore);
    }

    private static ItemStack progressStack(LocalDate date, int monthlyDays) {
        int next = nextMilestone(monthlyDays);
        List<Component> lore = new ArrayList<>();
        lore.add(ServerText.translatable("gui.omnitools.checkin.month_progress", monthlyDays));
        if (next > 0) {
            lore.add(ServerText.translatable("gui.omnitools.checkin.next_milestone", next));
            lore.add(ServerText.translatable("gui.omnitools.checkin.days_left", Math.max(0, next - monthlyDays)));
        }
        return namedItem(Items.FILLED_MAP, ServerText.translatable("gui.omnitools.checkin.progress"), lore);
    }

    private static int nextMilestone(int monthlyDays) {
        return ModMindEntry.rewardService().monthlyRewards().keySet().stream().sorted()
                .filter(milestone -> milestone > monthlyDays).findFirst().orElse(-1);
    }

    private static boolean hasPendingRewards(ServerPlayer player, LocalDate date, int monthlyDays) {
        var rewards = ModMindEntry.rewardService();
        var ledger = dev.modmind.omnitools.reward.RewardClaimLedger.get(player);
        var dailyEvent = dev.modmind.omnitools.reward.RewardEvent.checkinDaily(player.getUUID(), date.toEpochDay());
        if (ledger.hasEvent(dailyEvent) && !ledger.allGranted(dailyEvent, rewards.dailyRewards())) {
            return true;
        }
        java.time.YearMonth month = java.time.YearMonth.from(date);
        for (var milestone : rewards.monthlyRewards().entrySet()) {
            if (monthlyDays < milestone.getKey()) {
                continue;
            }
            var event = dev.modmind.omnitools.reward.RewardEvent.checkinMonthly(player.getUUID(), month,
                    milestone.getKey());
            if (ledger.hasEvent(event) && !ledger.allGranted(event, milestone.getValue())) {
                return true;
            }
        }
        return false;
    }

    public void refreshAfterRewardRetry() {
        if (owner != null) {
            refreshContents(owner, today());
            broadcastChanges();
        }
    }

    private static void openRewardInfo(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> CheckinRewardInfoScreenHandler.createServer(syncId, inventory, player, 0),
                ServerText.translatable("gui.omnitools.checkin.reward_info")));
    }

    private static void openRecordsMenu(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> CheckinRecordsScreenHandler.createServer(syncId, inventory, player),
                ServerText.translatable("gui.omnitools.records.title")));
    }

    private static void openAchievementsMenu(ServerPlayer player) {
        if (!ModMindEntry.hasCommandPermission(player, CommandAction.ACHIEVEMENTS_OPEN)
                || !ModMindEntry.isModuleEnabled(ModuleId.ACHIEVEMENTS)) {
            player.displayClientMessage(ServerText.translatable("message.omnitools.permission_denied"), true);
            return;
        }
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> AchievementScreenHandler.createServer(syncId, inventory, player,
                        ModMindEntry.achievementService(), 0),
                ServerText.translatable("gui.omnitools.achievement.title")));
    }

    public static int monthStartOffset(LocalDate date) {
        return date.withDayOfMonth(1).getDayOfWeek().getValue() - 1;
    }

    public static int slotForDay(LocalDate month, int day) {
        if (day < 1 || day > month.lengthOfMonth()) {
            return -1;
        }
        int index = monthStartOffset(month) + day - 1;
        return (index / 7) * 9 + (index % 7);
    }

    public static Integer slotToDay(LocalDate month, int slot) {
        if (slot < 0 || slot >= CONTAINER_SIZE || slot % 9 > 6) {
            return null;
        }
        int index = (slot / 9) * 7 + slot % 9 - monthStartOffset(month);
        int day = index + 1;
        return day >= 1 && day <= month.lengthOfMonth() ? day : null;
    }

    private static String weekdayName(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "gui.omnitools.checkin.monday";
            case TUESDAY -> "gui.omnitools.checkin.tuesday";
            case WEDNESDAY -> "gui.omnitools.checkin.wednesday";
            case THURSDAY -> "gui.omnitools.checkin.thursday";
            case FRIDAY -> "gui.omnitools.checkin.friday";
            case SATURDAY -> "gui.omnitools.checkin.saturday";
            case SUNDAY -> "gui.omnitools.checkin.sunday";
        };
    }

    private static ItemStack emptySlot() {
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

    private static LocalDate today() {
        return CheckinData.today();
    }
}
