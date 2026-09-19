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
import net.minecraft.server.level.ServerPlayer;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Compact daily-sign desk: draw, reveal, reroll and history without theme or relief submenus. */
public final class DivinationScreenHandler extends ChestMenu {
    private static final int ROWS = 6;
    private static final int CONTAINER_SIZE = ROWS * 9;
    private static final int CLOSE_SLOT = 8;
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
        if (slotId == CLOSE_SLOT) {
            serverPlayer.closeContainer();
            click(serverPlayer);
            return;
        }
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
        DivinationData.Reading reading = ModMindEntry.divinationService().current(owner);
        container.setItem(0, GuiTheme.status(Items.AMETHYST_SHARD,
                ServerText.translatable("gui.omnitools.divination.title"), ChatFormatting.LIGHT_PURPLE,
                List.of(ServerText.translatable("gui.omnitools.divination.daily_status",
                        reading == null ? ServerText.translatable("gui.omnitools.divination.none") : rankName(reading.rank()))
                        .withStyle(ChatFormatting.GRAY)), reading != null && reading.active()));
        long balance = CheckinData.get(owner).getBalance(owner.getUUID());
        container.setItem(4, GuiTheme.named(Items.GOLD_NUGGET,
                ServerText.translatable("gui.omnitools.divination.wallet"),
                List.of(ServerText.translatable("gui.omnitools.divination.wallet_balance", balance)
                        .withStyle(ChatFormatting.GRAY))));
        container.setItem(CLOSE_SLOT, GuiNavigationService.close());
        if (view == View.HISTORY) renderHistory(); else renderHome(reading);
        lastRevision = ModMindEntry.configSnapshot().revision();
    }

    private void renderHome(DivinationData.Reading reading) {
        boolean pending = reading != null && !reading.resolved();
        boolean rerollable = reading != null && reading.resolved()
                && ModMindEntry.divinationService().config().settings().rerollsEnabled();
        container.setItem(RESULT_SLOT, readingItem(reading));
        container.setItem(DRAW_SLOT, GuiTheme.status(Items.BLAZE_POWDER,
                ServerText.translatable("gui.omnitools.divination.draw"),
                reading == null ? ChatFormatting.GOLD : ChatFormatting.GRAY,
                List.of(ServerText.translatable(reading == null ? "gui.omnitools.divination.draw_hint"
                        : "gui.omnitools.divination.draw_unavailable").withStyle(ChatFormatting.GRAY)), reading == null));
        container.setItem(INTERPRET_SLOT, GuiTheme.status(Items.WRITABLE_BOOK,
                ServerText.translatable("gui.omnitools.divination.interpret"),
                pending ? ChatFormatting.AQUA : ChatFormatting.GRAY,
                List.of(ServerText.translatable(pending ? "gui.omnitools.divination.interpret_hint"
                        : "gui.omnitools.divination.interpret_unavailable").withStyle(ChatFormatting.GRAY)), pending));
        long cost = ModMindEntry.divinationService().config().settings().rerollCost();
        container.setItem(REROLL_SLOT, GuiTheme.status(Items.GOLD_INGOT,
                ServerText.translatable("gui.omnitools.divination.reroll"),
                rerollable ? ChatFormatting.GOLD : ChatFormatting.GRAY,
                List.of(ServerText.translatable(rerollable ? "gui.omnitools.divination.reroll_hint"
                        : "gui.omnitools.divination.reroll_unavailable", cost).withStyle(ChatFormatting.GRAY)), rerollable));
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
            sign.poem().forEach(line -> lore.add(Component.literal(line).withStyle(ChatFormatting.DARK_GRAY)));
            lore.add(ServerText.translatable("gui.omnitools.divination.meaning", sign.interpretation()).withStyle(ChatFormatting.GRAY));
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
            player.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.0f);
        } else {
            player.displayClientMessage(ServerText.translatable("message.omnitools.divination.interpret_failed"), true);
            player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 0.6f);
        }
    }

    private static void click(ServerPlayer player) {
        player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.6f, 1.0f);
    }

    private static Component rankName(DivinationConfig.Rank rank) {
        return ServerText.translatable("gui.omnitools.divination.rank." + rank.serializedName());
    }

    private static Item rankIcon(DivinationConfig.Rank rank) {
        return rank.auspicious() ? Items.GOLD_INGOT : rank == DivinationConfig.Rank.NEUTRAL ? Items.PAPER : Items.FLINT;
    }

    private static ChatFormatting rankColor(DivinationConfig.Rank rank) {
        return rank.auspicious() ? ChatFormatting.GOLD : rank == DivinationConfig.Rank.NEUTRAL
                ? ChatFormatting.GRAY : ChatFormatting.DARK_GRAY;
    }

    private enum View { HOME, HISTORY }
}
