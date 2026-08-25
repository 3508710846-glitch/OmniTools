package dev.modmind.omnitools;

import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.permissions.CommandAction;
import dev.modmind.omnitools.reward.RewardDefinition;
import dev.modmind.omnitools.reward.RewardType;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Server-authoritative, paged original-client menu for daily online-time rewards. */
public final class OnlineTimeRewardScreenHandler extends ChestMenu {
    public static final int ROWS = 3;
    public static final int CONTAINER_SIZE = ROWS * 9;
    private static final List<Integer> REWARD_SLOTS = List.of(10, 11, 12, 13, 14, 15, 16);
    private static final int PREVIOUS_PAGE_SLOT = 18;
    private static final int PAGE_INFO_SLOT = 22;
    private static final int NEXT_PAGE_SLOT = 26;

    private final SimpleContainer rewardContainer;
    private final UUID ownerId;
    private final ServerPlayer owner;
    private int displayedOnlineMinutes = -1;
    private int page;
    private int pageCount = 1;
    private long lastRevision = Long.MIN_VALUE;
    private long lastRefreshCheckTick = Long.MIN_VALUE;

    public OnlineTimeRewardScreenHandler(int syncId, Inventory inventory) {
        this(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), null, 0);
    }

    private OnlineTimeRewardScreenHandler(int syncId, Inventory inventory, SimpleContainer container,
                                          ServerPlayer owner, int page) {
        super(MenuType.GENERIC_9x3, syncId, inventory, container, ROWS);
        this.rewardContainer = container;
        this.ownerId = owner == null ? null : owner.getUUID();
        this.owner = owner;
        this.page = Math.max(0, page);
        if (owner != null) {
            refreshContents(owner, getOnlineMinutes(owner));
        }
    }

    public static OnlineTimeRewardScreenHandler createServer(int syncId, Inventory inventory, ServerPlayer owner) {
        return new OnlineTimeRewardScreenHandler(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), owner, 0);
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (!ModMindEntry.isModuleEnabled(ModuleId.ONLINE_REWARD)
                || (player instanceof ServerPlayer serverPlayerForPermission
                && !ModMindEntry.hasCommandPermission(serverPlayerForPermission, CommandAction.ONLINE_OPEN))) {
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
        if (slotId == PREVIOUS_PAGE_SLOT && page > 0) {
            page--;
            refreshContents(serverPlayer, getOnlineMinutes(serverPlayer));
            return;
        }
        if (slotId == NEXT_PAGE_SLOT && page + 1 < pageCount) {
            page++;
            refreshContents(serverPlayer, getOnlineMinutes(serverPlayer));
            return;
        }

        int localIndex = REWARD_SLOTS.indexOf(slotId);
        if (localIndex < 0) {
            return;
        }
        int rewardIndex = page * REWARD_SLOTS.size() + localIndex;
        List<CheckinRewardConfig.OnlineTimeReward> rewards = ModMindEntry.rewardService().onlineTimeRewards();
        if (rewardIndex >= rewards.size()) {
            return;
        }
        CheckinRewardConfig.OnlineTimeReward reward = rewards.get(rewardIndex);
        OnlineTimeRewardService.RewardStatus state = ModMindEntry.onlineTimeRewardService()
                .status(serverPlayer, rewardIndex, reward);
        if (state != OnlineTimeRewardService.RewardStatus.AVAILABLE
                && state != OnlineTimeRewardService.RewardStatus.PENDING) {
            return;
        }
        OnlineTimeRewardService.ClaimResult result = ModMindEntry.onlineTimeRewardService()
                .claim(serverPlayer, rewardIndex, reward);
        refreshContents(serverPlayer, getOnlineMinutes(serverPlayer));
        broadcastChanges();

        if (result.status() == OnlineTimeRewardService.ClaimStatus.CLAIMED) {
            serverPlayer.displayClientMessage(ServerText.translatable(
                    "message.omnitools.online_reward.claimed", reward.minutes(),
                    result.granted() + result.alreadyGranted(), result.balance()), true);
            if (result.granted() > 0) {
                serverPlayer.level().getServer().getPlayerList().broadcastSystemMessage(ServerText.translatable(
                        "message.omnitools.online_reward.broadcast", serverPlayer.getName(), reward.minutes()), false);
            }
        } else if (result.status() == OnlineTimeRewardService.ClaimStatus.PENDING
                || result.status() == OnlineTimeRewardService.ClaimStatus.BLOCKED
                || result.status() == OnlineTimeRewardService.ClaimStatus.FAILED) {
            serverPlayer.displayClientMessage(ServerText.translatable("message.omnitools.reward.pending",
                    reward.id(), result.reason()), true);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void broadcastChanges() {
        if (ownerId != null && owner != null) {
            if (!ModMindEntry.isModuleEnabled(ModuleId.ONLINE_REWARD)
                    || !ModMindEntry.hasCommandPermission(owner, CommandAction.ONLINE_OPEN)) {
                owner.closeContainer();
                return;
            }
            long tick = owner.level().getServer().getTickCount();
            if (lastRefreshCheckTick == Long.MIN_VALUE || tick - lastRefreshCheckTick >= 20L
                    || lastRevision != ModMindEntry.configSnapshot().revision()) {
                lastRefreshCheckTick = tick;
                int onlineMinutes = getOnlineMinutes(owner);
                if (onlineMinutes != displayedOnlineMinutes || lastRevision != ModMindEntry.configSnapshot().revision()) {
                    refreshContents(owner, onlineMinutes);
                }
            }
        }
        super.broadcastChanges();
    }

    public void refreshAfterRewardRetry() {
        if (owner != null) {
            refreshContents(owner, getOnlineMinutes(owner));
            broadcastChanges();
        }
    }

    private void refreshContents(ServerPlayer player, int onlineMinutes) {
        displayedOnlineMinutes = onlineMinutes;
        List<CheckinRewardConfig.OnlineTimeReward> rewards = ModMindEntry.rewardService().onlineTimeRewards();
        pageCount = Math.max(1, (rewards.size() + REWARD_SLOTS.size() - 1) / REWARD_SLOTS.size());
        page = Math.min(page, pageCount - 1);
        for (int slot = 0; slot < CONTAINER_SIZE; slot++) {
            rewardContainer.setItem(slot, new ItemStack(Items.BLACK_STAINED_GLASS_PANE));
        }
        int first = page * REWARD_SLOTS.size();
        for (int localIndex = 0; localIndex < REWARD_SLOTS.size(); localIndex++) {
            int rewardIndex = first + localIndex;
            if (rewardIndex >= rewards.size()) {
                break;
            }
            CheckinRewardConfig.OnlineTimeReward reward = rewards.get(rewardIndex);
            rewardContainer.setItem(REWARD_SLOTS.get(localIndex), rewardStack(player, rewardIndex, reward,
                    onlineMinutes));
        }
        if (page > 0) {
            rewardContainer.setItem(PREVIOUS_PAGE_SLOT, namedItem(Items.ARROW,
                    ServerText.translatable("gui.omnitools.rewards.previous"), List.of()));
        }
        rewardContainer.setItem(PAGE_INFO_SLOT, namedItem(Items.CLOCK,
                ServerText.translatable("gui.omnitools.online_reward.page", page + 1, pageCount, rewards.size()),
                List.of(ServerText.translatable("gui.omnitools.online_reward.progress", onlineMinutes, onlineMinutes))));
        if (page + 1 < pageCount) {
            rewardContainer.setItem(NEXT_PAGE_SLOT, namedItem(Items.ARROW,
                    ServerText.translatable("gui.omnitools.rewards.next"), List.of()));
        }
        lastRevision = ModMindEntry.configSnapshot().revision();
    }

    private static ItemStack rewardStack(ServerPlayer player, int rewardIndex,
                                         CheckinRewardConfig.OnlineTimeReward reward, int onlineMinutes) {
        OnlineTimeRewardService service = ModMindEntry.onlineTimeRewardService();
        OnlineTimeRewardService.RewardStatus status = service.status(player, rewardIndex, reward);
        ItemStack stack = previewStack(player, reward);
        ChatFormatting color = switch (status) {
            case AVAILABLE -> ChatFormatting.GREEN;
            case CLAIMED -> ChatFormatting.GOLD;
            case PENDING -> ChatFormatting.YELLOW;
            case BLOCKED, FAILED -> ChatFormatting.RED;
            case NOT_READY -> ChatFormatting.GRAY;
        };
        if (status == OnlineTimeRewardService.RewardStatus.CLAIMED) {
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        List<Component> lore = new ArrayList<>();
        lore.add(ServerText.translatable("gui.omnitools.online_reward.progress",
                Math.min(onlineMinutes, reward.minutes()), reward.minutes()).withStyle(color));
        lore.add(ServerText.translatable(statusKey(status)).withStyle(color));
        appendRewardPreview(player, reward.rewards(), lore);
        String reason = service.statusReason(player, reward);
        if (!reason.isBlank() && status != OnlineTimeRewardService.RewardStatus.CLAIMED) {
            lore.add(ServerText.translatable("gui.omnitools.reward.pending", reason).withStyle(ChatFormatting.YELLOW));
        }
        stack.set(DataComponents.CUSTOM_NAME, ServerText.translatable("gui.omnitools.online_reward.title", reward.minutes())
                .withStyle(color, ChatFormatting.BOLD));
        stack.set(DataComponents.LORE, new ItemLore(lore));
        return stack;
    }

    private static ItemStack previewStack(ServerPlayer player, CheckinRewardConfig.OnlineTimeReward milestone) {
        if (milestone.rewards().isEmpty()) {
            return new ItemStack(Items.CHEST);
        }
        RewardDefinition first = milestone.rewards().getFirst();
        return switch (first.type()) {
            case CURRENCY -> new ItemStack(first.amount() >= 1_000 ? Items.GOLD_INGOT : Items.GOLD_NUGGET);
            case ITEM -> TextTemplateRenderer.renderItemText(player, first.createItemStack());
            case TITLE -> new ItemStack(Items.NAME_TAG);
            case COMMAND -> new ItemStack(Items.COMMAND_BLOCK);
        };
    }

    private static void appendRewardPreview(ServerPlayer player, List<RewardDefinition> rewards, List<Component> lore) {
        if (rewards.isEmpty()) {
            lore.add(ServerText.translatable("gui.omnitools.checkin.not_claimed").withStyle(ChatFormatting.GRAY));
            return;
        }
        for (RewardDefinition reward : rewards) {
            Component preview = switch (reward.type()) {
                case CURRENCY -> ServerText.translatable("gui.omnitools.reward.currency", reward.amount());
                case ITEM -> {
                    ItemStack item = TextTemplateRenderer.renderItemText(player, reward.createItemStack());
                    yield ServerText.translatable("gui.omnitools.reward.item", item.getHoverName(), item.getCount());
                }
                case TITLE -> ModMindEntry.titleConfig().definition(reward.titleId())
                        .<Component>map(title -> ServerText.translatable("gui.omnitools.reward.title",
                                TextTemplateRenderer.render(player, title.display())))
                        .orElseGet(() -> ServerText.translatable("gui.omnitools.reward.title", reward.titleId()));
                case COMMAND -> ServerText.translatable("gui.omnitools.reward.command");
            };
            lore.add(preview.copy().withStyle(
                    reward.type() == RewardType.CURRENCY ? ChatFormatting.GOLD : ChatFormatting.AQUA));
        }
    }

    private static String statusKey(OnlineTimeRewardService.RewardStatus status) {
        return switch (status) {
            case NOT_READY -> "gui.omnitools.online_reward.unavailable";
            case AVAILABLE -> "gui.omnitools.online_reward.available";
            case CLAIMED -> "gui.omnitools.online_reward.claimed";
            case PENDING -> "gui.omnitools.online_reward.pending";
            case BLOCKED -> "gui.omnitools.online_reward.blocked";
            case FAILED -> "gui.omnitools.online_reward.failed";
        };
    }

    private static ItemStack namedItem(net.minecraft.world.item.Item item, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, name);
        if (!lore.isEmpty()) {
            stack.set(DataComponents.LORE, new ItemLore(lore));
        }
        return stack;
    }

    private static int getOnlineMinutes(ServerPlayer player) {
        long onlineMillis = ModMindEntry.onlineTimeRewardService().getTodayOnlineTime(player);
        return (int) Math.min(Integer.MAX_VALUE, onlineMillis / 60_000L);
    }
}
