package dev.modmind.omnitools;

import dev.modmind.omnitools.diagnostics.ModuleFaultBoundary;
import dev.modmind.omnitools.config.ModuleId;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Minimal server-authoritative confirmation page for one card purchase or historical sign-in. */
public final class CheckinMakeupConfirmScreenHandler extends ChestMenu {
    private static final int SIZE = 27;
    private static final int CONFIRM_SLOT = 11;
    private static final int CANCEL_SLOT = 15;
    private final SimpleContainer container;
    private final ServerPlayer owner;
    private final UUID ownerId;
    private final LocalDate targetDate;

    private CheckinMakeupConfirmScreenHandler(int syncId, Inventory inventory, ServerPlayer owner, LocalDate targetDate) {
        this(syncId, inventory, new SimpleContainer(SIZE), owner, targetDate);
    }

    private CheckinMakeupConfirmScreenHandler(int syncId, Inventory inventory, SimpleContainer container,
                                              ServerPlayer owner, LocalDate targetDate) {
        super(MenuType.GENERIC_9x3, syncId, inventory, container, 3);
        this.container = container;
        this.owner = owner;
        this.ownerId = owner == null ? null : owner.getUUID();
        this.targetDate = targetDate;
        if (owner != null) {
            render();
        }
    }

    public static CheckinMakeupConfirmScreenHandler forDate(int syncId, Inventory inventory, ServerPlayer owner,
                                                              LocalDate date) {
        return new CheckinMakeupConfirmScreenHandler(syncId, inventory, owner, date);
    }

    public static CheckinMakeupConfirmScreenHandler forPurchase(int syncId, Inventory inventory, ServerPlayer owner) {
        return new CheckinMakeupConfirmScreenHandler(syncId, inventory, owner, null);
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            ModuleFaultBoundary.runPlayerAction(ModuleId.DAILY_CHECKIN, "makeup_menu_confirm", serverPlayer,
                    "makeup_transaction_retained", () -> handleClick(slotId, button, clickType, player));
            return;
        }
        handleClick(slotId, button, clickType, player);
    }

    private void handleClick(int slotId, int button, ClickType clickType, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer) || !serverPlayer.getUUID().equals(ownerId)
                || clickType != ClickType.PICKUP || button != 0) {
            return;
        }
        if (!ModMindEntry.isModuleEnabled(ModuleId.DAILY_CHECKIN)) {
            serverPlayer.closeContainer();
            return;
        }
        if (slotId == CANCEL_SLOT) {
            ModMindEntry.openCheckinMenu(serverPlayer);
            return;
        }
        if (slotId != CONFIRM_SLOT) {
            return;
        }
        if (targetDate == null) {
            applyPurchase(serverPlayer);
        } else {
            applyMakeup(serverPlayer);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    private void applyMakeup(ServerPlayer player) {
        if (!ModMindEntry.hasCommandPermission(player, CommandAction.CHECKIN_MAKEUP)) {
            player.displayClientMessage(ServerText.translatable("message.omnitools.permission_denied"), true);
            return;
        }
        CheckinData.MakeupResult result = ModMindEntry.checkinMakeupService().makeup(player, targetDate);
        if (!result.applied()) {
            player.displayClientMessage(ServerText.translatable("command.omnitools.checkin.makeup."
                    + result.status().name().toLowerCase(java.util.Locale.ROOT)), true);
            render();
            broadcastChanges();
            return;
        }
        player.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
        player.displayClientMessage(ServerText.translatable("command.omnitools.checkin.makeup.success", targetDate,
                result.stats().streakDays(), CheckinData.get(player).getMakeupCards(player.getUUID())), true);
        ModMindEntry.openCheckinMenu(player);
    }

    private void applyPurchase(ServerPlayer player) {
        if (!ModMindEntry.hasCommandPermission(player, CommandAction.CHECKIN_CARDS_BUY)) {
            player.displayClientMessage(ServerText.translatable("message.omnitools.permission_denied"), true);
            return;
        }
        CheckinData.MakeupPurchaseResult result = ModMindEntry.checkinMakeupService().buy(player, 1L);
        if (!result.applied()) {
            String key = switch (result.status()) {
                case DISABLED -> "command.omnitools.checkin.cards.purchase_disabled";
                case CARD_LIMIT -> "command.omnitools.checkin.cards.limit";
                case INSUFFICIENT_CURRENCY -> "command.omnitools.checkin.cards.insufficient";
                case APPLIED -> throw new IllegalStateException("handled above");
            };
            player.displayClientMessage(ServerText.translatable(key), true);
            render();
            broadcastChanges();
            return;
        }
        player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8f, 1.0f);
        player.displayClientMessage(ServerText.translatable("command.omnitools.checkin.cards.bought", 1,
                result.cost(), result.cards(), result.balance()), true);
        ModMindEntry.openCheckinMenu(player);
    }

    private void render() {
        for (int slot = 0; slot < SIZE; slot++) {
            container.setItem(slot, ItemStack.EMPTY);
        }
        CheckinMakeupService.CardStatus cards = ModMindEntry.checkinMakeupService().status(owner);
        ItemStack detail = new ItemStack(targetDate == null ? Items.EMERALD : Items.CLOCK);
        detail.set(DataComponents.CUSTOM_NAME, ServerText.translatable(targetDate == null
                ? "gui.omnitools.checkin.makeup_purchase_title" : "gui.omnitools.checkin.makeup_confirm_title")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        detail.set(DataComponents.LORE, new ItemLore(List.of(
                targetDate == null ? ServerText.translatable("gui.omnitools.checkin.makeup_purchase_cost",
                        ModMindEntry.rewardService().makeup().purchase().price())
                        : ServerText.translatable("gui.omnitools.checkin.makeup_confirm_date", targetDate),
                ServerText.translatable("gui.omnitools.checkin.makeup_cards_balance", cards.cards(), cards.maxCards()),
                ServerText.translatable("gui.omnitools.checkin.makeup_cards_month", cards.monthlyUses(),
                        cards.maxMonthlyUses()))));
        container.setItem(4, detail);
        container.setItem(CONFIRM_SLOT, GuiTheme.navigation(Items.LIME_DYE,
                ServerText.translatable("gui.omnitools.checkin.makeup_confirm"),
                ServerText.translatable("gui.omnitools.checkin.makeup_confirm_hint")));
        container.setItem(CANCEL_SLOT, GuiTheme.navigation(Items.BARRIER,
                ServerText.translatable("gui.omnitools.checkin.makeup_cancel"),
                ServerText.translatable("gui.omnitools.checkin.makeup_cancel_hint")));
    }
}
