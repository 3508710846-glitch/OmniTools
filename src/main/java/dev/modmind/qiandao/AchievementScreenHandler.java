package dev.modmind.qiandao;

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
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.ArrayList;
import java.util.List;
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
    private int stateHash = Integer.MIN_VALUE;

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
            service.check(owner);
            refreshContents();
        }
    }

    public static AchievementScreenHandler createServer(int syncId, Inventory inventory, ServerPlayer owner,
                                                        AchievementService service, int page) {
        return new AchievementScreenHandler(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), owner, service,
                page);
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (!ModMindEntry.isModuleEnabled(dev.modmind.qiandao.config.ModuleId.ACHIEVEMENTS)) {
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
                    "message.qiandao.achievement.claimed", achievement.display(), achievement.rewards().coins(),
                    result.grantedTitles(), result.balance()), true);
            case ALREADY_CLAIMED -> serverPlayer.displayClientMessage(Component.translatable(
                    "message.qiandao.achievement.already_claimed"), true);
            case NOT_COMPLETED -> serverPlayer.displayClientMessage(Component.translatable(
                    "message.qiandao.achievement.not_completed"), true);
            case UNKNOWN_ACHIEVEMENT -> serverPlayer.displayClientMessage(Component.translatable(
                    "message.qiandao.achievement.unknown"), true);
        }
        refreshContents();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void broadcastChanges() {
        if (owner != null && service != null && service.stateHash(owner) != stateHash) {
            refreshContents();
        }
        super.broadcastChanges();
    }

    private void refreshContents() {
        service.check(owner);
        AchievementConfig config = service.config();
        List<AchievementConfig.AchievementDefinition> achievements = config.achievements();
        int pageCount = pageCount(achievements.size());
        page = Math.max(0, Math.min(page, pageCount - 1));

        for (int slot = 0; slot < CONTAINER_SIZE; slot++) {
            achievementContainer.setItem(slot, filler());
        }

        if (achievements.isEmpty()) {
            achievementContainer.setItem(22, namedItem(Items.BOOK,
                    Component.translatable("gui.qiandao.achievement.empty_title").withStyle(ChatFormatting.GRAY),
                    List.of(Component.translatable("gui.qiandao.achievement.empty_hint")
                            .withStyle(ChatFormatting.DARK_GRAY))));
        }

        int firstAchievement = page * ACHIEVEMENT_SLOTS;
        for (int slot = 0; slot < ACHIEVEMENT_SLOTS && firstAchievement + slot < achievements.size(); slot++) {
            AchievementConfig.AchievementDefinition achievement = achievements.get(firstAchievement + slot);
            achievementContainer.setItem(slot, achievementItem(achievement));
        }

        if (page > 0) {
            achievementContainer.setItem(PREVIOUS_PAGE_SLOT, namedItem(Items.ARROW,
                    Component.translatable("gui.qiandao.achievement.previous").withStyle(ChatFormatting.AQUA),
                    List.of(Component.translatable("gui.qiandao.achievement.previous_hint")
                            .withStyle(ChatFormatting.GRAY))));
        }
        achievementContainer.setItem(PROFILE_SLOT, profileItem(achievements.size(), page + 1, pageCount));
        if (page + 1 < pageCount) {
            achievementContainer.setItem(NEXT_PAGE_SLOT, namedItem(Items.ARROW,
                    Component.translatable("gui.qiandao.achievement.next").withStyle(ChatFormatting.AQUA),
                    List.of(Component.translatable("gui.qiandao.achievement.next_hint")
                            .withStyle(ChatFormatting.GRAY))));
        }
        stateHash = service.stateHash(owner);
    }

    private ItemStack achievementItem(AchievementConfig.AchievementDefinition achievement) {
        AchievementService.State state = service.state(owner, achievement);
        ChatFormatting color = stateColor(achievement, state);
        ItemStack item = new ItemStack(achievement.icon());
        if (state == AchievementService.State.CLAIMED) {
            item.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        item.set(DataComponents.CUSTOM_NAME, LegacyTitleText.parse(achievement.display()).copy()
                .withStyle(color, ChatFormatting.BOLD));
        List<Component> lore = new ArrayList<>();
        lore.add(LegacyTitleText.parse(achievement.description()).copy().withStyle(ChatFormatting.GRAY));
        for (AchievementConfig.Requirement requirement : achievement.requirements()) {
            long progress = Math.min(requirement.current(owner), requirement.count());
            lore.add(Component.translatable(requirement.type().translationKey(), requirement.targetId(), progress,
                    requirement.count()).withStyle(color));
        }
        AchievementConfig.Reward reward = achievement.rewards();
        if (reward.coins() > 0L) {
            lore.add(Component.translatable("gui.qiandao.achievement.reward_coins", reward.coins())
                    .withStyle(ChatFormatting.GOLD));
        }
        if (!reward.titles().isEmpty()) {
            lore.add(Component.translatable("gui.qiandao.achievement.reward_titles",
                    String.join(", ", reward.titles())).withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        lore.add(Component.translatable(stateTranslationKey(state)).withStyle(color));
        lore.add(Component.literal(achievement.id()).withStyle(ChatFormatting.DARK_GRAY));
        item.set(DataComponents.LORE, new ItemLore(lore));
        return item;
    }

    private ItemStack profileItem(int totalAchievements, int pageNumber, int pageCount) {
        ItemStack profile = new ItemStack(Items.PLAYER_HEAD);
        profile.set(DataComponents.PROFILE, ResolvableProfile.createResolved(owner.getGameProfile()));
        profile.set(DataComponents.CUSTOM_NAME, Component.translatable("gui.qiandao.achievement.profile")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        profile.set(DataComponents.LORE, new ItemLore(List.of(
                Component.translatable("gui.qiandao.achievement.completed", service.unlockedCount(owner))
                        .withStyle(ChatFormatting.AQUA),
                Component.translatable("gui.qiandao.achievement.claimed", service.claimedCount(owner))
                        .withStyle(ChatFormatting.GREEN),
                Component.translatable("gui.qiandao.achievement.total", totalAchievements)
                        .withStyle(ChatFormatting.GRAY),
                Component.translatable("gui.qiandao.achievement.page", pageNumber, pageCount)
                        .withStyle(ChatFormatting.DARK_GRAY))));
        return profile;
    }

    private ChatFormatting stateColor(AchievementConfig.AchievementDefinition achievement,
                                      AchievementService.State state) {
        if (state == AchievementService.State.CLAIMED) {
            return ChatFormatting.GOLD;
        }
        if (state == AchievementService.State.CLAIMABLE) {
            return ChatFormatting.GREEN;
        }
        return achievement.requirements().stream().anyMatch(requirement -> requirement.current(owner) > 0L)
                ? ChatFormatting.RED : ChatFormatting.DARK_GRAY;
    }

    private static String stateTranslationKey(AchievementService.State state) {
        return switch (state) {
            case IN_PROGRESS -> "gui.qiandao.achievement.in_progress";
            case CLAIMABLE -> "gui.qiandao.achievement.available";
            case CLAIMED -> "gui.qiandao.achievement.claimed_status";
        };
    }

    private static int pageCount(int achievementCount) {
        return Math.max(1, (achievementCount + ACHIEVEMENT_SLOTS - 1) / ACHIEVEMENT_SLOTS);
    }

    private static ItemStack filler() {
        return namedItem(Items.GRAY_STAINED_GLASS_PANE, Component.translatable("gui.qiandao.empty"), List.of());
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
