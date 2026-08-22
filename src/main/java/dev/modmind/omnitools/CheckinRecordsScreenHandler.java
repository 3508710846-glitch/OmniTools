package dev.modmind.omnitools;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.flag.FeatureFlags;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

public final class CheckinRecordsScreenHandler extends ChestMenu {
    public static final int ROWS = 6;
    public static final int CONTAINER_SIZE = ROWS * 9;
    public static final int RECORD_SLOT_COUNT = 5 * 9;
    public static final int BACK_SLOT = 45;
    public static final int PREVIOUS_PAGE_SLOT = 47;
    public static final int PAGE_INFO_SLOT = 49;
    public static final int NEXT_PAGE_SLOT = 51;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static final MenuType<CheckinRecordsScreenHandler> TYPE = Registry.register(
            BuiltInRegistries.MENU,
            Identifier.fromNamespaceAndPath(ModMindEntry.MOD_ID, "checkin_records"),
            new MenuType<>(CheckinRecordsScreenHandler::new, FeatureFlags.DEFAULT_FLAGS));

    private final SimpleContainer recordsContainer;
    private final UUID ownerId;
    private LocalDate openedDate;
    private int page;
    private int pageCount = 1;

    public static void register() {
        // Loading this class registers TYPE before the client creates its screen.
    }

