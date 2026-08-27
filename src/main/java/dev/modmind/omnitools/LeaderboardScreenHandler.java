package dev.modmind.omnitools;

import com.mojang.authlib.GameProfile;
import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.leaderboard.LeaderboardConfig;
import dev.modmind.omnitools.leaderboard.LeaderboardService;
import dev.modmind.omnitools.permissions.CommandAction;
import dev.modmind.omnitools.text.TextTemplateRenderer;
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
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Server-authoritative cached-statistics leaderboard browser. */
public final class LeaderboardScreenHandler extends ChestMenu {
    private static final int ROWS = 6;
    private static final int CONTAINER_SIZE = ROWS * 9;
    private static final int CONTENT_SLOTS = GuiSlots.CONTENT_SLOT_COUNT_54;
    private static final int PREVIOUS_SLOT = GuiSlots.FIRST_ACTION_SLOT_54;
    private static final int FOOTER_SLOT = GuiSlots.CENTER_54;
    private static final int NEXT_SLOT = GuiSlots.LAST_SLOT_54;
    private static final int HEADER_PROFILE_SLOT = GuiSlots.HEADER_LEFT_54;
    private static final int HEADER_TITLE_SLOT = GuiSlots.HEADER_CENTER_54;
    private static final int CLOSE_SLOT = GuiSlots.HEADER_CLOSE_54;

    private final SimpleContainer container;
    private final ServerPlayer owner;
    private final UUID ownerId;
    private final LeaderboardService service;
    private String boardId;
    private int page;
    private long lastRefreshTick = Long.MIN_VALUE;

