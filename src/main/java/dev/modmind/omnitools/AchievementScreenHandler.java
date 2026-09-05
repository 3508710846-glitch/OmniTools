package dev.modmind.omnitools;

import dev.modmind.omnitools.diagnostics.ModuleFaultBoundary;
import dev.modmind.omnitools.text.TextTemplateRenderer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
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
import net.minecraft.world.item.component.ResolvableProfile;
import dev.modmind.omnitools.achievement.AchievementCondition;
import dev.modmind.omnitools.achievement.AllCondition;
import dev.modmind.omnitools.achievement.AnyCondition;
import dev.modmind.omnitools.achievement.ConditionProgress;
import dev.modmind.omnitools.achievement.NotCondition;
import dev.modmind.omnitools.achievement.StatCondition;
import dev.modmind.omnitools.achievement.SumCondition;
import dev.modmind.omnitools.achievement.TargetMatch;
import dev.modmind.omnitools.reward.RewardDefinition;
import dev.modmind.omnitools.reward.RewardType;
import dev.modmind.omnitools.reward.RewardClaimLedger;
import dev.modmind.omnitools.reward.RewardEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Server-authoritative paged achievement list and one-time reward claim menu. */
public final class AchievementScreenHandler extends ChestMenu {
    public static final int ROWS = 6;
    public static final int CONTAINER_SIZE = ROWS * 9;
    public static final int ACHIEVEMENT_SLOTS = GuiSlots.CONTENT_SLOT_COUNT_54;
    public static final int PREVIOUS_PAGE_SLOT = GuiSlots.FIRST_ACTION_SLOT_54;
    public static final int PROFILE_SLOT = GuiSlots.CENTER_54;
    public static final int NEXT_PAGE_SLOT = GuiSlots.LAST_SLOT_54;
    public static final int HEADER_PROFILE_SLOT = GuiSlots.HEADER_LEFT_54;
    public static final int HEADER_TITLE_SLOT = GuiSlots.HEADER_CENTER_54;
    public static final int CLOSE_SLOT = GuiSlots.HEADER_CLOSE_54;
    public static final int COMPLETED_FILTER_SLOT = GuiSlots.FIRST_ACTION_SLOT_54 + 1;
    public static final int CLAIMABLE_FILTER_SLOT = GuiSlots.FIRST_ACTION_SLOT_54 + 2;
    private static final int MAX_NAMED_TARGETS = 4;
    private static final int MAX_VISIBLE_EACH_TARGETS = 2;
    private static final int MAX_CONDITION_LORE_LINES = 4;
    private static final int MAX_ACHIEVEMENT_LORE_LINES = 10;
    private static final int MAX_PREVIEW_EFFECTS = 3;
    private final SimpleContainer achievementContainer;
    private final UUID ownerId;
    private final ServerPlayer owner;
    private final AchievementService service;
    private AchievementFilter filter = AchievementFilter.ALL;
    private int page;
    private long lastRefreshTick = Long.MIN_VALUE;
    private int lastConfigRevision = Integer.MIN_VALUE;

