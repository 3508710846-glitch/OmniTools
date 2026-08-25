package dev.modmind.omnitools;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
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
    public static final int ACHIEVEMENT_SLOTS = 45;
    public static final int PREVIOUS_PAGE_SLOT = 45;
    public static final int PROFILE_SLOT = 49;
    public static final int NEXT_PAGE_SLOT = 53;
    public static final MenuType<AchievementScreenHandler> TYPE = Registry.register(
            BuiltInRegistries.MENU,
            Identifier.fromNamespaceAndPath(ModMindEntry.MOD_ID, "achievements"),
            new MenuType<>(AchievementScreenHandler::new, FeatureFlags.DEFAULT_FLAGS));

    private final SimpleContainer achievementContainer;
    private final UUID ownerId;
    private final ServerPlayer owner;
    private final AchievementService service;
    private int page;
    private long lastRefreshTick = Long.MIN_VALUE;
    private int lastConfigRevision = Integer.MIN_VALUE;

    public static void register() {
        // Loading this class registers TYPE before the client creates its screen.
    }

    public AchievementScreenHandler(int syncId, Inventory inventory) {
        this(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), null, null, 0);
    }

    private AchievementScreenHandler(int syncId, Inventory inventory, SimpleContainer container,
                                     ServerPlayer owner, AchievementService service, int page) {
        super(TYPE, syncId, inventory, container, ROWS);
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
        int pageCount = pageCount(achievements.size());
        if (slotId == PREVIOUS_PAGE_SLOT && page > 0) {
            page--;
            refreshContents();
            return;
        }
        if (slotId == NEXT_PAGE_SLOT && page + 1 < pageCount) {
            page++;
            refreshContents();
            return;
        }
        if (slotId >= ACHIEVEMENT_SLOTS) {
            return;
        }

        int achievementIndex = page * ACHIEVEMENT_SLOTS + slotId;
        if (achievementIndex >= achievements.size()) {
            return;
        }
        AchievementConfig.AchievementDefinition achievement = achievements.get(achievementIndex);
        AchievementService.ClaimResult result = service.claim(serverPlayer, achievement.id());
        switch (result.status()) {
            case CLAIMED -> serverPlayer.displayClientMessage(Component.translatable(
                    "message.omnitools.achievement.claimed", achievement.display(), result.grantedRewards(),
                    result.balance()), true);
            case ALREADY_CLAIMED -> serverPlayer.displayClientMessage(Component.translatable(
                    "message.omnitools.achievement.already_claimed"), true);
            case NOT_COMPLETED -> serverPlayer.displayClientMessage(Component.translatable(
                    "message.omnitools.achievement.not_completed"), true);
            case UNKNOWN_ACHIEVEMENT -> serverPlayer.displayClientMessage(Component.translatable(
                    "message.omnitools.achievement.unknown"), true);
            case PENDING, BLOCKED, FAILED -> serverPlayer.displayClientMessage(Component.translatable(
                    "message.omnitools.achievement.reward_pending", result.reason()), true);
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
        int pageCount = pageCount(achievements.size());
        page = Math.max(0, Math.min(page, pageCount - 1));

        for (int slot = 0; slot < CONTAINER_SIZE; slot++) {
            achievementContainer.setItem(slot, filler());
        }

        if (achievements.isEmpty()) {
            achievementContainer.setItem(22, namedItem(Items.BOOK,
                    Component.translatable("gui.omnitools.achievement.empty_title").withStyle(ChatFormatting.GRAY),
                    List.of(Component.translatable("gui.omnitools.achievement.empty_hint")
                            .withStyle(ChatFormatting.DARK_GRAY))));
        }

        int firstAchievement = page * ACHIEVEMENT_SLOTS;
        for (int slot = 0; slot < ACHIEVEMENT_SLOTS && firstAchievement + slot < achievements.size(); slot++) {
            AchievementConfig.AchievementDefinition achievement = achievements.get(firstAchievement + slot);
            AchievementService.Evaluation evaluation = menuSnapshot.evaluation(achievement.id());
            achievementContainer.setItem(slot, achievementItem(achievement, evaluation));
        }

        if (page > 0) {
            achievementContainer.setItem(PREVIOUS_PAGE_SLOT, namedItem(Items.ARROW,
                    Component.translatable("gui.omnitools.achievement.previous").withStyle(ChatFormatting.AQUA),
                    List.of(Component.translatable("gui.omnitools.achievement.previous_hint")
                            .withStyle(ChatFormatting.GRAY))));
        }
        achievementContainer.setItem(PROFILE_SLOT, profileItem(achievements.size(), page + 1, pageCount));
        if (page + 1 < pageCount) {
            achievementContainer.setItem(NEXT_PAGE_SLOT, namedItem(Items.ARROW,
                    Component.translatable("gui.omnitools.achievement.next").withStyle(ChatFormatting.AQUA),
                    List.of(Component.translatable("gui.omnitools.achievement.next_hint")
                            .withStyle(ChatFormatting.GRAY))));
        }
        lastRefreshTick = owner.level().getServer().getTickCount();
        lastConfigRevision = service.revision();
    }

    private ItemStack achievementItem(AchievementConfig.AchievementDefinition achievement,
                                      AchievementService.Evaluation evaluation) {
        ConditionProgress progress = evaluation.progress();
        AchievementService.State state = evaluation.state();
        ChatFormatting color = stateColor(achievement, state, progress);
        ItemStack item = new ItemStack(achievement.icon());
        if (state == AchievementService.State.CLAIMED) {
            item.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        item.set(DataComponents.CUSTOM_NAME, LegacyTitleText.parse(achievement.display()).copy()
                .withStyle(color, ChatFormatting.BOLD));
        List<Component> lore = new ArrayList<>();
        lore.add(LegacyTitleText.parse(achievement.description()).copy().withStyle(ChatFormatting.GRAY));
        appendConditionLore(achievement.condition(), progress, lore, color, 0);
        appendRewards(achievement.rewards(), lore);
        appendPendingRewardReason(achievement, lore);
        lore.add(Component.translatable(stateTranslationKey(state)).withStyle(color));
        lore.add(Component.literal(achievement.id()).withStyle(ChatFormatting.DARK_GRAY));
        item.set(DataComponents.LORE, new ItemLore(lore));
        return item;
    }

    private ItemStack profileItem(int totalAchievements, int pageNumber, int pageCount) {
        ItemStack profile = new ItemStack(Items.PLAYER_HEAD);
        profile.set(DataComponents.PROFILE, ResolvableProfile.createResolved(owner.getGameProfile()));
        profile.set(DataComponents.CUSTOM_NAME, Component.translatable("gui.omnitools.achievement.profile")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        profile.set(DataComponents.LORE, new ItemLore(List.of(
                Component.translatable("gui.omnitools.achievement.completed", service.unlockedCount(owner))
                        .withStyle(ChatFormatting.AQUA),
                Component.translatable("gui.omnitools.achievement.claimed", service.claimedCount(owner))
                        .withStyle(ChatFormatting.GREEN),
                Component.translatable("gui.omnitools.achievement.total", totalAchievements)
                        .withStyle(ChatFormatting.GRAY),
                Component.translatable("gui.omnitools.achievement.page", pageNumber, pageCount)
                        .withStyle(ChatFormatting.DARK_GRAY))));
        return profile;
    }

    private ChatFormatting stateColor(AchievementConfig.AchievementDefinition achievement,
                                      AchievementService.State state, ConditionProgress progress) {
        if (state == AchievementService.State.CLAIMED) {
            return ChatFormatting.GOLD;
        }
        if (state == AchievementService.State.CLAIMABLE) {
            return ChatFormatting.GREEN;
        }
        if (state == AchievementService.State.PENDING) {
            return ChatFormatting.YELLOW;
        }
        return hasProgress(progress)
                ? ChatFormatting.RED : ChatFormatting.DARK_GRAY;
    }

    private static boolean hasProgress(ConditionProgress progress) {
        if (progress.current() > 0L) {
            return true;
        }
        return progress.children().stream().anyMatch(AchievementScreenHandler::hasProgress);
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
                    lore.add(prefix.copy().append(Component.translatable(
                            "gui.omnitools.achievement.condition.stat_sum",
                            joinTargetNames(stat.requirements()), progress.current(), stat.atLeast()))
                            .withStyle(color));
                }
            } else {
                lore.add(prefix.copy().append(Component.translatable(
                        stat.match() == TargetMatch.EACH
                                ? "gui.omnitools.achievement.match.each"
                                : "gui.omnitools.achievement.match.any",
                        progress.current(), progress.target())).withStyle(color));
                for (int index = 0; index < stat.requirements().size(); index++) {
                    ConditionProgress child = index < progress.children().size()
                            ? progress.children().get(index) : ConditionProgress.leaf(0, stat.atLeast(), false);
                    lore.add(Component.literal("  ".repeat(Math.max(0, indent + 1)))
                            .append(statLine(stat.requirements().get(index), child.current(), stat.atLeast()))
                            .withStyle(color));
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
            lore.add(prefix.copy().append(Component.translatable(
                    "gui.omnitools.achievement.condition.sum", joinTargetNames(sum.requirements()),
                    progress.current(), sum.atLeast())).withStyle(color));
            return;
        }
        if (condition instanceof AllCondition all) {
            lore.add(prefix.copy().append(Component.translatable(
                    "gui.omnitools.achievement.condition.all", progress.current(), progress.target()))
                    .withStyle(color));
            appendChildren(all.children(), progress, lore, color, indent + 1);
            return;
        }
        if (condition instanceof AnyCondition any) {
            lore.add(prefix.copy().append(Component.translatable(
                    "gui.omnitools.achievement.condition.any", progress.current(), progress.target()))
                    .withStyle(color));
            appendChildren(any.children(), progress, lore, color, indent + 1);
            return;
        }
        if (condition instanceof NotCondition not) {
            lore.add(prefix.copy().append(Component.translatable(
                    "gui.omnitools.achievement.condition.not", progress.current(), progress.target()))
                    .withStyle(color));
            appendChildren(List.of(not.child()), progress, lore, color, indent + 1);
        }
    }

    private void appendRewards(List<RewardDefinition> rewards, List<Component> lore) {
        long currency = rewards.stream().filter(reward -> reward.type() == RewardType.CURRENCY)
                .mapToLong(RewardDefinition::amount).sum();
        if (currency > 0L) {
            lore.add(Component.translatable("gui.omnitools.achievement.reward_coins", currency)
                    .withStyle(ChatFormatting.GOLD));
        }
        for (RewardDefinition reward : rewards) {
            if (reward.type() == RewardType.ITEM) {
                lore.add(Component.translatable("gui.omnitools.achievement.reward_item",
                        reward.createItemStack().getHoverName(), reward.createItemStack().getCount())
                        .withStyle(ChatFormatting.AQUA));
            } else if (reward.type() == RewardType.COMMAND) {
                lore.add(Component.translatable("gui.omnitools.achievement.reward_command")
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
                lore.add(Component.translatable("gui.omnitools.reward.pending", entry.reason())
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
            displays.append(definitions.get(index).displayComponent());
        }
        lore.add(Component.translatable("gui.omnitools.achievement.reward_titles", displays)
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        for (TitleConfig.TitleDefinition definition : definitions) {
            for (String tooltip : definition.tooltip()) {
                lore.add(Component.literal("  ")
                        .append(LegacyTitleText.parse(tooltip))
                        .withStyle(ChatFormatting.GRAY));
            }
            for (String effectId : definition.effects()) {
                ModMindEntry.titleEffectConfig().definition(effectId)
                        .map(TitleEffectConfig.EffectDefinition::display)
                        .map(LegacyTitleText::parse)
                        .ifPresent(effect -> lore.add(Component.literal("  ").append(effect)));
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
        MutableComponent progress = Component.translatable("gui.omnitools.achievement.progress",
                formatProgressValue(current, divisor), formatProgressValue(target, divisor));
        if (unit == null || unit.isBlank()) {
            return progress;
        }
        return progress.append(Component.literal(" "))
                .append(Component.translatable("gui.omnitools.achievement.unit." + unit));
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
        return Component.translatable(requirement.type().translationKey(), targetName(requirement), current, target);
    }

    private Component joinTargetNames(List<AchievementConfig.Requirement> requirements) {
        MutableComponent joined = Component.empty();
        for (int index = 0; index < requirements.size(); index++) {
            if (index > 0) {
                joined.append(Component.translatable("gui.omnitools.achievement.target.separator"));
            }
            joined.append(targetName(requirements.get(index)));
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

    private static String stateTranslationKey(AchievementService.State state) {
        return switch (state) {
            case IN_PROGRESS -> "gui.omnitools.achievement.in_progress";
            case CLAIMABLE -> "gui.omnitools.achievement.available";
            case PENDING -> "gui.omnitools.achievement.pending";
            case CLAIMED -> "gui.omnitools.achievement.claimed_status";
        };
    }

    private static int pageCount(int achievementCount) {
        return Math.max(1, (achievementCount + ACHIEVEMENT_SLOTS - 1) / ACHIEVEMENT_SLOTS);
    }

    private static ItemStack filler() {
        return namedItem(Items.GRAY_STAINED_GLASS_PANE, Component.translatable("gui.omnitools.empty"), List.of());
    }

    private static ItemStack namedItem(Item item, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, name);
        if (!lore.isEmpty()) {
            stack.set(DataComponents.LORE, new ItemLore(lore));
        }
        return stack;
    }
}
