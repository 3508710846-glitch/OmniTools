package dev.modmind.omnitools;

import dev.modmind.omnitools.reward.RewardClaimLedger;
import dev.modmind.omnitools.reward.RewardDefinition;
import dev.modmind.omnitools.reward.RewardEvent;
import dev.modmind.omnitools.reward.RewardType;
import dev.modmind.omnitools.text.TextTemplateRenderer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Builds the journal's presentation-only ItemStacks from current server state. */
public final class CheckinRenderService {
    private CheckinRenderService() {
    }

    public static ItemStack calendarStack(CheckinData data, ServerPlayer player, LocalDate day, LocalDate month,
                                          CheckinRewardService rewards, CheckinUiConfig ui) {
        boolean signed = data.hasSigned(player.getUUID(), day.toEpochDay());
        boolean today = day.equals(month);
        DateState state = today && !signed ? DateState.AVAILABLE
                : today ? DateState.SIGNED
                : day.isBefore(month) ? (signed ? DateState.PAST_SIGNED : DateState.MISSED) : DateState.FUTURE;
        boolean milestone = !today && rewards.monthlyRewards().containsKey(day.getDayOfMonth())
                && state != DateState.MISSED;
        CheckinData.MakeupStatus makeupStatus = state == DateState.MISSED
                ? data.makeupStatus(player.getUUID(), day.toEpochDay(), month.toEpochDay(), rewards.makeup()) : null;
        boolean makeup = makeupStatus == CheckinData.MakeupStatus.APPLIED;
        Item icon = makeup ? Items.CLOCK : milestone ? ui.icons().milestone() : switch (state) {
            case AVAILABLE -> ui.icons().available();
            case SIGNED -> ui.icons().signed();
            case PAST_SIGNED -> ui.icons().pastSigned();
            case MISSED -> ui.icons().missed();
            case FUTURE -> ui.icons().future();
        };
        ChatFormatting color = switch (state) {
            case AVAILABLE -> ChatFormatting.GOLD;
            case SIGNED, PAST_SIGNED -> ChatFormatting.GREEN;
            case MISSED -> ChatFormatting.RED;
            case FUTURE -> ChatFormatting.GRAY;
        };
        List<Component> lore = new ArrayList<>();
        lore.add(CheckinTextProvider.dateDetail(day).withStyle(ChatFormatting.GRAY));
        lore.add(ServerText.translatable(state.translationKey()).withStyle(color));
        if (milestone) {
            lore.add(ServerText.translatable("gui.omnitools.checkin.journal.milestone", day.getDayOfMonth())
                    .withStyle(ChatFormatting.GOLD));
        } else if (state == DateState.AVAILABLE && ui.showActionHints()) {
            lore.add(ServerText.translatable("gui.omnitools.checkin.journal.action_sign")
                    .withStyle(ChatFormatting.GOLD));
        } else if (state == DateState.SIGNED && hasPendingRewards(player, month, data.getStats(player.getUUID(), month.toEpochDay()).monthlyDays())) {
            lore.add(ServerText.translatable("gui.omnitools.checkin.rewards_pending").withStyle(ChatFormatting.YELLOW));
        }
        if (makeup) {
            lore.add(ServerText.translatable("gui.omnitools.checkin.makeup_date_hint")
                    .withStyle(ChatFormatting.AQUA));
        } else if (makeupStatus != null && makeupStatus != CheckinData.MakeupStatus.ALREADY_SIGNED) {
            lore.add(ServerText.translatable("command.omnitools.checkin.makeup."
                    + makeupStatus.name().toLowerCase(java.util.Locale.ROOT)).withStyle(ChatFormatting.GRAY));
        }
        if (milestone && ui.showRewardPreview()) {
            lore.add(ServerText.translatable("gui.omnitools.checkin.journal.action_rewards")
                    .withStyle(ChatFormatting.AQUA));
        }
        ItemStack stack = namedItem(icon, CheckinTextProvider.dateName(day, ui.showWeekday()).withStyle(color,
                ChatFormatting.BOLD), CheckinTextProvider.compactLore(lore));
        if (state == DateState.AVAILABLE || state == DateState.SIGNED || milestone) {
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return stack;
    }

    public static ItemStack emptyCalendarStack(CheckinUiConfig ui) {
        return GuiTheme.emptySlot();
    }

    public static ItemStack profileStack(ServerPlayer player, CheckinData.PlayerStats stats) {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(player.getGameProfile()));
        stack.set(DataComponents.CUSTOM_NAME, ServerText.translatable("gui.omnitools.checkin.profile", player.getName())
                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        stack.set(DataComponents.LORE, new ItemLore(CheckinTextProvider.compactLore(List.of(
                ServerText.translatable("gui.omnitools.checkin.profile_month_progress", stats.monthlyDays()),
                ServerText.translatable("gui.omnitools.checkin.total", stats.totalDays()),
                ServerText.translatable("gui.omnitools.checkin.streak", stats.streakDays())))));
        return stack;
    }

    public static ItemStack monthStack(LocalDate date) {
        return namedItem(Items.CLOCK, ServerText.translatable("gui.omnitools.checkin.journal.month_title",
                        date.getYear(), date.getMonthValue()).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
                List.of(ServerText.translatable("gui.omnitools.checkin.journal.current_day", date.getDayOfMonth())
                        .withStyle(ChatFormatting.GRAY),
                        ServerText.translatable("gui.omnitools.checkin.journal.calendar_subtitle")
                                .withStyle(ChatFormatting.WHITE)));
    }

    public static ItemStack rewardInfoStack(ServerPlayer player, CheckinRewardService rewards,
                                             CheckinData.PlayerStats stats, CheckinUiConfig ui) {
        ItemStack stack = ui.showRewardPreview() ? rewardIcon(player, rewards.dailyRewards()) : new ItemStack(Items.CHEST);
        List<Component> lore = new ArrayList<>();
        lore.add(ServerText.translatable("gui.omnitools.checkin.daily_count", rewards.dailyRewards().size()));
        int next = nextMilestone(rewards, stats.monthlyDays());
        lore.add(next > 0 ? ServerText.translatable("gui.omnitools.checkin.next_milestone", next)
                : ServerText.translatable("gui.omnitools.checkin.milestone_complete"));
        lore.add(ServerText.translatable("gui.omnitools.checkin.reward_hint").withStyle(ChatFormatting.AQUA));
        stack.set(DataComponents.CUSTOM_NAME, ServerText.translatable("gui.omnitools.checkin.reward_info")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        stack.set(DataComponents.LORE, new ItemLore(CheckinTextProvider.compactLore(lore)));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return stack;
    }

    public static ItemStack progressStack(CheckinRewardService rewards, int monthlyDays, CheckinUiConfig ui) {
        int next = nextMilestone(rewards, monthlyDays);
        int target = next > 0 ? next : rewards.monthlyRewards().keySet().stream().mapToInt(Integer::intValue)
                .max().orElse(Math.max(1, monthlyDays));
        List<Component> lore = new ArrayList<>();
        lore.add(ServerText.translatable("gui.omnitools.checkin.month_progress", monthlyDays, target));
        if (next > 0) {
            lore.add(ServerText.translatable("gui.omnitools.checkin.days_left", Math.max(0, next - monthlyDays)));
        }
        if (ui.showProgressBar()) {
            lore.add(ServerText.translatable("gui.omnitools.checkin.journal.progress_bar", progressBar(monthlyDays, target)));
        }
        return namedItem(Items.FILLED_MAP, ServerText.translatable("gui.omnitools.checkin.progress")
                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), CheckinTextProvider.compactLore(lore));
    }

    public static ItemStack recordsStack() {
        return namedItem(Items.WRITABLE_BOOK, ServerText.translatable("gui.omnitools.checkin.records"),
                List.of(ServerText.translatable("gui.omnitools.checkin.records_hint")));
    }

    public static ItemStack achievementsStack() {
        return namedItem(Items.NETHER_STAR, ServerText.translatable("gui.omnitools.checkin.achievements"),
                List.of(ServerText.translatable("gui.omnitools.checkin.achievements_hint")));
    }

    public static ItemStack streakStack(int streakDays) {
        return namedItem(Items.CAMPFIRE, ServerText.translatable("gui.omnitools.checkin.streak", streakDays),
                List.of(ServerText.translatable("gui.omnitools.checkin.journal.streak_hint")));
    }

    public static ItemStack balanceStack(long balance) {
        return namedItem(Items.GOLD_INGOT, ServerText.translatable("gui.omnitools.checkin.balance", balance),
                List.of(ServerText.translatable("gui.omnitools.checkin.journal.balance_hint")));
    }

    public static ItemStack inboxStack(ServerPlayer player) {
        int pending = RewardClaimLedger.get(player).pendingItemEntries(player.getUUID()).size();
        return namedItem(Items.CHEST, ServerText.translatable("gui.omnitools.checkin.reward_inbox"),
                List.of(ServerText.translatable("gui.omnitools.checkin.reward_inbox_count", pending),
                        ServerText.translatable("gui.omnitools.checkin.reward_inbox_hint")));
    }

    public static ItemStack makeupCardStack(ServerPlayer player) {
        CheckinMakeupService.CardStatus status = ModMindEntry.checkinMakeupService().status(player);
        ItemStack stack = namedItem(Items.CLOCK, ServerText.translatable("gui.omnitools.checkin.makeup_cards")
                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), List.of(
                ServerText.translatable("gui.omnitools.checkin.makeup_cards_balance", status.cards(), status.maxCards())
                        .withStyle(ChatFormatting.GOLD),
                ServerText.translatable("gui.omnitools.checkin.makeup_cards_month", status.monthlyUses(),
                        status.maxMonthlyUses()).withStyle(ChatFormatting.GRAY),
                ServerText.translatable("gui.omnitools.checkin.makeup_cards_hint").withStyle(ChatFormatting.AQUA)));
        if (status.cards() > 0L) {
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return stack;
    }

    public static ItemStack helpStack() {
        return namedItem(Items.BOOK, ServerText.translatable("gui.omnitools.checkin.help"), List.of(
                ServerText.translatable("gui.omnitools.checkin.help_available").withStyle(ChatFormatting.GOLD),
                ServerText.translatable("gui.omnitools.checkin.help_signed").withStyle(ChatFormatting.GREEN),
                ServerText.translatable("gui.omnitools.checkin.help_missed").withStyle(ChatFormatting.RED),
                ServerText.translatable("gui.omnitools.checkin.help_future").withStyle(ChatFormatting.GRAY)));
    }

    public static ItemStack refreshStack() {
        return namedItem(Items.COMPASS, ServerText.translatable("gui.omnitools.checkin.refresh"),
                List.of(ServerText.translatable("gui.omnitools.checkin.refresh_hint")));
    }

    public static ItemStack closeStack() {
        return GuiNavigationService.close();
    }

    private static ItemStack rewardIcon(ServerPlayer player, List<RewardDefinition> rewards) {
        ItemStack stack = new ItemStack(Items.CHEST);
        for (RewardDefinition reward : rewards) {
            stack = switch (reward.type()) {
                case ITEM -> reward.createItemStack();
                case CURRENCY -> new ItemStack(Items.GOLD_INGOT);
                case MAKEUP_CARD -> new ItemStack(Items.CLOCK);
                case TITLE -> new ItemStack(Items.NAME_TAG);
                case COMMAND -> new ItemStack(Items.COMMAND_BLOCK);
            };
            break;
        }
        return TextTemplateRenderer.renderItemText(player, stack);
    }

    private static int nextMilestone(CheckinRewardService rewards, int monthlyDays) {
        return rewards.monthlyRewards().keySet().stream().sorted()
                .filter(milestone -> milestone > monthlyDays).findFirst().orElse(-1);
    }

    private static boolean hasPendingRewards(ServerPlayer player, LocalDate month, int monthlyDays) {
        CheckinRewardService rewards = ModMindEntry.rewardService();
        RewardClaimLedger ledger = RewardClaimLedger.get(player);
        RewardEvent dailyEvent = RewardEvent.checkinDaily(player.getUUID(), month.toEpochDay());
        if (ledger.hasEvent(dailyEvent) && !ledger.allGranted(dailyEvent, rewards.dailyRewards())) {
            return true;
        }
        for (var milestone : rewards.monthlyRewards().entrySet()) {
            if (monthlyDays < milestone.getKey()) {
                continue;
            }
            RewardEvent event = RewardEvent.checkinMonthly(player.getUUID(), java.time.YearMonth.from(month),
                    milestone.getKey());
            if (ledger.hasEvent(event) && !ledger.allGranted(event, milestone.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static String progressBar(int current, int target) {
        int filled = target <= 0 ? 10 : Math.min(10, Math.max(0, (int) Math.floor(10D * current / target)));
        return "\u25A0".repeat(filled) + "\u25A1".repeat(10 - filled);
    }

    private static ItemStack namedItem(Item item, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, name);
        if (!lore.isEmpty()) {
            stack.set(DataComponents.LORE, new ItemLore(lore));
        }
        return stack;
    }

    private enum DateState {
        AVAILABLE("gui.omnitools.checkin.journal.status_available"),
        SIGNED("gui.omnitools.checkin.journal.status_signed"),
        PAST_SIGNED("gui.omnitools.checkin.journal.status_past_signed"),
        MISSED("gui.omnitools.checkin.journal.status_missed"),
        FUTURE("gui.omnitools.checkin.journal.status_future");

        private final String translationKey;

        DateState(String translationKey) {
            this.translationKey = translationKey;
        }

        String translationKey() {
            return translationKey;
        }
    }
}
