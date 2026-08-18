package dev.modmind.qiandao;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.flag.FeatureFlags;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

public final class CheckinScreenHandler extends ChestMenu {
    public static final int ROWS = 5;
    public static final int CONTAINER_SIZE = ROWS * 9;
    public static final int DATE_SLOT_COUNT = 4 * 9;
    public static final int PROFILE_SLOT = 4 * 9 + 4;
    public static final MenuType<CheckinScreenHandler> TYPE = Registry.register(
            BuiltInRegistries.MENU,
            Identifier.fromNamespaceAndPath(ModMindEntry.MOD_ID, "checkin"),
            new MenuType<>(CheckinScreenHandler::new, FeatureFlags.DEFAULT_FLAGS));

    private final SimpleContainer checkinContainer;
    private final UUID ownerId;
    private LocalDate openedDate;

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
        this.openedDate = openedDate;
        if (owner != null) {
            refreshContents(owner, openedDate);
        }
    }

    public static CheckinScreenHandler createServer(int syncId, Inventory inventory, ServerPlayer owner) {
        LocalDate openedDate = today();
        return new CheckinScreenHandler(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), owner, openedDate);
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
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
        if (slotId >= DATE_SLOT_COUNT || clickType != ClickType.PICKUP) {
            return;
        }

        LocalDate currentDate = today();
        int expectedSlot = currentDate.getDayOfMonth() - 1;
        boolean dateChanged = openedDate == null || !currentDate.equals(openedDate);
        if (dateChanged || slotId != expectedSlot) {
            serverPlayer.displayClientMessage(Component.translatable(
                    dateChanged ? "message.qiandao.invalid_date" : "message.qiandao.only_today"), true);
            if (dateChanged) {
                refreshContents(serverPlayer, currentDate);
                broadcastChanges();
            }
            return;
        }

        CheckinData data = CheckinData.get(serverPlayer);
        CheckinData.SignInResult result = data.signIn(serverPlayer.getUUID(), currentDate.toEpochDay());
        refreshContents(serverPlayer, currentDate);
        broadcastChanges();
        if (result.newlySigned()) {
            serverPlayer.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            serverPlayer.displayClientMessage(
                    Component.translatable("message.qiandao.success", result.stats().todayOrdinal()), true);
            Component broadcastMessage = result.stats().todayOrdinal() == 1
                    ? Component.translatable("message.qiandao.broadcast.first", serverPlayer.getName())
                    : Component.translatable("message.qiandao.broadcast", serverPlayer.getName(),
                    result.stats().todayOrdinal());
            serverPlayer.level().getServer().getPlayerList().broadcastSystemMessage(broadcastMessage, false);
        } else {
            serverPlayer.displayClientMessage(Component.translatable("message.qiandao.already_signed"), true);
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
                stack = new ItemStack(signed ? Items.ENCHANTED_BOOK : Items.BOOK);
                if (signed) {
                    stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
                }
                stack.set(DataComponents.CUSTOM_NAME, Component.translatable(
                        "gui.qiandao.day", day).withStyle(signed ? ChatFormatting.GREEN : ChatFormatting.WHITE));
                String statusKey;
                if (day == date.getDayOfMonth()) {
                    statusKey = signed ? "gui.qiandao.status.signed" : "gui.qiandao.status.today";
                } else if (day < date.getDayOfMonth()) {
                    statusKey = signed ? "gui.qiandao.status.signed" : "gui.qiandao.status.missed";
                } else {
                    statusKey = "gui.qiandao.status.future";
                }
                stack.set(DataComponents.LORE, new ItemLore(List.of(
                        Component.translatable("gui.qiandao.date", date.getMonthValue(), day),
                        Component.translatable(statusKey))));
            } else {
                stack = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
                stack.set(DataComponents.CUSTOM_NAME, Component.translatable("gui.qiandao.empty"));
            }
            checkinContainer.setItem(day - 1, stack);
        }

        for (int slot = DATE_SLOT_COUNT; slot < CONTAINER_SIZE; slot++) {
            if (slot != PROFILE_SLOT) {
                ItemStack filler = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
                filler.set(DataComponents.CUSTOM_NAME, Component.translatable("gui.qiandao.empty"));
                checkinContainer.setItem(slot, filler);
            }
        }

        ItemStack profile = new ItemStack(Items.PLAYER_HEAD);
        profile.set(DataComponents.PROFILE, ResolvableProfile.createResolved(owner.getGameProfile()));
        profile.set(DataComponents.CUSTOM_NAME, Component.translatable("gui.qiandao.profile", owner.getName()));
        String ordinalKey = stats.signedToday()
                ? "gui.qiandao.profile.ordinal"
                : "gui.qiandao.profile.ordinal_waiting";
        profile.set(DataComponents.LORE, new ItemLore(List.of(
                Component.translatable(ordinalKey, stats.todayOrdinal()),
                Component.translatable("gui.qiandao.profile.total", stats.totalDays()),
                Component.translatable("gui.qiandao.profile.streak", stats.streakDays()),
                Component.translatable(stats.signedToday()
                        ? "gui.qiandao.status.signed"
                        : "gui.qiandao.status.today"))));
        checkinContainer.setItem(PROFILE_SLOT, profile);
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneId.systemDefault());
    }
}