    public LeaderboardScreenHandler(int syncId, Inventory inventory) {
        this(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), null, null, "", 0);
    }

    private LeaderboardScreenHandler(int syncId, Inventory inventory, SimpleContainer container, ServerPlayer owner,
                                     LeaderboardService service, String boardId, int page) {
        super(MenuType.GENERIC_9x6, syncId, inventory, container, ROWS);
        this.container = container;
        this.owner = owner;
        this.ownerId = owner == null ? null : owner.getUUID();
        this.service = service;
        this.boardId = boardId == null ? "" : boardId;
        this.page = Math.max(0, page);
        if (owner != null && service != null) {
            refreshContents();
        }
    }

    public static LeaderboardScreenHandler createServer(int syncId, Inventory inventory, ServerPlayer owner,
                                                        LeaderboardService service, String boardId, int page) {
        return new LeaderboardScreenHandler(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), owner, service,
                boardId, page);
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (!ModMindEntry.isModuleEnabled(ModuleId.LEADERBOARDS)
                || (player instanceof ServerPlayer serverPlayer
                && !ModMindEntry.hasCommandPermission(serverPlayer, CommandAction.LEADERBOARDS_OPEN))) {
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.closeContainer();
            }
            return;
        }
        if (slotId < 0 || slotId >= CONTAINER_SIZE) {
            super.clicked(slotId, button, clickType, player);
            return;
        }
        if (!(player instanceof ServerPlayer serverPlayer) || ownerId == null || !ownerId.equals(serverPlayer.getUUID())
                || clickType != ClickType.PICKUP) {
            return;
        }
        if (slotId == CLOSE_SLOT) {
            serverPlayer.closeContainer();
            GuiFeedbackService.click(serverPlayer);
            return;
        }
        if (slotId == FOOTER_SLOT && !boardId.isBlank()) {
            boardId = "";
            page = 0;
            refreshContents();
            GuiFeedbackService.click(serverPlayer);
            return;
        }
        int pageCount = pageCount();
        if (slotId == PREVIOUS_SLOT && page > 0) {
            page--;
            refreshContents();
            GuiFeedbackService.click(serverPlayer);
            return;
        }
        if (slotId == NEXT_SLOT && page + 1 < pageCount) {
            page++;
            refreshContents();
            GuiFeedbackService.click(serverPlayer);
            return;
        }
        int localIndex = GuiSlots.contentIndex54(slotId);
        if (localIndex < 0 || !boardId.isBlank()) {
            return;
        }
        List<LeaderboardService.BoardSnapshot> boards = service.boards();
        int index = page * CONTENT_SLOTS + localIndex;
        if (index < boards.size()) {
            boardId = boards.get(index).definition().id();
            page = 0;
            refreshContents();
            GuiFeedbackService.click(serverPlayer);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void broadcastChanges() {
        if (owner != null && service != null) {
            long tick = owner.level().getServer().getTickCount();
            if (tick - lastRefreshTick >= 20L) {
                refreshContents();
            }
        }
        super.broadcastChanges();
    }

    private void refreshContents() {
        GuiTheme.clear(container);
        LeaderboardService.BoardSnapshot board = currentBoard();
        if (board == null && !boardId.isBlank()) {
            boardId = "";
            page = 0;
        }
        if (board == null) {
            renderIndex();
        } else {
            renderBoard(board);
        }
        lastRefreshTick = owner.level().getServer().getTickCount();
    }

    private void renderIndex() {
        List<LeaderboardService.BoardSnapshot> boards = service.boards();
        page = clampPage(page, boards.size());
        container.setItem(HEADER_PROFILE_SLOT, ownerHead(ServerText.translatable("gui.omnitools.leaderboard.profile").getString()));
        container.setItem(HEADER_TITLE_SLOT, GuiTheme.status(Items.NETHER_STAR,
                ServerText.translatable("gui.omnitools.leaderboard.title"), ChatFormatting.AQUA,
                List.of(ServerText.translatable("gui.omnitools.leaderboard.configured", boards.size())
                        .withStyle(ChatFormatting.GRAY)), false));
        container.setItem(CLOSE_SLOT, GuiNavigationService.close());
        if (boards.isEmpty()) {
            container.setItem(22, GuiTheme.named(Items.BOOK, ServerText.translatable("gui.omnitools.leaderboard.empty")
                    .withStyle(ChatFormatting.GRAY), List.of(ServerText.translatable("gui.omnitools.leaderboard.empty_hint")
                    .withStyle(ChatFormatting.DARK_GRAY))));
        }
        int first = page * CONTENT_SLOTS;
        for (int index = 0; index < CONTENT_SLOTS && first + index < boards.size(); index++) {
            container.setItem(GuiSlots.contentSlot54(index), boardItem(boards.get(first + index)));
        }
        navigation(pageCount(), boards.size(), false, null);
    }

    private void renderBoard(LeaderboardService.BoardSnapshot board) {
        List<LeaderboardService.RankedEntry> entries = board.entries();
        page = clampPage(page, entries.size());
        LeaderboardConfig.LeaderboardDefinition definition = board.definition();
        container.setItem(HEADER_PROFILE_SLOT, ownerHead(ServerText.translatable("gui.omnitools.leaderboard.my_ranking").getString()));
        container.setItem(HEADER_TITLE_SLOT, GuiTheme.status(definition.icon(),
                TextTemplateRenderer.render(owner, definition.display()), ChatFormatting.AQUA,
                definition.description().isBlank() ? List.of() : List.of(TextTemplateRenderer.render(owner,
                        definition.description()).copy().withStyle(ChatFormatting.GRAY)), false));
        container.setItem(CLOSE_SLOT, GuiNavigationService.close());
        if (entries.isEmpty()) {
            container.setItem(22, GuiTheme.named(Items.BOOK, ServerText.translatable("gui.omnitools.leaderboard.ranking_empty")
                    .withStyle(ChatFormatting.GRAY), List.of(ServerText.translatable("gui.omnitools.leaderboard.ranking_empty_hint")
                    .withStyle(ChatFormatting.DARK_GRAY))));
        }
        int first = page * CONTENT_SLOTS;
        for (int index = 0; index < CONTENT_SLOTS && first + index < entries.size(); index++) {
            container.setItem(GuiSlots.contentSlot54(index), rankingItem(board, entries.get(first + index)));
        }
        LeaderboardService.RankedEntry mine = board.entry(ownerId).orElse(null);
        List<Component> lore = mine == null
                ? List.of(ServerText.translatable("gui.omnitools.leaderboard.my_rank", "-").withStyle(ChatFormatting.GRAY),
                ServerText.translatable("gui.omnitools.leaderboard.my_score", "0").withStyle(ChatFormatting.GRAY))
                : List.of(ServerText.translatable("gui.omnitools.leaderboard.my_rank", "#" + mine.rank()).withStyle(ChatFormatting.GOLD),
                ServerText.translatable("gui.omnitools.leaderboard.my_score", board.format(mine.value())).withStyle(ChatFormatting.AQUA));
        container.setItem(FOOTER_SLOT, GuiTheme.named(Items.COMPASS, ServerText.translatable("gui.omnitools.leaderboard.all")
                .withStyle(ChatFormatting.YELLOW), lore));
        navigation(pageCount(), entries.size(), true, board);
    }

    private void navigation(int pageCount, int count, boolean keepFooter, LeaderboardService.BoardSnapshot board) {
        if (page > 0) {
            container.setItem(PREVIOUS_SLOT, GuiNavigationService.previous());
        }
        if (!keepFooter) {
            container.setItem(FOOTER_SLOT, GuiNavigationService.page(page + 1, pageCount, count));
        }
        if (page + 1 < pageCount) {
            container.setItem(NEXT_SLOT, GuiNavigationService.next());
        }
    }

    private ItemStack boardItem(LeaderboardService.BoardSnapshot board) {
        LeaderboardConfig.LeaderboardDefinition definition = board.definition();
        List<Component> lore = new ArrayList<>();
        if (!definition.description().isBlank()) {
            lore.add(TextTemplateRenderer.render(owner, definition.description()).copy().withStyle(ChatFormatting.GRAY));
        }
        lore.add(ServerText.translatable("gui.omnitools.leaderboard.ranked_players", board.entries().size())
                .withStyle(ChatFormatting.DARK_GRAY));
        if (!definition.linkedAchievement().isBlank()) {
            lore.add(ServerText.translatable("gui.omnitools.leaderboard.linked_achievement", definition.linkedAchievement())
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        return GuiTheme.status(definition.icon(), TextTemplateRenderer.render(owner, definition.display()),
                ChatFormatting.AQUA, lore, false);
    }

    private ItemStack rankingItem(LeaderboardService.BoardSnapshot board, LeaderboardService.RankedEntry entry) {
        ItemStack item = new ItemStack(Items.PLAYER_HEAD);
        item.set(DataComponents.PROFILE, ResolvableProfile.createResolved(new GameProfile(entry.playerId(), entry.playerName())));
        item.set(DataComponents.CUSTOM_NAME, Component.literal("#" + entry.rank() + " " + entry.playerName())
                .withStyle(ChatFormatting.AQUA));
        item.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(board.format(entry.value()))
                .withStyle(ChatFormatting.GOLD))));
        return item;
    }

    private ItemStack ownerHead(String name) {
        ItemStack item = new ItemStack(Items.PLAYER_HEAD);
        item.set(DataComponents.PROFILE, ResolvableProfile.createResolved(owner.getGameProfile()));
        item.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(ChatFormatting.GOLD));
        return item;
    }

    private LeaderboardService.BoardSnapshot currentBoard() {
        return boardId.isBlank() ? null : service.board(boardId).orElse(null);
    }

    private int pageCount() {
        LeaderboardService.BoardSnapshot board = currentBoard();
        return pageCount(board == null ? service.boards().size() : board.entries().size());
    }

    private static int pageCount(int count) {
        return Math.max(1, (count + CONTENT_SLOTS - 1) / CONTENT_SLOTS);
    }

    private static int clampPage(int page, int count) {
        return Math.max(0, Math.min(page, pageCount(count) - 1));
    }
}
