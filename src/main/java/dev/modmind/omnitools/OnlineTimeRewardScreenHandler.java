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
import net.minecraft.world.flag.FeatureFlags;

import java.util.List;
import java.util.UUID;

/** Server-authoritative menu for daily online-time rewards. */
public final class OnlineTimeRewardScreenHandler extends ChestMenu {
    public static final int ROWS = 3;
    public static final int CONTAINER_SIZE = ROWS * 9;
    private static final List<Integer> REWARD_SLOTS = List.of(11, 13, 15);
    public static final MenuType<OnlineTimeRewardScreenHandler> TYPE = Registry.register(
            BuiltInRegistries.MENU,
            Identifier.fromNamespaceAndPath(ModMindEntry.MOD_ID, "online_time_rewards"),
            new MenuType<>(OnlineTimeRewardScreenHandler::new, FeatureFlags.DEFAULT_FLAGS));

    private final SimpleContainer rewardContainer;
    private final UUID ownerId;
    private final ServerPlayer owner;
    private int displayedOnlineMinutes = -1;

    public static void register() {
        // Loading this class registers TYPE before the client creates its screen.
    }

    public OnlineTimeRewardScreenHandler(int syncId, Inventory inventory) {
        this(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), null);
    }

    private OnlineTimeRewardScreenHandler(int syncId, Inventory inventory, SimpleContainer container,
                                          ServerPlayer owner) {
        super(TYPE, syncId, inventory, container, ROWS);
        this.rewardContainer = container;
        this.ownerId = owner == null ? null : owner.getUUID();
        this.owner = owner;
        if (owner != null) {
            refreshContents(owner, getOnlineMinutes(owner));
        }
    }

    public static OnlineTimeRewardScreenHandler createServer(int syncId, Inventory inventory, ServerPlayer owner) {
        return new OnlineTimeRewardScreenHandler(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), owner);
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (!ModMindEntry.isModuleEnabled(dev.modmind.omnitools.config.ModuleId.ONLINE_REWARD)
                || (player instanceof ServerPlayer serverPlayerForPermission
                && !ModMindEntry.hasCommandPermission(serverPlayerForPermission,
                dev.modmind.omnitools.permissions.CommandAction.ONLINE_OPEN))) {
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.closeContainer();
            }
            return;
        }
        // Keep the player's inventory usable, but never let UI items be moved out of the menu.
        if (slotId < 0 || slotId >= CONTAINER_SIZE) {
            super.clicked(slotId, button, clickType, player);
            return;
        }
        if (!(player instanceof ServerPlayer serverPlayer) || ownerId == null
                || !ownerId.equals(serverPlayer.getUUID()) || clickType != ClickType.PICKUP) {
            return;
        }

        int rewardIndex = REWARD_SLOTS.indexOf(slotId);
        if (rewardIndex < 0) {
            return;
        }
        List<CheckinRewardConfig.OnlineTimeReward> rewards = ModMindEntry.rewardService().onlineTimeRewards();
        if (rewardIndex >= rewards.size()) {
            return;
        }
        CheckinRewardConfig.OnlineTimeReward reward = rewards.get(rewardIndex);
        OnlineTimeRewardService.ClaimResult result = ModMindEntry.onlineTimeRewardService()
                .claim(serverPlayer, rewardIndex, reward);
        refreshContents(serverPlayer, getOnlineMinutes(serverPlayer));
        broadcastChanges();

        if (result.status() == OnlineTimeRewardService.ClaimStatus.CLAIMED) {
            serverPlayer.displayClientMessage(Component.translatable(
                    "message.omnitools.online_reward.claimed", reward.minutes(), reward.coins(), result.balance()), true);
            serverPlayer.level().getServer().getPlayerList().broadcastSystemMessage(Component.translatable(
                    "message.omnitools.online_reward.broadcast", serverPlayer.getName(), reward.minutes(), reward.coins()),
                    false);
        } else if (result.status() == OnlineTimeRewardService.ClaimStatus.ALREADY_CLAIMED) {
            serverPlayer.displayClientMessage(Component.translatable(
                    "message.omnitools.online_reward.already_claimed"), true);
        } else {
            serverPlayer.displayClientMessage(Component.translatable(
                    "message.omnitools.online_reward.not_ready"), true);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void broadcastChanges() {
        if (ownerId != null) {
            if (owner != null) {
                int onlineMinutes = getOnlineMinutes(owner);
                if (onlineMinutes != displayedOnlineMinutes) {
                    refreshContents(owner, onlineMinutes);
                }
            }
        }
        super.broadcastChanges();
    }

    private void refreshContents(ServerPlayer owner, int onlineMinutes) {
        this.displayedOnlineMinutes = onlineMinutes;
        for (int slot = 0; slot < CONTAINER_SIZE; slot++) {
            ItemStack filler = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
            filler.set(DataComponents.CUSTOM_NAME, Component.translatable("gui.omnitools.empty"));
            rewardContainer.setItem(slot, filler);
        }

        long day = CheckinData.today(owner.level().getServer()).toEpochDay();
        CheckinData data = CheckinData.get(owner);
        List<CheckinRewardConfig.OnlineTimeReward> rewards = ModMindEntry.rewardService().onlineTimeRewards();
        for (int index = 0; index < REWARD_SLOTS.size() && index < rewards.size(); index++) {
            CheckinRewardConfig.OnlineTimeReward reward = rewards.get(index);
            boolean claimed = data.hasClaimedOnlineTimeReward(owner.getUUID(), day, reward.id(), index);
            boolean canClaim = !claimed && onlineMinutes >= reward.minutes();
            ChatFormatting stateColor = claimed ? ChatFormatting.GOLD
                    : canClaim ? ChatFormatting.GREEN : ChatFormatting.RED;
            int progress = Math.min(onlineMinutes, reward.minutes());

            ItemStack rewardStack = new ItemStack(Items.CLOCK);
            if (claimed) {
                rewardStack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            }
            rewardStack.set(DataComponents.CUSTOM_NAME, Component.translatable(
                    "gui.omnitools.online_reward.title", reward.minutes()).withStyle(stateColor, ChatFormatting.BOLD));
            rewardStack.set(DataComponents.LORE, new ItemLore(List.of(
                    Component.translatable("gui.omnitools.online_reward.progress", progress, reward.minutes())
                            .withStyle(stateColor),
                    Component.translatable(claimed ? "gui.omnitools.online_reward.claimed"
                            : canClaim ? "gui.omnitools.online_reward.available"
                            : "gui.omnitools.online_reward.unavailable").withStyle(stateColor),
                    Component.translatable("gui.omnitools.online_reward.coins", reward.coins())
                            .withStyle(ChatFormatting.GOLD))));
            rewardContainer.setItem(REWARD_SLOTS.get(index), rewardStack);
        }
    }

    private static int getOnlineMinutes(ServerPlayer player) {
        long onlineMillis = ModMindEntry.onlineTimeRewardService().getTodayOnlineTime(player);
        return (int) Math.min(Integer.MAX_VALUE, onlineMillis / 60_000L);
    }
}
