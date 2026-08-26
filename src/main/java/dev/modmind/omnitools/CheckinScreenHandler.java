package dev.modmind.omnitools;

import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.permissions.CommandAction;
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

import java.time.LocalDate;
import java.util.UUID;

/** Server-authoritative six-row vanilla chest menu for the daily check-in journal. */
public final class CheckinScreenHandler extends ChestMenu {
    public static final int ROWS = CheckinTheme.ROWS;
    public static final int CONTAINER_SIZE = CheckinTheme.CONTAINER_SIZE;
    public static final int PROFILE_SLOT = CheckinTheme.PROFILE_SLOT;
    /** Retained as a source-compatible alias; slot 8 is now the month panel. */
    public static final int TODAY_SLOT = CheckinTheme.MONTH_SLOT;
    public static final int REWARD_INFO_SLOT = CheckinTheme.REWARD_INFO_SLOT;
    public static final int PROGRESS_SLOT = CheckinTheme.PROGRESS_SLOT;
    public static final int RECORDS_SLOT = CheckinTheme.RECORDS_SLOT;
    public static final int ACHIEVEMENTS_SLOT = CheckinTheme.ACHIEVEMENTS_SLOT;
    public static final int STREAK_SLOT = CheckinTheme.STREAK_SLOT;
    public static final int BALANCE_SLOT = CheckinTheme.BALANCE_SLOT;

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
        CheckinScreenHandler handler = new CheckinScreenHandler(syncId, inventory,
                new SimpleContainer(CONTAINER_SIZE), owner, CheckinData.today(owner.level().getServer()));
        if (ModMindEntry.rewardService().ui().sounds().open()) {
            owner.playSound(SoundEvents.CHEST_OPEN, 0.65f, 1.0f);
        }
        return handler;
    }

    public LocalDate getOpenedDate() {
        return openedDate == null ? today() : openedDate;
    }

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
                || !ownerId.equals(serverPlayer.getUUID()) || clickType != ClickType.PICKUP || button != 0) {
            return;
        }
        CheckinUiConfig ui = ModMindEntry.rewardService().ui();
        if (slotId == CheckinTheme.CLOSE_SLOT) {
            serverPlayer.closeContainer();
            playClick(serverPlayer, ui);
            return;
        }
        if (slotId == CheckinTheme.REFRESH_SLOT) {
            refreshContents(serverPlayer, today());
            broadcastChanges();
            playClick(serverPlayer, ui);
            return;
        }
        if (slotId == REWARD_INFO_SLOT) {
            openRewardInfo(serverPlayer);
            playClick(serverPlayer, ui);
            return;
        }
        if (slotId == CheckinTheme.REWARD_INBOX_SLOT) {
            if (ModMindEntry.hasCommandPermission(serverPlayer, CommandAction.REWARDS_RETRY)) {
                ModMindEntry.openRewardInbox(serverPlayer);
                playClick(serverPlayer, ui);
            } else {
                serverPlayer.displayClientMessage(ServerText.translatable("message.omnitools.permission_denied"), true);
                playFailure(serverPlayer, ui);
            }
            return;
        }
        if (slotId == RECORDS_SLOT) {
            openRecordsMenu(serverPlayer);
            playClick(serverPlayer, ui);
            return;
        }
        if (slotId == ACHIEVEMENTS_SLOT) {
            openAchievementsMenu(serverPlayer);
            playClick(serverPlayer, ui);
            return;
        }
        if (slotId == CheckinTheme.HELP_SLOT) {
            serverPlayer.displayClientMessage(ServerText.translatable("message.omnitools.checkin.help"), true);
            playClick(serverPlayer, ui);
            return;
        }

        Integer day = CheckinTheme.slotToDay(getOpenedDate(), slotId);
        if (day == null) {
            return;
        }
        LocalDate selectedDate = getOpenedDate().withDayOfMonth(day);
        if (!selectedDate.equals(today())
                && ModMindEntry.rewardService().monthlyRewards().containsKey(day)
                && ui.showRewardPreview()) {
            openRewardInfo(serverPlayer);
            playClick(serverPlayer, ui);
            return;
        }
        LocalDate currentDate = today();
        if (!currentDate.equals(openedDate)) {
            serverPlayer.displayClientMessage(ServerText.translatable("message.omnitools.invalid_date"), true);
            refreshContents(serverPlayer, currentDate);
            broadcastChanges();
            playFailure(serverPlayer, ui);
            return;
        }
        int todaySlot = CheckinTheme.slotForDay(currentDate, currentDate.getDayOfMonth());
        if (slotId != todaySlot) {
            serverPlayer.displayClientMessage(ServerText.translatable("message.omnitools.only_today"), true);
            playFailure(serverPlayer, ui);
            return;
        }

        CheckinData data = CheckinData.get(serverPlayer);
        CheckinData.SignInResult result = data.signIn(serverPlayer.getUUID(), currentDate.toEpochDay(),
                serverPlayer.getGameProfile().name());
        refreshContents(serverPlayer, currentDate);
        broadcastChanges();
        if (result.newlySigned()) {
            if (ui.sounds().success()) {
                serverPlayer.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            }
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
            playFailure(serverPlayer, ui);
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
            if (!currentDate.equals(openedDate) || lastRevision != ModMindEntry.configSnapshot().revision()) {
                refreshContents(owner, currentDate);
            }
        }
        super.broadcastChanges();
    }

    public void refreshAfterRewardRetry() {
        if (owner != null) {
            refreshContents(owner, today());
            broadcastChanges();
        }
    }

    public static int monthStartOffset(LocalDate date) {
        return CheckinTheme.monthStartOffset(date);
    }

    public static int slotForDay(LocalDate month, int day) {
        return CheckinTheme.slotForDay(month, day);
    }

    public static Integer slotToDay(LocalDate month, int slot) {
        return CheckinTheme.slotToDay(month, slot);
    }

    private void refreshContents(ServerPlayer player, LocalDate date) {
        openedDate = date;
        CheckinData data = CheckinData.get(player);
        CheckinData.PlayerStats stats = data.getStats(player.getUUID(), date.toEpochDay());
        CheckinRewardService rewards = ModMindEntry.rewardService();
        CheckinUiConfig ui = rewards.ui();
        for (int slot = 0; slot < CONTAINER_SIZE; slot++) {
            checkinContainer.setItem(slot, ItemStack.EMPTY);
        }
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < CheckinTheme.CALENDAR_COLUMNS; column++) {
                checkinContainer.setItem(row * 9 + column, CheckinRenderService.emptyCalendarStack(ui));
            }
        }
        for (int day = 1; day <= date.lengthOfMonth(); day++) {
            int slot = CheckinTheme.slotForDay(date, day);
            checkinContainer.setItem(slot, CheckinRenderService.calendarStack(data, player,
                    date.withDayOfMonth(day), date, rewards, ui));
        }
        checkinContainer.setItem(PROFILE_SLOT, CheckinRenderService.profileStack(player, stats));
        checkinContainer.setItem(CheckinTheme.MONTH_SLOT, CheckinRenderService.monthStack(date));
        checkinContainer.setItem(REWARD_INFO_SLOT, CheckinRenderService.rewardInfoStack(player, rewards, stats, ui));
        checkinContainer.setItem(PROGRESS_SLOT, CheckinRenderService.progressStack(rewards, stats.monthlyDays(), ui));
        checkinContainer.setItem(RECORDS_SLOT, CheckinRenderService.recordsStack());
        checkinContainer.setItem(ACHIEVEMENTS_SLOT, CheckinRenderService.achievementsStack());
        checkinContainer.setItem(STREAK_SLOT, CheckinRenderService.streakStack(stats.streakDays()));
        checkinContainer.setItem(BALANCE_SLOT, CheckinRenderService.balanceStack(data.getBalance(player.getUUID())));
        checkinContainer.setItem(CheckinTheme.REWARD_INBOX_SLOT, CheckinRenderService.inboxStack(player));
        checkinContainer.setItem(CheckinTheme.HELP_SLOT, CheckinRenderService.helpStack());
        checkinContainer.setItem(CheckinTheme.REFRESH_SLOT, CheckinRenderService.refreshStack());
        checkinContainer.setItem(CheckinTheme.CLOSE_SLOT, CheckinRenderService.closeStack());
        lastRevision = ModMindEntry.configSnapshot().revision();
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

    private static void playClick(ServerPlayer player, CheckinUiConfig ui) {
        if (ui.sounds().click()) {
            player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.7f, 1.0f);
        }
    }

    private static void playFailure(ServerPlayer player, CheckinUiConfig ui) {
        if (ui.sounds().failure()) {
            player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 0.6f);
        }
    }

    private static LocalDate today() {
        return CheckinData.today();
    }
}