    public AchievementScreenHandler(int syncId, Inventory inventory) {
        this(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), null, null, 0);
    }

    private AchievementScreenHandler(int syncId, Inventory inventory, SimpleContainer container,
                                     ServerPlayer owner, AchievementService service, int page) {
        super(MenuType.GENERIC_9x6, syncId, inventory, container, ROWS);
        this.achievementContainer = container;
        this.owner = owner;
        this.ownerId = owner == null ? null : owner.getUUID();
        this.service = service;
        this.page = page;
        if (owner != null && service != null) {
            refreshContents();
        }
    }

    public static AchievementScreenHandler createServer(int syncId, Inventory inventory, ServerPlayer owner,
                                                        AchievementService service, int page) {
        service.retryPending(owner);
        return new AchievementScreenHandler(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), owner, service,
                page);
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            ModuleFaultBoundary.runPlayerAction(dev.modmind.omnitools.config.ModuleId.ACHIEVEMENTS, "menu_click",
                    serverPlayer, "achievement_claim_ledger_retained",
                    () -> handleClick(slotId, button, clickType, player));
            return;
        }
        handleClick(slotId, button, clickType, player);
    }

    private void handleClick(int slotId, int button, ClickType clickType, Player player) {
        if (!ModMindEntry.isModuleEnabled(dev.modmind.omnitools.config.ModuleId.ACHIEVEMENTS)
                || (player instanceof ServerPlayer serverPlayerForPermission
                && !ModMindEntry.hasCommandPermission(serverPlayerForPermission,
                dev.modmind.omnitools.permissions.CommandAction.ACHIEVEMENTS_OPEN))) {
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

        List<AchievementConfig.AchievementDefinition> achievements = service.config().achievements();
        AchievementService.MenuSnapshot menuSnapshot = service.menuSnapshot(serverPlayer);
        List<AchievementConfig.AchievementDefinition> visibleAchievements = filteredAchievements(menuSnapshot, achievements);
        int pageCount = pageCount(visibleAchievements.size());
        if (slotId == CLOSE_SLOT) {
            serverPlayer.closeContainer();
            GuiFeedbackService.click(serverPlayer);
            return;
        }
        if (slotId == COMPLETED_FILTER_SLOT) {
            filter = filter == AchievementFilter.COMPLETED ? AchievementFilter.ALL : AchievementFilter.COMPLETED;
            page = 0;
            refreshContents();
            GuiFeedbackService.click(serverPlayer);
            return;
        }
        if (slotId == CLAIMABLE_FILTER_SLOT) {
            filter = filter == AchievementFilter.CLAIMABLE ? AchievementFilter.ALL : AchievementFilter.CLAIMABLE;
            page = 0;
            refreshContents();
            GuiFeedbackService.click(serverPlayer);
            return;
        }
        if (slotId == PREVIOUS_PAGE_SLOT && page > 0) {
            page--;
            refreshContents();
            GuiFeedbackService.click(serverPlayer);
            return;
        }
        if (slotId == NEXT_PAGE_SLOT && page + 1 < pageCount) {
            page++;
            refreshContents();
            GuiFeedbackService.click(serverPlayer);
            return;
        }
        int localIndex = GuiSlots.contentIndex54(slotId);
        if (localIndex < 0) {
            return;
        }

        int achievementIndex = page * ACHIEVEMENT_SLOTS + localIndex;
        if (achievementIndex >= visibleAchievements.size()) {
            return;
        }
        AchievementConfig.AchievementDefinition achievement = visibleAchievements.get(achievementIndex);
        AchievementDisplayState displayState = displayState(menuSnapshot.evaluation(achievement.id()));
        if (!displayState.isClaimable()) {
            showNonClaimableFeedback(serverPlayer, displayState);
            return;
        }
        AchievementService.ClaimResult result = service.claim(serverPlayer, achievement.id());
        switch (result.status()) {
            case CLAIMED -> {
                GuiFeedbackService.success(serverPlayer);
                serverPlayer.displayClientMessage(ServerText.translatable(
                        "message.omnitools.achievement.claimed", TextTemplateRenderer.render(serverPlayer,
                                achievement.display()), result.grantedRewards(), result.balance()), true);
            }
            case ALREADY_CLAIMED -> {
                GuiFeedbackService.failure(serverPlayer);
                serverPlayer.displayClientMessage(ServerText.translatable(
                        "message.omnitools.achievement.already_claimed"), true);
            }
            case NOT_COMPLETED -> {
                GuiFeedbackService.failure(serverPlayer);
                serverPlayer.displayClientMessage(ServerText.translatable(
                        "message.omnitools.achievement.not_completed"), true);
            }
            case UNKNOWN_ACHIEVEMENT -> {
                GuiFeedbackService.failure(serverPlayer);
                serverPlayer.displayClientMessage(ServerText.translatable(
                        "message.omnitools.achievement.unknown"), true);
            }
            case PENDING, BLOCKED, FAILED -> {
                GuiFeedbackService.failure(serverPlayer);
                serverPlayer.displayClientMessage(ServerText.translatable(
                        "message.omnitools.achievement.reward_pending", result.reason()), true);
            }
        }
        refreshContents();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (service != null && player instanceof ServerPlayer serverPlayer
                && ownerId != null && ownerId.equals(serverPlayer.getUUID())) {
            service.forgetMenuSnapshot(serverPlayer);
        }
    }

    @Override
    public void broadcastChanges() {
        if (owner != null && service != null) {
            long tick = owner.level().getServer().getTickCount();
            if (tick - lastRefreshTick >= AchievementService.CHECK_INTERVAL_TICKS
                    || service.revision() != lastConfigRevision) {
                refreshContents();
            }
        }
        super.broadcastChanges();
    }

    private void refreshContents() {
        AchievementService.MenuSnapshot menuSnapshot = service.menuSnapshot(owner);
        AchievementConfig config = service.config();
        List<AchievementConfig.AchievementDefinition> achievements = config.achievements();
        List<AchievementConfig.AchievementDefinition> visibleAchievements = filteredAchievements(menuSnapshot, achievements);
        int completedCount = completedCount(menuSnapshot, achievements);
        int claimableCount = claimableCount(menuSnapshot, achievements);
        int pageCount = pageCount(visibleAchievements.size());
        page = Math.max(0, Math.min(page, pageCount - 1));

        GuiTheme.clear(achievementContainer);

        if (achievements.isEmpty()) {
            achievementContainer.setItem(22, GuiTheme.named(Items.BOOK,
                    ServerText.translatable("gui.omnitools.achievement.empty_title").withStyle(ChatFormatting.GRAY),
                    List.of(ServerText.translatable("gui.omnitools.achievement.empty_hint")
                            .withStyle(ChatFormatting.DARK_GRAY))));
        } else if (visibleAchievements.isEmpty()) {
            achievementContainer.setItem(22, GuiTheme.named(Items.HOPPER,
                    ServerText.translatable("gui.omnitools.achievement.filter_empty_title")
                            .withStyle(ChatFormatting.GRAY),
                    List.of(ServerText.translatable("gui.omnitools.achievement.filter_empty_hint")
                            .withStyle(ChatFormatting.DARK_GRAY))));
        }

        achievementContainer.setItem(HEADER_PROFILE_SLOT, profileItem(completedCount, achievements.size(),
                service.claimedCount(owner)));
        achievementContainer.setItem(HEADER_TITLE_SLOT, GuiTheme.status(Items.NETHER_STAR,
                ServerText.translatable("gui.omnitools.achievement.title"), ChatFormatting.AQUA,
                List.of(
                        ServerText.translatable("gui.omnitools.achievement.overview", completedCount,
                                achievements.size()).withStyle(ChatFormatting.AQUA),
                        ServerText.translatable("gui.omnitools.achievement.claimable_count", claimableCount)
                                .withStyle(ChatFormatting.GREEN),
                        ServerText.translatable("gui.omnitools.achievement.filter_current",
                                ServerText.translatable(filter.translationKey())).withStyle(ChatFormatting.GRAY)
                ), false));
        achievementContainer.setItem(CLOSE_SLOT, GuiNavigationService.close());
        achievementContainer.setItem(COMPLETED_FILTER_SLOT, filterButton(Items.BOOK, AchievementFilter.COMPLETED,
                "gui.omnitools.achievement.filter_completed", "gui.omnitools.achievement.filter_completed_hint"));
        achievementContainer.setItem(CLAIMABLE_FILTER_SLOT, filterButton(Items.LIME_DYE, AchievementFilter.CLAIMABLE,
                "gui.omnitools.achievement.filter_claimable", "gui.omnitools.achievement.filter_claimable_hint"));

        int firstAchievement = page * ACHIEVEMENT_SLOTS;
        for (int index = 0; index < ACHIEVEMENT_SLOTS && firstAchievement + index < visibleAchievements.size();
             index++) {
            AchievementConfig.AchievementDefinition achievement = visibleAchievements.get(firstAchievement + index);
            AchievementService.Evaluation evaluation = menuSnapshot.evaluation(achievement.id());
            achievementContainer.setItem(GuiSlots.contentSlot54(index), achievementItem(achievement, evaluation));
        }

        if (page > 0) {
            achievementContainer.setItem(PREVIOUS_PAGE_SLOT, GuiNavigationService.previous());
        }
        achievementContainer.setItem(PROFILE_SLOT, GuiNavigationService.page(page + 1, pageCount,
                visibleAchievements.size()));
        if (page + 1 < pageCount) {
            achievementContainer.setItem(NEXT_PAGE_SLOT, GuiNavigationService.next());
        }
        lastRefreshTick = owner.level().getServer().getTickCount();
        lastConfigRevision = service.revision();
    }

    private ItemStack achievementItem(AchievementConfig.AchievementDefinition achievement,
                                      AchievementService.Evaluation evaluation) {
        AchievementDisplayState displayState = displayState(evaluation);
        ConditionProgress progress = evaluation.progress();
        GuiStatusItem.State visualState = visualState(displayState);
        ChatFormatting color = visualState.color();
        List<Component> lore = new ArrayList<>();
        lore.add(TextTemplateRenderer.render(owner, achievement.description()).copy().withStyle(ChatFormatting.GRAY));
        List<Component> conditionLore = new ArrayList<>();
        appendConditionLore(achievement.condition(), progress, conditionLore, color, 0);
        lore.addAll(conditionLore.stream().limit(MAX_CONDITION_LORE_LINES).toList());
        appendRewards(achievement.rewards(), lore);
        appendPendingRewardReason(achievement, lore);
        return GuiStatusItem.create(displayIcon(achievement, displayState), achievementName(achievement, displayState),
                visualState, GuiTextService.cardLore(lore,
                        ServerText.translatable(stateFooterTranslationKey(displayState)).withStyle(color),
                        MAX_ACHIEVEMENT_LORE_LINES), MAX_ACHIEVEMENT_LORE_LINES);
    }

    private ItemStack profileItem(int completedCount, int totalAchievements, int claimedCount) {
        ItemStack profile = new ItemStack(Items.PLAYER_HEAD);
        profile.set(DataComponents.PROFILE, ResolvableProfile.createResolved(owner.getGameProfile()));
        profile.set(DataComponents.CUSTOM_NAME, ServerText.translatable("gui.omnitools.achievement.profile")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        profile.set(DataComponents.LORE, new ItemLore(List.of(
                ServerText.translatable("gui.omnitools.achievement.overview", completedCount, totalAchievements)
                        .withStyle(ChatFormatting.AQUA),
                ServerText.translatable("gui.omnitools.achievement.claimed", claimedCount)
                        .withStyle(ChatFormatting.GREEN),
                ServerText.translatable("gui.omnitools.achievement.total", totalAchievements)
                        .withStyle(ChatFormatting.DARK_GRAY))));
        return profile;
    }

    private static AchievementDisplayState displayState(AchievementService.Evaluation evaluation) {
        // The current schema has no prerequisite field; do not infer a lock from display text or Lore.
        return AchievementDisplayState.resolve(false, evaluation.completed(), evaluation.claimed());
    }

    private static GuiStatusItem.State visualState(AchievementDisplayState displayState) {
        return switch (displayState) {
            case LOCKED -> GuiStatusItem.State.BLOCKED;
            case IN_PROGRESS -> GuiStatusItem.State.IN_PROGRESS;
            case CLAIMABLE -> GuiStatusItem.State.ACTIONABLE;
            case CLAIMED -> GuiStatusItem.State.COMPLETED;
        };
    }

    private ItemStack displayIcon(AchievementConfig.AchievementDefinition achievement,
                                  AchievementDisplayState displayState) {
        return displayState == AchievementDisplayState.LOCKED
                ? new ItemStack(Items.BARRIER) : new ItemStack(achievement.icon());
    }

    private Component achievementName(AchievementConfig.AchievementDefinition achievement,
                                      AchievementDisplayState displayState) {
        return ServerText.translatable(stateNameTranslationKey(displayState)).append(Component.literal(" "))
                .append(TextTemplateRenderer.render(owner, achievement.display()));
    }

    private void showNonClaimableFeedback(ServerPlayer player, AchievementDisplayState displayState) {
        GuiFeedbackService.failure(player);
        String messageKey = switch (displayState) {
            case LOCKED -> "message.omnitools.achievement.locked";
            case IN_PROGRESS -> "message.omnitools.achievement.not_completed";
            case CLAIMED -> "message.omnitools.achievement.already_claimed";
            case CLAIMABLE -> throw new IllegalArgumentException("Claimable achievements must be claimed directly");
        };
        player.displayClientMessage(ServerText.translatable(messageKey), true);
    }

    private List<AchievementConfig.AchievementDefinition> filteredAchievements(
            AchievementService.MenuSnapshot menuSnapshot,
            List<AchievementConfig.AchievementDefinition> achievements) {
        return achievements.stream().filter(achievement -> filter.includes(
                displayState(menuSnapshot.evaluation(achievement.id())))).toList();
    }

    private static int completedCount(AchievementService.MenuSnapshot menuSnapshot,
                                      List<AchievementConfig.AchievementDefinition> achievements) {
        return (int) achievements.stream().filter(achievement -> displayState(menuSnapshot.evaluation(achievement.id()))
                .isCompleted()).count();
    }

    private static int claimableCount(AchievementService.MenuSnapshot menuSnapshot,
                                      List<AchievementConfig.AchievementDefinition> achievements) {
        return (int) achievements.stream().filter(achievement -> displayState(menuSnapshot.evaluation(achievement.id()))
                .isClaimable()).count();
    }

    private ItemStack filterButton(Item item, AchievementFilter target, String titleKey, String inactiveHintKey) {
        boolean active = filter == target;
        return GuiStatusItem.create(new ItemStack(item), ServerText.translatable(titleKey),
                active ? GuiStatusItem.State.ACTIONABLE : GuiStatusItem.State.INACTIVE,
                List.of(ServerText.translatable(active ? "gui.omnitools.achievement.filter_show_all_hint"
                        : inactiveHintKey).withStyle(ChatFormatting.GRAY)));
    }

    private void appendConditionLore(AchievementCondition condition, ConditionProgress progress,
                                     List<Component> lore, ChatFormatting color, int indent) {
        Component prefix = Component.literal("  ".repeat(Math.max(0, indent)));
        if (condition instanceof StatCondition stat) {
            if (isCustomOnly(stat.requirements())) {
                lore.add(prefix.copy().append(customProgressLine(progress.current(), stat.atLeast(),
                        stat.progressDivisor(), stat.progressUnit())).withStyle(color));
                return;
            }
            if (stat.match() == TargetMatch.SUM) {
                if (stat.requirements().size() == 1) {
                    lore.add(prefix.copy().append(statLine(stat.requirements().get(0), progress.current(),
                            stat.atLeast())).withStyle(color));
                } else {
                    lore.add(prefix.copy().append(sumProgressLine(stat.requirements(), progress.current(),
                            stat.atLeast(), "gui.omnitools.achievement.condition.stat_sum")).withStyle(color));
                }
            } else {
                if (stat.match() == TargetMatch.EACH) {
                    lore.add(prefix.copy().append(ServerText.translatable(
                            "gui.omnitools.achievement.condition.each_progress",
                            progress.current(), progress.target())).withStyle(color));
                    appendIncompleteStatTargets(stat, progress, lore, color, indent + 1);
                } else {
                    lore.add(prefix.copy().append(ServerText.translatable(
                            "gui.omnitools.achievement.condition.any_progress",
                            progress.current(), progress.target(), stat.requirements().size())).withStyle(color));
                }
            }
            return;
        }
        if (condition instanceof SumCondition sum) {
            if (containsCustom(sum.requirements())) {
                lore.add(prefix.copy().append(customProgressLine(progress.current(), sum.atLeast(),
                        sum.progressDivisor(), sum.progressUnit())).withStyle(color));
                return;
            }
            lore.add(prefix.copy().append(sumProgressLine(sum.requirements(), progress.current(), sum.atLeast(),
                    "gui.omnitools.achievement.condition.sum")).withStyle(color));
            return;
        }
        if (condition instanceof AllCondition all) {
            lore.add(prefix.copy().append(ServerText.translatable(
                    "gui.omnitools.achievement.condition.all", progress.current(), progress.target()))
                    .withStyle(color));
            appendChildren(all.children(), progress, lore, color, indent + 1);
            return;
        }
        if (condition instanceof AnyCondition any) {
            lore.add(prefix.copy().append(ServerText.translatable(
                    "gui.omnitools.achievement.condition.any", progress.current(), progress.target()))
                    .withStyle(color));
            appendChildren(any.children(), progress, lore, color, indent + 1);
            return;
        }
        if (condition instanceof NotCondition not) {
            lore.add(prefix.copy().append(ServerText.translatable(
                    "gui.omnitools.achievement.condition.not", progress.current(), progress.target()))
                    .withStyle(color));
            appendChildren(List.of(not.child()), progress, lore, color, indent + 1);
        }
    }

    private void appendRewards(List<RewardDefinition> rewards, List<Component> lore) {
        long currency = rewards.stream().filter(reward -> reward.type() == RewardType.CURRENCY)
                .mapToLong(RewardDefinition::amount).sum();
        if (currency > 0L) {
            lore.add(ServerText.translatable("gui.omnitools.achievement.reward_coins", currency)
                    .withStyle(ChatFormatting.GOLD));
        }
        long makeupCards = rewards.stream().filter(reward -> reward.type() == RewardType.MAKEUP_CARD)
                .mapToLong(RewardDefinition::amount).sum();
        if (makeupCards > 0L) {
            lore.add(ServerText.translatable("gui.omnitools.reward.makeup_card", makeupCards)
                    .withStyle(ChatFormatting.AQUA));
        }
        for (RewardDefinition reward : rewards) {
            if (reward.type() == RewardType.ITEM) {
                ItemStack displayItem = TextTemplateRenderer.renderItemText(owner, reward.createItemStack());
                lore.add(ServerText.translatable("gui.omnitools.achievement.reward_item",
                        displayItem.getHoverName(), displayItem.getCount())
                        .withStyle(ChatFormatting.AQUA));
            } else if (reward.type() == RewardType.COMMAND) {
                lore.add(ServerText.translatable("gui.omnitools.achievement.reward_command")
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        appendTitleRewards(rewards.stream().filter(reward -> reward.type() == RewardType.TITLE)
                .map(RewardDefinition::titleId).toList(), lore);
    }

    private void appendPendingRewardReason(AchievementConfig.AchievementDefinition achievement, List<Component> lore) {
        RewardEvent event = RewardEvent.achievement(owner.getUUID(), achievement.id());
        RewardClaimLedger ledger = RewardClaimLedger.get(owner);
        if (!ledger.hasEvent(event) || ledger.allGranted(event, achievement.rewards())) {
            return;
        }
        for (RewardDefinition reward : achievement.rewards()) {
            RewardClaimLedger.Entry entry = ledger.entry(event, reward.id());
            if (entry.status() != RewardClaimLedger.EntryStatus.GRANTED) {
                lore.add(ServerText.translatable("gui.omnitools.reward.pending", entry.reason())
                        .withStyle(ChatFormatting.YELLOW));
                return;
            }
        }
    }

    private void appendTitleRewards(List<String> titleIds, List<Component> lore) {
        List<TitleConfig.TitleDefinition> definitions = titleIds.stream()
                .map(ModMindEntry.titleConfig()::definition)
                .flatMap(Optional::stream)
                .toList();
        if (definitions.isEmpty()) {
            return;
        }

        MutableComponent displays = Component.empty();
        for (int index = 0; index < definitions.size(); index++) {
            if (index > 0) {
                displays.append(Component.literal(", "));
            }
            displays.append(TextTemplateRenderer.render(owner, definitions.get(index).display()));
        }
        lore.add(ServerText.translatable("gui.omnitools.achievement.reward_titles", displays)
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        for (TitleConfig.TitleDefinition definition : definitions) {
            for (String tooltip : definition.tooltip()) {
                lore.add(Component.literal("  ")
                        .append(TextTemplateRenderer.render(owner, tooltip))
                        .withStyle(ChatFormatting.GRAY));
            }
            List<TitleEffectConfig.EffectDefinition> effects = ModMindEntry.titleConfig()
                    .effectsFor(definition, ModMindEntry.titleEffectConfig());
            if (effects.isEmpty()) {
                lore.add(Component.literal("  ")
                        .append(ServerText.translatable("gui.omnitools.title.no_effects"))
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
            int visibleEffects = Math.min(MAX_PREVIEW_EFFECTS, effects.size());
            for (int index = 0; index < visibleEffects; index++) {
                TitleEffectConfig.EffectDefinition effect = effects.get(index);
                String display = effect.display().isBlank() ? effect.name() : effect.display();
                lore.add(Component.literal("  ").append(TextTemplateRenderer.render(owner, display)));
            }
            if (effects.size() > visibleEffects) {
                lore.add(Component.literal("  ")
                        .append(ServerText.translatable("gui.omnitools.title.hidden_effects",
                                effects.size() - visibleEffects))
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
        }
    }

    private static boolean isCustomOnly(List<AchievementConfig.Requirement> requirements) {
        return !requirements.isEmpty()
                && requirements.stream().allMatch(requirement ->
                requirement.type() == AchievementConfig.RequirementType.CUSTOM);
    }

    private static boolean containsCustom(List<AchievementConfig.Requirement> requirements) {
        return requirements.stream().anyMatch(requirement ->
                requirement.type() == AchievementConfig.RequirementType.CUSTOM);
    }

    private static Component customProgressLine(long current, long target, long divisor, String unit) {
        MutableComponent progress = ServerText.translatable("gui.omnitools.achievement.progress",
                formatProgressValue(current, divisor), formatProgressValue(target, divisor));
        if (unit == null || unit.isBlank()) {
            return progress;
        }
        return progress.append(Component.literal(" "))
                .append(ServerText.translatable("gui.omnitools.achievement.unit." + unit));
    }

    private static String formatProgressValue(long value, long divisor) {
        if (divisor <= 1L) {
            return Long.toString(value);
        }
        long whole = value / divisor;
        long hundredths = (value % divisor) * 100L / divisor;
        if (hundredths == 0L) {
            return Long.toString(whole);
        }
        if (hundredths % 10L == 0L) {
            return whole + "." + (hundredths / 10L);
        }
        return whole + "." + String.format(java.util.Locale.ROOT, "%02d", hundredths);
    }

    private void appendChildren(List<AchievementCondition> children, ConditionProgress progress,
                                List<Component> lore, ChatFormatting color, int indent) {
        for (int index = 0; index < children.size(); index++) {
            ConditionProgress childProgress = index < progress.children().size()
                    ? progress.children().get(index) : ConditionProgress.leaf(0, 0, false);
            appendConditionLore(children.get(index), childProgress, lore, color, indent);
        }
    }

    private Component statLine(AchievementConfig.Requirement requirement, long current, long target) {
        return ServerText.translatable(requirement.type().translationKey(), targetName(requirement), current, target);
    }

    private Component sumProgressLine(List<AchievementConfig.Requirement> requirements, long current, long target,
                                      String namedTargetsTranslationKey) {
        if (requirements.size() > MAX_NAMED_TARGETS) {
            return ServerText.translatable("gui.omnitools.achievement.condition.aggregate",
                    requirements.size(), current, target);
        }
        return ServerText.translatable(namedTargetsTranslationKey, joinTargetNames(requirements), current, target);
    }

    private void appendIncompleteStatTargets(StatCondition stat, ConditionProgress progress, List<Component> lore,
                                             ChatFormatting color, int indent) {
        List<Integer> incomplete = new ArrayList<>();
        for (int index = 0; index < stat.requirements().size(); index++) {
            ConditionProgress child = index < progress.children().size()
                    ? progress.children().get(index) : ConditionProgress.leaf(0, stat.atLeast(), false);
            if (!child.completed()) {
                incomplete.add(index);
            }
        }
        int visible = Math.min(MAX_VISIBLE_EACH_TARGETS, incomplete.size());
        for (int displayed = 0; displayed < visible; displayed++) {
            int index = incomplete.get(displayed);
            ConditionProgress child = index < progress.children().size()
                    ? progress.children().get(index) : ConditionProgress.leaf(0, stat.atLeast(), false);
            lore.add(Component.literal("  ".repeat(Math.max(0, indent)))
                    .append(statLine(stat.requirements().get(index), child.current(), stat.atLeast()))
                    .withStyle(color));
        }
        int hidden = incomplete.size() - visible;
        if (hidden > 0) {
            lore.add(Component.literal("  ".repeat(Math.max(0, indent)))
                    .append(ServerText.translatable("gui.omnitools.achievement.condition.hidden_targets", hidden))
                    .withStyle(color));
        }
    }

    private Component joinTargetNames(List<AchievementConfig.Requirement> requirements) {
        MutableComponent joined = Component.empty();
        int visible = Math.min(MAX_NAMED_TARGETS, requirements.size());
        for (int index = 0; index < visible; index++) {
            if (index > 0) {
                joined.append(ServerText.translatable("gui.omnitools.achievement.target.separator"));
            }
            joined.append(targetName(requirements.get(index)));
        }
        int hidden = requirements.size() - visible;
        if (hidden > 0) {
            joined.append(ServerText.translatable("gui.omnitools.achievement.target.separator"));
            joined.append(ServerText.translatable("gui.omnitools.achievement.condition.hidden_targets", hidden));
        }
        return joined;
    }

    private Component targetName(AchievementConfig.Requirement requirement) {
        return switch (requirement.type().domain()) {
            case BLOCK -> ((net.minecraft.world.level.block.Block) requirement.target()).getName();
            case ITEM -> ((Item) requirement.target()).getName();
            case ENTITY -> ((net.minecraft.world.entity.EntityType<?>) requirement.target()).getDescription();
            case CUSTOM -> Component.literal(requirement.targetId());
        };
    }

    private static String stateNameTranslationKey(AchievementDisplayState displayState) {
        return switch (displayState) {
            case LOCKED -> "gui.omnitools.achievement.state.locked";
            case IN_PROGRESS -> "gui.omnitools.achievement.state.in_progress";
            case CLAIMABLE -> "gui.omnitools.achievement.state.claimable";
            case CLAIMED -> "gui.omnitools.achievement.state.claimed";
        };
    }

    private static String stateFooterTranslationKey(AchievementDisplayState displayState) {
        return switch (displayState) {
            case LOCKED -> "gui.omnitools.achievement.state.locked";
            case IN_PROGRESS -> "gui.omnitools.achievement.in_progress";
            case CLAIMABLE -> "gui.omnitools.achievement.available";
            case CLAIMED -> "gui.omnitools.achievement.claimed_status";
        };
    }

    private enum AchievementFilter {
        ALL("gui.omnitools.achievement.filter_all"),
        COMPLETED("gui.omnitools.achievement.filter_completed"),
        CLAIMABLE("gui.omnitools.achievement.filter_claimable");

        private final String translationKey;

        AchievementFilter(String translationKey) {
            this.translationKey = translationKey;
        }

        public boolean includes(AchievementDisplayState displayState) {
            return switch (this) {
                case ALL -> true;
                case COMPLETED -> displayState.isCompleted();
                case CLAIMABLE -> displayState.isClaimable();
            };
        }

        public String translationKey() {
            return translationKey;
        }
    }

    private static int pageCount(int achievementCount) {
        return Math.max(1, (achievementCount + ACHIEVEMENT_SLOTS - 1) / ACHIEVEMENT_SLOTS);
    }

}
