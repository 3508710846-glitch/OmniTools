package dev.modmind.omnitools.reward;

import dev.modmind.omnitools.CheckinData;
import dev.modmind.omnitools.ModMindEntry;
import dev.modmind.omnitools.TitleConfig;
import dev.modmind.omnitools.config.ModuleId;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

/** Applies one event in configuration order with per-reward idempotency and durable failure state. */
public final class RewardGrantService {
    public RewardGrantResult grant(ServerPlayer player, RewardEvent event, List<RewardDefinition> rewards) {
        if (!player.getUUID().equals(event.playerId())) {
            return RewardGrantResult.failed(0, 0, "reward event belongs to another player");
        }
        RewardClaimLedger ledger = RewardClaimLedger.get(player);
        int granted = 0;
        int alreadyGranted = 0;
        for (RewardDefinition reward : rewards) {
            RewardClaimLedger.Entry entry = ledger.entry(event, reward.id());
            if (entry.status() == RewardClaimLedger.EntryStatus.GRANTED) {
                alreadyGranted++;
                continue;
            }
            if (entry.status() == RewardClaimLedger.EntryStatus.FAILED) {
                return RewardGrantResult.failed(granted, alreadyGranted, entry.reason());
            }
            SingleResult result = grantOne(player, reward, ledger, event);
            if (result.status == RewardClaimLedger.EntryStatus.GRANTED) {
                granted++;
                continue;
            }
            return switch (result.status) {
                case PENDING -> RewardGrantResult.pending(granted, alreadyGranted, result.reason);
                case BLOCKED -> RewardGrantResult.blocked(granted, alreadyGranted, result.reason);
                case FAILED -> RewardGrantResult.failed(granted, alreadyGranted, result.reason);
                case GRANTED -> throw new IllegalStateException("handled above");
            };
        }
        return RewardGrantResult.success(granted, alreadyGranted);
    }

    public RewardGrantResult retry(ServerPlayer player, RewardEvent event, List<RewardDefinition> rewards) {
        return grant(player, event, rewards);
    }

    private SingleResult grantOne(ServerPlayer player, RewardDefinition reward, RewardClaimLedger ledger,
                                  RewardEvent event) {
        try {
            return switch (reward.type()) {
                case CURRENCY -> grantCurrency(player, reward, ledger, event);
                case ITEM -> grantItem(player, reward, ledger, event);
                case TITLE -> grantTitle(player, reward, ledger, event);
                case COMMAND -> grantCommand(player, reward, ledger, event);
            };
        } catch (RuntimeException exception) {
            String reason = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            ledger.mark(event, reward.id(), RewardClaimLedger.EntryStatus.FAILED, reason);
            return new SingleResult(RewardClaimLedger.EntryStatus.FAILED, reason);
        }
    }

    private SingleResult grantCurrency(ServerPlayer player, RewardDefinition reward, RewardClaimLedger ledger,
                                       RewardEvent event) {
        long balance = CheckinData.get(player).getBalance(player.getUUID());
        if (reward.amount() > Long.MAX_VALUE - balance) {
            ledger.mark(event, reward.id(), RewardClaimLedger.EntryStatus.FAILED, "currency_overflow");
            return new SingleResult(RewardClaimLedger.EntryStatus.FAILED, "currency_overflow");
        }
        // Ledger and currency are separate SavedData files. Marking first makes this an at-most-once
        // operation across a crash/retry boundary, which is safer than silently duplicating currency.
        ledger.mark(event, reward.id(), RewardClaimLedger.EntryStatus.GRANTED, "");
        CheckinData.get(player).addCurrency(player.getUUID(), reward.amount(), player.getGameProfile().name());
        return SingleResult.granted();
    }

    private SingleResult grantItem(ServerPlayer player, RewardDefinition reward, RewardClaimLedger ledger,
                                   RewardEvent event) {
        ItemStack pending = reward.createItemStack();
        if (!canFitFully(player, pending)) {
            String reason = "inventory_full";
            ledger.mark(event, reward.id(), RewardClaimLedger.EntryStatus.PENDING, reason);
            return new SingleResult(RewardClaimLedger.EntryStatus.PENDING, reason);
        }
        // The simulation above proves every unit fits. Persist before mutation to ensure retries
        // cannot duplicate an item stack if the server stops between inventory and ledger saves.
        ledger.mark(event, reward.id(), RewardClaimLedger.EntryStatus.GRANTED, "");
        insertFully(player, pending);
        return SingleResult.granted();
    }

    /** Checks every candidate stack before mutating the inventory. */
    private static boolean canFitFully(ServerPlayer player, ItemStack reward) {
        var inventory = player.getInventory();
        ItemStack simulated = reward.copy();
        int backpackSlots = Math.min(36, inventory.getContainerSize());
        for (int slot = 0; slot < backpackSlots && !simulated.isEmpty(); slot++) {
            ItemStack present = inventory.getItem(slot);
            if (present.isEmpty()) {
                simulated.setCount(Math.max(0, simulated.getCount() - Math.min(simulated.getCount(), simulated.getMaxStackSize())));
            } else if (ItemStack.isSameItemSameComponents(present, simulated)) {
                int room = Math.max(0, present.getMaxStackSize() - present.getCount());
                simulated.setCount(Math.max(0, simulated.getCount() - room));
            }
        }
        return simulated.isEmpty();
    }

