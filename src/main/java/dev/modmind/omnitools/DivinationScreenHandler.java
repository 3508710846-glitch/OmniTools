package dev.modmind.omnitools;

import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.diagnostics.ModuleFaultBoundary;
import dev.modmind.omnitools.divination.DivinationConfig;
import dev.modmind.omnitools.divination.DivinationData;
import dev.modmind.omnitools.divination.DivinationService;
import dev.modmind.omnitools.permissions.CommandAction;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
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
import net.minecraft.core.particles.ParticleTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Compact daily-sign desk: draw, reveal, reroll and history without theme or relief submenus. */
public final class DivinationScreenHandler extends ChestMenu {
    private static final int ROWS = 6;
    private static final int CONTAINER_SIZE = ROWS * 9;
    private static final int CLOSE_SLOT = 8;
    private static final int BOTTOM_CLOSE_SLOT = 49;
    private static final int RESULT_SLOT = 22;
    private static final int DRAW_SLOT = 29;
    private static final int INTERPRET_SLOT = 31;
    private static final int REROLL_SLOT = 33;
    private static final int HISTORY_SLOT = 35;
    private static final int HISTORY_BACK_SLOT = 45;

    private final SimpleContainer container;
    private final ServerPlayer owner;
    private final UUID ownerId;
    private View view = View.HOME;
    private long lastRevision = Long.MIN_VALUE;
    private int animationTicks;
    private int animationFrame;

