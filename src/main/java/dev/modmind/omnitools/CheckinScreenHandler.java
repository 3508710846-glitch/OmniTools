package dev.modmind.omnitools;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.flag.FeatureFlags;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

public final class CheckinScreenHandler extends ChestMenu {
    public static final int ROWS = 5;
    public static final int CONTAINER_SIZE = ROWS * 9;
    public static final int DATE_SLOT_COUNT = 4 * 9;
    public static final int RECORDS_SLOT = DATE_SLOT_COUNT;
    public static final int PROFILE_SLOT = 4 * 9 + 4;
    public static final int ACHIEVEMENTS_SLOT = CONTAINER_SIZE - 1;
    private static final int NEXT_CHECKIN_SECONDS_DATA_SLOT = 1;
    public static final MenuType<CheckinScreenHandler> TYPE = Registry.register(
            BuiltInRegistries.MENU,
            Identifier.fromNamespaceAndPath(ModMindEntry.MOD_ID, "checkin"),
            new MenuType<>(CheckinScreenHandler::new, FeatureFlags.DEFAULT_FLAGS));

    private final SimpleContainer checkinContainer;
    private final UUID ownerId;
    private final ServerPlayer owner;
    private final DataSlot openedDayData = addDataSlot(DataSlot.standalone());
    private final DataSlot nextCheckinSeconds = addDataSlot(DataSlot.standalone());
    private LocalDate openedDate;
    private long nextCheckinDeadlineMillis;
    private long clientCountdownDeadlineNanos;
    private long lastCountdownUpdateTick = Long.MIN_VALUE;

    public static void register() {
        // Loading this class registers TYPE before the client creates its screen.
    }