    private static void insertFully(ServerPlayer player, ItemStack reward) {
        var inventory = player.getInventory();
        ItemStack actual = reward.copy();
        int backpackSlots = Math.min(36, inventory.getContainerSize());
        for (int slot = 0; slot < backpackSlots && !actual.isEmpty(); slot++) {
            ItemStack present = inventory.getItem(slot);
            if (present.isEmpty()) {
                int transfer = Math.min(actual.getCount(), actual.getMaxStackSize());
                ItemStack placed = actual.copy();
                placed.setCount(transfer);
                inventory.setItem(slot, placed);
                actual.shrink(transfer);
            } else if (ItemStack.isSameItemSameComponents(present, actual)) {
                int transfer = Math.min(actual.getCount(), Math.max(0, present.getMaxStackSize() - present.getCount()));
                if (transfer > 0) {
                    present.grow(transfer);
                    actual.shrink(transfer);
                }
            }
        }
        inventory.setChanged();
        if (!actual.isEmpty()) {
            throw new IllegalStateException("Inventory changed while granting reward");
        }
    }

    private SingleResult grantTitle(ServerPlayer player, RewardDefinition reward, RewardClaimLedger ledger,
                                    RewardEvent event) {
        if (!ModMindEntry.isModuleEnabled(ModuleId.TITLES)) {
            return blocked(ledger, event, reward, "titles_module_disabled");
        }
        TitleConfig.GrantResult result = ModMindEntry.titleConfig().grant(player.getUUID(),
                player.getGameProfile().name(), reward.titleId());
        if (result == TitleConfig.GrantResult.UNKNOWN_TITLE) {
            ledger.mark(event, reward.id(), RewardClaimLedger.EntryStatus.FAILED, "unknown_title");
            return new SingleResult(RewardClaimLedger.EntryStatus.FAILED, "unknown_title");
        }
        ledger.mark(event, reward.id(), RewardClaimLedger.EntryStatus.GRANTED, "");
        return SingleResult.granted();
    }

    private SingleResult grantCommand(ServerPlayer player, RewardDefinition reward, RewardClaimLedger ledger,
                                      RewardEvent event) {
        if (!ModMindEntry.configSnapshot().root().allowCommandRewards()) {
            return blocked(ledger, event, reward, "command_rewards_disabled");
        }
        if (reward.command().length() > ModMindEntry.configSnapshot().root().maxCommandRewardLength()) {
            ledger.mark(event, reward.id(), RewardClaimLedger.EntryStatus.FAILED, "command_too_long");
            return new SingleResult(RewardClaimLedger.EntryStatus.FAILED, "command_too_long");
        }
        // Persist before invoking an external side effect: crashes may skip the command, never replay it.
        ledger.mark(event, reward.id(), RewardClaimLedger.EntryStatus.GRANTED, "");
        String command = substitute(reward.command(), player);
        try {
            player.level().getServer().getCommands().performPrefixedCommand(
                    player.level().getServer().createCommandSourceStack(), command);
            System.out.println("[omnitools] Dispatched reward command " + reward.id() + " for "
                    + player.getUUID() + " in event " + event.id() + ": " + command);
        } catch (RuntimeException exception) {
            // The ledger deliberately remains GRANTED. A command can partially mutate server state before
            // failing, so replaying it would be less safe than recording a possible lost side effect.
            System.err.println("[omnitools] Reward command " + reward.id() + " for " + player.getUUID()
                    + " was already marked dispatched but threw " + exception.getClass().getSimpleName()
                    + " in event " + event.id() + ": " + exception.getMessage());
        }
        return SingleResult.granted();
    }

    private static SingleResult blocked(RewardClaimLedger ledger, RewardEvent event, RewardDefinition reward,
                                        String reason) {
        ledger.mark(event, reward.id(), RewardClaimLedger.EntryStatus.BLOCKED, reason);
        return new SingleResult(RewardClaimLedger.EntryStatus.BLOCKED, reason);
    }

    private static String substitute(String command, ServerPlayer player) {
        Map<String, String> values = Map.of(
                "player_name", player.getGameProfile().name(),
                "player_uuid", player.getUUID().toString(),
                "player_x", Integer.toString(player.blockPosition().getX()),
                "player_y", Integer.toString(player.blockPosition().getY()),
                "player_z", Integer.toString(player.blockPosition().getZ()),
                "player_world", player.level().dimension().identifier().toString());
        String result = command;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private record SingleResult(RewardClaimLedger.EntryStatus status, String reason) {
        static SingleResult granted() {
            return new SingleResult(RewardClaimLedger.EntryStatus.GRANTED, "");
        }
    }
}