    public DivinationScreenHandler(int syncId, Inventory inventory) {
        this(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), null);
    }

    private DivinationScreenHandler(int syncId, Inventory inventory, SimpleContainer container, ServerPlayer owner) {
        super(MenuType.GENERIC_9x6, syncId, inventory, container, ROWS);
        this.container = container;
        this.owner = owner;
        this.ownerId = owner == null ? null : owner.getUUID();
        if (owner != null) refreshContents();
    }

    public static DivinationScreenHandler createServer(int syncId, Inventory inventory, ServerPlayer owner) {
        return new DivinationScreenHandler(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), owner);
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            ModuleFaultBoundary.runPlayerAction(ModuleId.DIVINATION, "menu_click", serverPlayer,
                    "daily_sign_and_wallet_operation_retained", () -> handleClick(slotId, button, clickType, player));
            return;
        }
        handleClick(slotId, button, clickType, player);
    }

    private void handleClick(int slotId, int button, ClickType clickType, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer) || ownerId == null || !ownerId.equals(serverPlayer.getUUID())) return;
        if (!ModMindEntry.isModuleEnabled(ModuleId.DIVINATION)
                || !ModMindEntry.hasCommandPermission(serverPlayer, CommandAction.DIVINATION_OPEN)) {
            serverPlayer.closeContainer();
            return;
        }
        if (slotId < 0 || slotId >= CONTAINER_SIZE) {
            super.clicked(slotId, button, clickType, player);
            return;
        }
        if (clickType != ClickType.PICKUP || button != 0) return;
        if (slotId == CLOSE_SLOT || slotId == BOTTOM_CLOSE_SLOT) {
            serverPlayer.closeContainer();
            click(serverPlayer);
            return;
        }
        if (animationTicks > 0) return;
        if (view == View.HISTORY) {
            if (slotId == HISTORY_BACK_SLOT) {
                view = View.HOME;
                refreshContents();
                click(serverPlayer);
            }
            return;
        }
        if (slotId == DRAW_SLOT) {
            handleDrawOutcome(serverPlayer, ModMindEntry.divinationService().draw(serverPlayer));
            refreshContents();
            return;
        }
        if (slotId == INTERPRET_SLOT) {
            handleInterpretOutcome(serverPlayer, ModMindEntry.divinationService().interpret(serverPlayer, false, null));
            refreshContents();
            return;
        }
        if (slotId == REROLL_SLOT) {
            handleDrawOutcome(serverPlayer, ModMindEntry.divinationService().reroll(serverPlayer));
            refreshContents();
            return;
        }
        if (slotId == HISTORY_SLOT) {
            view = View.HISTORY;
            refreshContents();
            click(serverPlayer);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        return ItemStack.EMPTY;
    }

    @Override
    public void removed(Player player) {
        stopAnimation();
        super.removed(player);
    }

    /** Called from the server tick; all animation work stays on the main thread. */
    public static void tickAnimations(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof DivinationScreenHandler menu) {
                menu.tickAnimation(player);
            }
        }
    }

    @Override
    public void broadcastChanges() {
        if (owner != null) {
            if (!ModMindEntry.isModuleEnabled(ModuleId.DIVINATION)
                    || !ModMindEntry.hasCommandPermission(owner, CommandAction.DIVINATION_OPEN)) {
                owner.closeContainer();
                return;
            }
            if (lastRevision != ModMindEntry.configSnapshot().revision()) refreshContents();
        }
        super.broadcastChanges();
    }

    private void refreshContents() {
        GuiTheme.clear(container);
        paintStarChart();
        DivinationData.Reading reading = ModMindEntry.divinationService().current(owner);
        container.setItem(0, decorative(Items.AMETHYST_SHARD, "✦", ChatFormatting.LIGHT_PURPLE));
        container.setItem(4, titleStack(reading));
        container.setItem(CLOSE_SLOT, GuiNavigationService.close());
        container.setItem(BOTTOM_CLOSE_SLOT, GuiNavigationService.close());
        if (view == View.HISTORY) renderHistory(); else renderHome(reading);
        lastRevision = ModMindEntry.configSnapshot().revision();
    }

    private void renderHome(DivinationData.Reading reading) {
        boolean pending = reading != null && !reading.resolved();
        boolean rerollable = reading != null && reading.resolved()
                && ModMindEntry.divinationService().config().settings().rerollsEnabled();
        container.setItem(12, statusSummary(reading));
        container.setItem(RESULT_SLOT, animationTicks > 0 ? animationItem() : readingItem(reading));
        container.setItem(28, decorative(Items.LIME_DYE, "宜", ChatFormatting.GREEN));
        container.setItem(30, decorative(Items.ORANGE_DYE, "忌", ChatFormatting.RED));
        container.setItem(32, decorative(Items.SPYGLASS, "✧", ChatFormatting.AQUA));
        container.setItem(34, rewardSummary(reading));
        boolean locked = animationTicks > 0;
        container.setItem(DRAW_SLOT, GuiTheme.status(locked ? Items.CLOCK : Items.BLAZE_POWDER,
                ServerText.translatable("gui.omnitools.divination.draw"),
                locked ? ChatFormatting.YELLOW : reading == null ? ChatFormatting.GOLD : ChatFormatting.GRAY,
                List.of(ServerText.translatable(locked ? "gui.omnitools.divination.today"
                        : reading == null ? "gui.omnitools.divination.draw_hint"
                        : "gui.omnitools.divination.draw_unavailable").withStyle(locked ? ChatFormatting.YELLOW : ChatFormatting.GRAY)), reading == null && !locked));
        container.setItem(INTERPRET_SLOT, GuiTheme.status(locked ? Items.CLOCK : Items.WRITABLE_BOOK,
                ServerText.translatable("gui.omnitools.divination.interpret"),
                locked ? ChatFormatting.YELLOW : pending ? ChatFormatting.AQUA : ChatFormatting.GRAY,
                List.of(ServerText.translatable(locked ? "gui.omnitools.divination.today"
                        : pending ? "gui.omnitools.divination.interpret_hint"
                        : "gui.omnitools.divination.interpret_unavailable").withStyle(locked ? ChatFormatting.YELLOW : ChatFormatting.GRAY)), pending && !locked));
        long cost = ModMindEntry.divinationService().config().settings().rerollCost();
        container.setItem(REROLL_SLOT, GuiTheme.status(locked ? Items.CLOCK : Items.GOLD_INGOT,
                ServerText.translatable("gui.omnitools.divination.reroll"),
                locked ? ChatFormatting.YELLOW : rerollable ? ChatFormatting.GOLD : ChatFormatting.GRAY,
                List.of(ServerText.translatable(locked ? "gui.omnitools.divination.today"
                        : rerollable ? "gui.omnitools.divination.reroll_hint"
                        : "gui.omnitools.divination.reroll_unavailable", cost).withStyle(locked ? ChatFormatting.YELLOW : ChatFormatting.GRAY)), rerollable && !locked));
        container.setItem(HISTORY_SLOT, GuiTheme.navigation(Items.BOOK,
                ServerText.translatable("gui.omnitools.divination.history"),
                ServerText.translatable("gui.omnitools.divination.history_hint")));
    }

    private void renderHistory() {
        List<DivinationData.Reading> readings = ModMindEntry.divinationService().history(owner, 30);
        if (readings.isEmpty()) {
            container.setItem(22, GuiTheme.named(Items.PAPER, ServerText.translatable("gui.omnitools.divination.history_empty"), List.of()));
        } else {
            int slot = 9;
            for (DivinationData.Reading reading : readings) {
                if (slot >= 45) break;
                container.setItem(slot++, historyItem(reading));
            }
        }
        container.setItem(HISTORY_BACK_SLOT, GuiTheme.navigation(Items.ARROW,
                ServerText.translatable("gui.omnitools.divination.back"),
                ServerText.translatable("gui.omnitools.divination.back_hint")));
    }

    private void paintStarChart() {
        int[] outer = {1, 2, 3, 5, 6, 7, 9, 17, 18, 26, 27, 35, 36, 44, 45, 46, 47, 48, 50, 51, 52, 53};
        for (int slot : outer) container.setItem(slot, decorative(Items.BLUE_STAINED_GLASS_PANE, "✦", ChatFormatting.DARK_BLUE));
        int[] inner = {10, 11, 13, 14, 15, 16, 19, 20, 21, 23, 24, 25, 28, 30, 32, 34, 37, 38, 39, 40, 41, 42, 43};
        for (int slot : inner) container.setItem(slot, decorative(Items.PURPLE_STAINED_GLASS_PANE, "✧", ChatFormatting.LIGHT_PURPLE));
        container.setItem(14, decorative(Items.CYAN_STAINED_GLASS_PANE, "星", ChatFormatting.AQUA));
        container.setItem(20, decorative(Items.LIGHT_BLUE_STAINED_GLASS_PANE, "·", ChatFormatting.BLUE));
        container.setItem(24, decorative(Items.LIGHT_BLUE_STAINED_GLASS_PANE, "·", ChatFormatting.BLUE));
    }

    private ItemStack titleStack(DivinationData.Reading reading) {
        Component title = Component.literal("✦ ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal("天").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(Component.literal("机").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD))
                .append(Component.literal(" 占 ").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
                .append(Component.literal("卜").withStyle(ChatFormatting.BLUE, ChatFormatting.BOLD))
                .append(Component.literal(" ✦").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        ItemStack stack = new ItemStack(Items.ENCHANTED_BOOK);
        stack.set(DataComponents.CUSTOM_NAME, title);
        List<Component> lore = new ArrayList<>();
        lore.add(ServerText.translatable("gui.omnitools.divination.daily_status",
                reading == null ? ServerText.translatable("gui.omnitools.divination.none") : rankName(reading.rank()))
                .withStyle(reading == null ? ChatFormatting.GRAY : rankColor(reading.rank())));
        long balance = CheckinData.get(owner).getBalance(owner.getUUID());
        lore.add(ServerText.translatable("gui.omnitools.divination.wallet_balance", balance).withStyle(ChatFormatting.GOLD));
        stack.set(DataComponents.LORE, new ItemLore(GuiTextService.compactLore(lore, 3)));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, reading != null && reading.active());
        return stack;
    }

    private ItemStack statusSummary(DivinationData.Reading reading) {
        if (reading == null) return decorative(Items.CLOCK, "今日", ChatFormatting.GRAY);
        List<Component> lore = new ArrayList<>();
        lore.add(ServerText.translatable("gui.omnitools.divination.daily_status", rankName(reading.rank()))
                .withStyle(rankColor(reading.rank())));
        lore.add(ServerText.translatable(reading.resolved() ? "gui.omnitools.divination.history_resolved"
                : "gui.omnitools.divination.awaiting_interpretation").withStyle(ChatFormatting.AQUA));
        return detailedStatus(Items.CLOCK, ServerText.translatable("gui.omnitools.divination.today"),
                reading.resolved() ? rankColor(reading.rank()) : ChatFormatting.YELLOW, lore, reading.active());
    }

    private ItemStack rewardSummary(DivinationData.Reading reading) {
        if (reading == null || !reading.resolved()) return decorative(Items.BEACON, "奖励", ChatFormatting.GRAY);
        List<Component> lore = new ArrayList<>();
        if (reading.currencyReward() > 0L) lore.add(ServerText.translatable("gui.omnitools.divination.currency_reward",
                reading.currencyReward()).withStyle(ChatFormatting.GOLD));
        long modifier = Math.round(reading.activeBonus(ModMindEntry.divinationService().config().settings().omenBuffCap()) * 100.0D);
        if (modifier != 0L) lore.add(ServerText.translatable(modifier > 0L ? "gui.omnitools.divination.xp_bonus"
                : "gui.omnitools.divination.xp_penalty", Math.abs(modifier)).withStyle(
                modifier > 0L ? ChatFormatting.GREEN : ChatFormatting.RED));
        return detailedStatus(Items.BEACON, ServerText.translatable("gui.omnitools.divination.currency_reward",
                reading.currencyReward()), modifier < 0L ? ChatFormatting.RED : ChatFormatting.GOLD, lore, modifier > 0L);
    }

    private static ItemStack decorative(Item item, String label, ChatFormatting color) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(label).withStyle(color, ChatFormatting.BOLD));
        return stack;
    }

    private ItemStack readingItem(DivinationData.Reading reading) {
        if (reading == null) {
            return GuiTheme.named(Items.PAPER, ServerText.translatable("gui.omnitools.divination.none"),
                    List.of(ServerText.translatable("gui.omnitools.divination.none_hint").withStyle(ChatFormatting.GRAY)));
        }
        if (!reading.resolved()) {
            return GuiTheme.status(Items.PAPER, ServerText.translatable("gui.omnitools.divination.drawn_sign"),
                    ChatFormatting.LIGHT_PURPLE, List.of(
                            ServerText.translatable("gui.omnitools.divination.awaiting_interpretation").withStyle(ChatFormatting.YELLOW),
                            ServerText.translatable("gui.omnitools.divination.awaiting_hint").withStyle(ChatFormatting.GRAY)), false);
        }
        DivinationConfig.SignDefinition sign = ModMindEntry.divinationService().config().sign(reading.signId());
        List<Component> lore = new ArrayList<>();
        if (sign != null) {
            ChatFormatting[] poemColors = {ChatFormatting.GOLD, ChatFormatting.LIGHT_PURPLE, ChatFormatting.AQUA, ChatFormatting.BLUE};
            for (int index = 0; index < sign.poem().size(); index++) {
                lore.add(Component.literal("✦ " + sign.poem().get(index)).withStyle(poemColors[index % poemColors.length]));
            }
            lore.add(ServerText.translatable("gui.omnitools.divination.meaning", sign.interpretation()).withStyle(ChatFormatting.LIGHT_PURPLE));
            lore.add(ServerText.translatable("gui.omnitools.divination.favorable", sign.favorable()).withStyle(ChatFormatting.GREEN));
            lore.add(ServerText.translatable("gui.omnitools.divination.avoid", sign.avoid()).withStyle(ChatFormatting.RED));
            lore.add(ServerText.translatable("gui.omnitools.divination.advice", sign.advice()).withStyle(ChatFormatting.AQUA));
        }
        long modifier = Math.round(reading.activeBonus(ModMindEntry.divinationService().config().settings().omenBuffCap()) * 100.0D);
        if (modifier != 0L) {
            lore.add(ServerText.translatable(modifier > 0L ? "gui.omnitools.divination.xp_bonus"
                    : "gui.omnitools.divination.xp_penalty", Math.abs(modifier)).withStyle(
                    modifier > 0L ? ChatFormatting.GREEN : ChatFormatting.RED));
        }
        if (reading.currencyReward() > 0L) {
            lore.add(ServerText.translatable("gui.omnitools.divination.currency_reward", reading.currencyReward())
                    .withStyle(ChatFormatting.GOLD));
        }
        return detailedStatus(rankIcon(reading.rank()), rankName(reading.rank()), rankColor(reading.rank()), lore, true);
    }

    private ItemStack historyItem(DivinationData.Reading reading) {
        return GuiTheme.status(rankIcon(reading.rank()), rankName(reading.rank()), rankColor(reading.rank()),
                List.of(ServerText.translatable(reading.resolved() ? "gui.omnitools.divination.history_resolved"
                        : "gui.omnitools.divination.awaiting_interpretation").withStyle(ChatFormatting.GRAY)), false);
    }

    private static ItemStack detailedStatus(Item item, Component name, ChatFormatting color, List<Component> lore, boolean glint) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, name.copy().withStyle(color, ChatFormatting.BOLD));
        List<Component> compact = GuiTextService.compactLore(lore, 12);
        if (!compact.isEmpty()) stack.set(DataComponents.LORE, new ItemLore(compact));
        if (glint) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return stack;
    }

    private void handleDrawOutcome(ServerPlayer player, DivinationService.DrawOutcome outcome) {
        if (outcome.changed()) {
            beginAnimation(outcome.reading());
            player.displayClientMessage(ServerText.translatable(outcome.paid()
                    ? "message.omnitools.divination.rerolled" : "message.omnitools.divination.drawn"), true);
            player.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.1f);
        } else {
            String key = outcome.status() == DivinationData.DrawStatus.INSUFFICIENT_CURRENCY
                    ? "message.omnitools.divination.insufficient_currency" : "message.omnitools.divination.draw_failed";
            player.displayClientMessage(ServerText.translatable(key), true);
            player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 0.6f);
        }
    }

    private void handleInterpretOutcome(ServerPlayer player, DivinationService.InterpretOutcome outcome) {
        if (outcome.changed()) {
            player.displayClientMessage(ServerText.translatable("message.omnitools.divination.interpreted",
                    outcome.currencyReward()), true);
            sendReadingDetails(player, outcome.reading());
            player.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.0f);
        } else {
            player.displayClientMessage(ServerText.translatable("message.omnitools.divination.interpret_failed"), true);
            player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 0.6f);
        }
    }

    private static void sendReadingDetails(ServerPlayer player, DivinationData.Reading reading) {
        if (reading == null) return;
        DivinationConfig.SignDefinition sign = ModMindEntry.divinationService().config().sign(reading.signId());
        if (sign == null) return;
        player.sendSystemMessage(Component.literal("✦ " + rankName(reading.rank()).getString() + " · 签诗 ✦")
                .withStyle(rankColor(reading.rank()), ChatFormatting.BOLD));
        ChatFormatting[] poemColors = {ChatFormatting.GOLD, ChatFormatting.LIGHT_PURPLE,
                ChatFormatting.AQUA, ChatFormatting.BLUE};
        for (int index = 0; index < sign.poem().size(); index++) {
            player.sendSystemMessage(Component.literal("  " + sign.poem().get(index))
                    .withStyle(poemColors[index % poemColors.length]));
        }
        player.sendSystemMessage(ServerText.translatable("gui.omnitools.divination.meaning", sign.interpretation())
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        player.sendSystemMessage(ServerText.translatable("gui.omnitools.divination.favorable", sign.favorable())
                .withStyle(ChatFormatting.GREEN));
        player.sendSystemMessage(ServerText.translatable("gui.omnitools.divination.avoid", sign.avoid())
                .withStyle(ChatFormatting.RED));
        player.sendSystemMessage(ServerText.translatable("gui.omnitools.divination.advice", sign.advice())
                .withStyle(ChatFormatting.AQUA));
    }

    private static void click(ServerPlayer player) {
        player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.6f, 1.0f);
    }

    private static Component rankName(DivinationConfig.Rank rank) {
        return ServerText.translatable("gui.omnitools.divination.rank." + rank.serializedName());
    }

    private static Item rankIcon(DivinationConfig.Rank rank) {
        return switch (rank) {
            case GREAT_FORTUNE -> Items.NETHER_STAR;
            case FORTUNE -> Items.DIAMOND;
            case NEUTRAL -> Items.AMETHYST_SHARD;
            case MISFORTUNE -> Items.MAGMA_CREAM;
            case GREAT_MISFORTUNE -> Items.WITHER_ROSE;
        };
    }

    private static ChatFormatting rankColor(DivinationConfig.Rank rank) {
        return switch (rank) {
            case GREAT_FORTUNE -> ChatFormatting.GOLD;
            case FORTUNE -> ChatFormatting.AQUA;
            case NEUTRAL -> ChatFormatting.LIGHT_PURPLE;
            case MISFORTUNE -> ChatFormatting.GOLD;
            case GREAT_MISFORTUNE -> ChatFormatting.DARK_RED;
        };
    }

    private ItemStack animationItem() {
        Item[] frames = {Items.AMETHYST_SHARD, Items.ENCHANTED_BOOK, Items.CLOCK, Items.ENDER_EYE};
        Item item = frames[Math.floorMod(animationFrame, frames.length)];
        return detailedStatus(item, Component.literal("✦ 天机推演中…"), ChatFormatting.LIGHT_PURPLE,
                List.of(Component.literal("星轨正在回应你的问题").withStyle(ChatFormatting.AQUA)), true);
    }

    private void beginAnimation(DivinationData.Reading reading) {
        if (reading == null) return;
        animationTicks = 40;
        animationFrame = 0;
        refreshContents();
    }

    private void tickAnimation(ServerPlayer player) {
        if (animationTicks <= 0) return;
        if (owner != player || player.containerMenu != this || !ModMindEntry.isModuleEnabled(ModuleId.DIVINATION)) {
            stopAnimation();
            return;
        }
        if (animationTicks % 4 == 0) {
            animationFrame++;
            container.setItem(RESULT_SLOT, animationItem());
            player.displayClientMessage(Component.literal("✦ 天机推演中…").withStyle(ChatFormatting.LIGHT_PURPLE), true);
            if (player.level() instanceof ServerLevel level) {
                level.sendParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + 1.0D, player.getZ(),
                        3, 0.22D, 0.35D, 0.22D, 0.01D);
            }
            player.playSound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.25f, 0.8f + animationFrame * 0.04f);
        }
        animationTicks--;
        if (animationTicks == 0) {
            refreshContents();
            player.displayClientMessage(Component.literal("✦ 签筒已定 ✦").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), true);
            player.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.05f);
        }
    }

    private void stopAnimation() {
        animationTicks = 0;
        animationFrame = 0;
    }

    private enum View { HOME, HISTORY }
}