    public CheckinScreenHandler(int syncId, Inventory inventory) {
        this(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), null, null);
    }

    private CheckinScreenHandler(int syncId, Inventory inventory, SimpleContainer container, ServerPlayer owner,
                                 LocalDate openedDate) {
        super(TYPE, syncId, inventory, container, ROWS);
        this.checkinContainer = container;
        this.ownerId = owner == null ? null : owner.getUUID();
        this.owner = owner;
        this.openedDate = openedDate;
        if (owner != null) {
            refreshContents(owner, openedDate);
        }
    }

    public static CheckinScreenHandler createServer(int syncId, Inventory inventory, ServerPlayer owner) {
        LocalDate openedDate = today();
        return new CheckinScreenHandler(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), owner, openedDate);
    }

    public LocalDate getOpenedDate() {
        int syncedEpochDay = openedDayData.get();
        return syncedEpochDay > 0 ? LocalDate.ofEpochDay(syncedEpochDay) : (openedDate == null ? today() : openedDate);
    }

    public boolean hasSignedToday() {
        if (getSecondsUntilNextCheckin() == 0L) {
            return false;
        }
        int todaySlot = getOpenedDate().getDayOfMonth() - 1;
        return todaySlot >= 0 && todaySlot < DATE_SLOT_COUNT
                && checkinContainer.getItem(todaySlot).is(Items.ENCHANTED_BOOK);
    }

    public String getTimeUntilNextCheckin() {
        long remainingSeconds = getSecondsUntilNextCheckin();
        long hours = remainingSeconds / 3_600L;
        long minutes = (remainingSeconds % 3_600L) / 60L;
        long seconds = remainingSeconds % 60L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds);
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
            serverPlayer.displayClientMessage(Component.translatable(
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
                    Component.translatable("message.omnitools.success", result.stats().todayOrdinal()), true);
            ModMindEntry.rewardService().grant(serverPlayer, result);
            Component broadcastMessage = result.stats().todayOrdinal() == 1
                    ? Component.translatable("message.omnitools.broadcast.first", serverPlayer.getName())
                    : Component.translatable("message.omnitools.broadcast", serverPlayer.getName(),
                    result.stats().todayOrdinal());
            serverPlayer.level().getServer().getPlayerList().broadcastSystemMessage(broadcastMessage, false);
        } else {
            serverPlayer.displayClientMessage(Component.translatable("message.omnitools.already_signed"), true);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void setData(int id, int value) {
        super.setData(id, value);
        if (ownerId == null && id == NEXT_CHECKIN_SECONDS_DATA_SLOT) {
            clientCountdownDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(0, value));
        }
    }

    @Override
    public void broadcastChanges() {
        if (owner != null) {
            long tick = owner.level().getServer().getTickCount();
            if (lastCountdownUpdateTick == Long.MIN_VALUE || tick - lastCountdownUpdateTick >= 20L) {
                updateCountdown();
                lastCountdownUpdateTick = tick;
            }
        }
        super.broadcastChanges();
    }

    private void refreshContents(ServerPlayer owner, LocalDate date) {
        this.openedDate = date;
        updateCheckinDeadline(date);
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
                stack.set(DataComponents.CUSTOM_NAME, Component.translatable(
                        "gui.omnitools.day", day).withStyle(statusColor, ChatFormatting.BOLD));
                stack.set(DataComponents.LORE, new ItemLore(List.of(
                        Component.translatable("gui.omnitools.date", date.getMonthValue(), day)
                                .withStyle(ChatFormatting.GRAY),
                        Component.translatable(statusKey).withStyle(statusColor))));
            } else {
                stack = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
                stack.set(DataComponents.CUSTOM_NAME, Component.translatable("gui.omnitools.empty"));
            }
            checkinContainer.setItem(day - 1, stack);
        }

        for (int slot = DATE_SLOT_COUNT; slot < CONTAINER_SIZE; slot++) {
            if (slot != PROFILE_SLOT) {
                ItemStack filler = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
                filler.set(DataComponents.CUSTOM_NAME, Component.translatable("gui.omnitools.empty"));
                checkinContainer.setItem(slot, filler);
            }
        }

        ItemStack recordsButton = new ItemStack(Items.CLOCK);
        recordsButton.set(DataComponents.CUSTOM_NAME, Component.translatable("gui.omnitools.records.button")
                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        recordsButton.set(DataComponents.LORE, new ItemLore(List.of(
                Component.translatable("gui.omnitools.records.button_hint").withStyle(ChatFormatting.GRAY))));
        checkinContainer.setItem(RECORDS_SLOT, recordsButton);

        ItemStack achievementsButton = new ItemStack(Items.NETHER_STAR);
        achievementsButton.set(DataComponents.CUSTOM_NAME, Component.translatable("gui.omnitools.achievements.button")
                .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
        achievementsButton.set(DataComponents.LORE, new ItemLore(List.of(
                Component.translatable("gui.omnitools.achievements.button_hint").withStyle(ChatFormatting.GRAY))));
        checkinContainer.setItem(ACHIEVEMENTS_SLOT, achievementsButton);

        ItemStack profile = new ItemStack(Items.PLAYER_HEAD);
        profile.set(DataComponents.PROFILE, ResolvableProfile.createResolved(owner.getGameProfile()));
        profile.set(DataComponents.CUSTOM_NAME, Component.translatable("gui.omnitools.profile", owner.getName())
                .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
        String ordinalKey = stats.signedToday()
                ? "gui.omnitools.profile.ordinal"
                : "gui.omnitools.profile.ordinal_waiting";
        profile.set(DataComponents.LORE, new ItemLore(List.of(
                Component.translatable(ordinalKey, stats.todayOrdinal()).withStyle(ChatFormatting.GOLD),
                Component.translatable("gui.omnitools.profile.total", stats.totalDays())
                        .withStyle(ChatFormatting.AQUA),
                Component.translatable("gui.omnitools.profile.streak", stats.streakDays())
                        .withStyle(ChatFormatting.LIGHT_PURPLE),
                Component.translatable("gui.omnitools.profile.monthly", stats.monthlyDays())
                        .withStyle(ChatFormatting.YELLOW),
                Component.translatable("gui.omnitools.profile.balance", data.getBalance(owner.getUUID()))
                        .withStyle(ChatFormatting.GOLD),
                Component.translatable(stats.signedToday()
                        ? "gui.omnitools.status.signed"
                        : "gui.omnitools.status.today")
                        .withStyle(stats.signedToday() ? ChatFormatting.GREEN : ChatFormatting.YELLOW))));
        checkinContainer.setItem(PROFILE_SLOT, profile);
    }

    private static void openRecordsMenu(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> CheckinRecordsScreenHandler.createServer(syncId, inventory, player),
                Component.translatable("gui.omnitools.records.title")));
    }

    private static void openAchievementsMenu(ServerPlayer player) {
        if (!ModMindEntry.hasCommandPermission(player,
                dev.modmind.omnitools.permissions.CommandAction.ACHIEVEMENTS_OPEN)
                || !ModMindEntry.isModuleEnabled(dev.modmind.omnitools.config.ModuleId.ACHIEVEMENTS)) {
            player.displayClientMessage(Component.translatable("message.omnitools.permission_denied"), true);
            return;
        }
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> AchievementScreenHandler.createServer(syncId, inventory, player,
                        ModMindEntry.achievementService(), 0),
                Component.translatable("gui.omnitools.achievement.title")));
    }

    private static LocalDate today() {
        return CheckinData.today();
    }

    private void updateCheckinDeadline(LocalDate date) {
        openedDayData.set(Math.toIntExact(date.toEpochDay()));
        nextCheckinDeadlineMillis = date.plusDays(1).atStartOfDay(ModMindEntry.configuredZone()).toInstant().toEpochMilli();
        updateCountdown();
    }

    private void updateCountdown() {
        long remainingMillis = Math.max(0L, nextCheckinDeadlineMillis - System.currentTimeMillis());
        nextCheckinSeconds.set(Math.toIntExact((remainingMillis + 999L) / 1_000L));
    }

    private long getSecondsUntilNextCheckin() {
        if (ownerId != null || clientCountdownDeadlineNanos == 0L) {
            return Math.max(0L, nextCheckinSeconds.get());
        }
        long remainingNanos = clientCountdownDeadlineNanos - System.nanoTime();
        return Math.max(0L, (remainingNanos + TimeUnit.SECONDS.toNanos(1L) - 1L) / TimeUnit.SECONDS.toNanos(1L));
    }
}