    public CheckinRecordsScreenHandler(int syncId, Inventory inventory) {
        this(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), null, null, 0);
    }

    private CheckinRecordsScreenHandler(int syncId, Inventory inventory, SimpleContainer container,
                                        ServerPlayer owner, LocalDate openedDate, int page) {
        super(TYPE, syncId, inventory, container, ROWS);
        this.recordsContainer = container;
        this.ownerId = owner == null ? null : owner.getUUID();
        this.openedDate = openedDate;
        this.page = Math.max(page, 0);
        if (owner != null) {
            refreshContents(owner, openedDate);
        }
    }

    public static CheckinRecordsScreenHandler createServer(int syncId, Inventory inventory, ServerPlayer owner) {
        return createServer(syncId, inventory, owner, 0);
    }

    public static CheckinRecordsScreenHandler createServer(int syncId, Inventory inventory, ServerPlayer owner,
                                                            int page) {
        return new CheckinRecordsScreenHandler(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), owner,
                CheckinData.today(), page);
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (slotId < 0 || slotId >= CONTAINER_SIZE) {
            super.clicked(slotId, button, clickType, player);
            return;
        }

        if (!(player instanceof ServerPlayer serverPlayer) || ownerId == null
                || !ownerId.equals(serverPlayer.getUUID()) || clickType != ClickType.PICKUP) {
            return;
        }

        if (slotId == BACK_SLOT) {
            ModMindEntry.openCheckinMenu(serverPlayer);
        } else if (slotId == PREVIOUS_PAGE_SLOT && page > 0) {
            openPage(serverPlayer, page - 1);
        } else if (slotId == NEXT_PAGE_SLOT && page + 1 < pageCount) {
            openPage(serverPlayer, page + 1);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    private void refreshContents(ServerPlayer owner, LocalDate date) {
        this.openedDate = date;
        List<CheckinData.DailySignInRecord> records = CheckinData.get(owner)
                .getDailyRecords(date.toEpochDay());
        this.pageCount = Math.max(1, (records.size() + RECORD_SLOT_COUNT - 1) / RECORD_SLOT_COUNT);
        this.page = Math.min(page, pageCount - 1);

        for (int slot = 0; slot < CONTAINER_SIZE; slot++) {
            recordsContainer.setItem(slot, emptySlot());
        }

        int firstRecord = page * RECORD_SLOT_COUNT;
        int visibleRecords = Math.min(RECORD_SLOT_COUNT, records.size() - firstRecord);
        for (int index = 0; index < visibleRecords; index++) {
            CheckinData.DailySignInRecord record = records.get(firstRecord + index);
            recordsContainer.setItem(index, createPlayerHead(owner, record, firstRecord + index + 1));
        }

        recordsContainer.setItem(BACK_SLOT, namedItem(
                Items.ARROW,
                Component.translatable("gui.omnitools.records.back").withStyle(ChatFormatting.GOLD),
                List.of(Component.translatable("gui.omnitools.records.back_hint").withStyle(ChatFormatting.GRAY))));
        if (page > 0) {
            recordsContainer.setItem(PREVIOUS_PAGE_SLOT, namedItem(
                    Items.ARROW,
                    Component.translatable("gui.omnitools.records.previous").withStyle(ChatFormatting.AQUA),
                    List.of(Component.translatable("gui.omnitools.records.previous_hint")
                            .withStyle(ChatFormatting.GRAY))));
        }
        recordsContainer.setItem(PAGE_INFO_SLOT, namedItem(
                Items.PAPER,
                Component.translatable("gui.omnitools.records.page", page + 1, pageCount)
                        .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD),
                List.of(Component.translatable("gui.omnitools.records.total", records.size())
                        .withStyle(ChatFormatting.GRAY))));
        if (page + 1 < pageCount) {
            recordsContainer.setItem(NEXT_PAGE_SLOT, namedItem(
                    Items.ARROW,
                    Component.translatable("gui.omnitools.records.next").withStyle(ChatFormatting.AQUA),
                    List.of(Component.translatable("gui.omnitools.records.next_hint")
                            .withStyle(ChatFormatting.GRAY))));
        }
    }

    private ItemStack createPlayerHead(ServerPlayer viewer, CheckinData.DailySignInRecord record, int displayRank) {
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        ServerPlayer recordedPlayer = viewer.level().getServer().getPlayerList().getPlayer(record.playerId());
        if (recordedPlayer != null) {
            head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(recordedPlayer.getGameProfile()));
        } else {
            head.set(DataComponents.PROFILE, ResolvableProfile.createUnresolved(record.playerId()));
        }

        String playerName = record.playerName();
        if (playerName == null || playerName.isBlank()) {
            playerName = recordedPlayer == null ? record.playerId().toString() : recordedPlayer.getGameProfile().name();
        }
        head.set(DataComponents.CUSTOM_NAME, Component.literal(playerName)
                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));

        int rank = record.ordinal() > 0 ? record.ordinal() : displayRank;
        ChatFormatting rankColor = rank == 1 ? ChatFormatting.GOLD
                : rank == 2 ? ChatFormatting.GRAY
                : rank == 3 ? ChatFormatting.DARK_AQUA
                : ChatFormatting.YELLOW;
        Component time = (record.signedAt() > 0L
                ? Component.translatable("gui.omnitools.record.time", formatTime(record.signedAt()))
                : Component.translatable("gui.omnitools.record.time_unknown"))
                .withStyle(ChatFormatting.GRAY);
        head.set(DataComponents.LORE, new ItemLore(List.of(
                Component.translatable("gui.omnitools.record.rank", rank).withStyle(rankColor, ChatFormatting.BOLD),
                time)));
        return head;
    }

    private void openPage(ServerPlayer player, int targetPage) {
        player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                (syncId, inventory, ignored) -> createServer(syncId, inventory, player, targetPage),
                Component.translatable("gui.omnitools.records.title")));
    }

    private static ItemStack emptySlot() {
        return namedItem(Items.GRAY_STAINED_GLASS_PANE,
                Component.translatable("gui.omnitools.empty").withStyle(ChatFormatting.DARK_GRAY), List.of());
    }

    private static ItemStack namedItem(net.minecraft.world.item.Item item, Component name,
                                       List<Component> lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, name);
        if (!lore.isEmpty()) {
            stack.set(DataComponents.LORE, new ItemLore(lore));
        }
        return stack;
    }

    private static String formatTime(long signedAt) {
        return TIME_FORMAT.format(Instant.ofEpochMilli(signedAt).atZone(ModMindEntry.configuredZone()));
    }
}
