package dev.modmind.omnitools.reward;

import dev.modmind.omnitools.CheckinData;
import dev.modmind.omnitools.AchievementData;
import dev.modmind.omnitools.ModMindEntry;
import dev.modmind.omnitools.TitleData;
import dev.modmind.omnitools.TitleDisplayService;
import dev.modmind.omnitools.config.ModuleId;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.time.YearMonth;
import java.util.UUID;

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
            ledger.registerReward(event, reward.id(), reward.type(), player.getGameProfile().name());
            RewardClaimLedger.Entry entry = ledger.entry(event, reward.id());
            if (entry.status() == RewardClaimLedger.EntryStatus.GRANTED) {
                alreadyGranted++;
                continue;
            }
            if (entry.status() == RewardClaimLedger.EntryStatus.FAILED) {
                return RewardGrantResult.failed(granted, alreadyGranted, entry.reason());
            }
            if (entry.status() == RewardClaimLedger.EntryStatus.BLOCKED && isManualResolutionRequired(entry.reason())) {
                return RewardGrantResult.blocked(granted, alreadyGranted, entry.reason());
            }
            SingleResult result = entry.status() == RewardClaimLedger.EntryStatus.APPLYING
                    ? recoverApplying(player, reward, ledger, event, entry)
                    : grantOne(player, reward, ledger, event);
            if (result.status == RewardClaimLedger.EntryStatus.GRANTED) {
                granted++;
                continue;
            }
            return switch (result.status) {
                case PENDING -> RewardGrantResult.pending(granted, alreadyGranted, result.reason);
                case BLOCKED -> RewardGrantResult.blocked(granted, alreadyGranted, result.reason);
                case FAILED -> RewardGrantResult.failed(granted, alreadyGranted, result.reason);
                case APPLYING, GRANTED -> throw new IllegalStateException("unfinished reward result");
            };
        }
        return RewardGrantResult.success(granted, alreadyGranted);
    }

    public RewardGrantResult retry(ServerPlayer player, RewardEvent event, List<RewardDefinition> rewards) {
        return grant(player, event, rewards);
    }

    /**
     * Retries exactly one persisted item delivery from the player's reward inbox. This intentionally
     * uses the ledger snapshot rather than a live configuration definition, because a configuration
     * reload must not change a reward that was already promised to a player.
     */
    public RewardGrantResult retryQueuedItem(ServerPlayer player, RewardEvent event, String rewardId) {
        if (player == null || event == null || !player.getUUID().equals(event.playerId())) {
            return RewardGrantResult.failed(0, 0, "reward event belongs to another player");
        }
        RewardClaimLedger ledger = RewardClaimLedger.get(player);
        RewardClaimLedger.Entry entry = ledger.entry(event, rewardId);
        if (entry.status() == RewardClaimLedger.EntryStatus.GRANTED) {
            return RewardGrantResult.success(0, 1);
        }
        if (entry.status() == RewardClaimLedger.EntryStatus.FAILED) {
            return RewardGrantResult.failed(0, 0, entry.reason());
        }
        if (entry.status() != RewardClaimLedger.EntryStatus.PENDING) {
            String reason = entry.reason().isBlank() ? "item_delivery_not_retryable" : entry.reason();
            return RewardGrantResult.blocked(0, 0, reason);
        }
        ItemStack pending = ledger.queuedItem(event, rewardId);
        if (pending.isEmpty()) {
            ledger.mark(event, rewardId, RewardClaimLedger.EntryStatus.FAILED, "missing_item_snapshot");
            return RewardGrantResult.failed(0, 0, "missing_item_snapshot");
        }
        if (!canFitFully(player, pending)) {
            ledger.mark(event, rewardId, RewardClaimLedger.EntryStatus.PENDING, "inventory_full");
            return RewardGrantResult.pending(0, 0, "inventory_full");
        }
        try {
            // See grantItem: this boundary must stay conservative after a process interruption.
            ledger.beginApplying(event, rewardId, "inbox_item_delivery");
            insertFully(player, pending);
            ledger.mark(event, rewardId, RewardClaimLedger.EntryStatus.GRANTED, "");
            return RewardGrantResult.success(1, 0);
        } catch (RuntimeException exception) {
            ledger.mark(event, rewardId, RewardClaimLedger.EntryStatus.BLOCKED,
                    "item_delivery_outcome_unknown");
            System.err.println("[omnitools] Reward inbox delivery requires manual resolution: event="
                    + event.id() + ", reward=" + rewardId + ", player=" + player.getUUID() + " ("
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage() + ")");
            return RewardGrantResult.blocked(0, 0, "item_delivery_outcome_unknown");
        }
    }

    public void reconcileStartup(MinecraftServer server) {
        RewardClaimLedger.RecoveryAudit audit = RewardClaimLedger.get(server).reconcileStartupApplying();
        if (audit.hasFindings()) {
            System.err.println("[omnitools] Reward recovery scan: " + audit.quarantinedItems()
                    + " item deliveries and " + audit.quarantinedCommands()
                    + " commands require administrator resolution; " + audit.awaitingDataRecovery()
                    + " currency/title entries will reconcile when their players join.");
        }
    }

    /**
     * Retention cleanup is proof-based rather than age-based. An event is discarded only once all
     * of its entries are granted and its original sign-in or achievement state is still present.
     */
    public int cleanupProvenCompleted(MinecraftServer server) {
        RewardClaimLedger ledger = RewardClaimLedger.get(server);
        int removed = 0;
        for (String eventId : ledger.eventIds()) {
            EventSource source = EventSource.parse(eventId);
            if (source == null) {
                continue;
            }
            RewardEvent event = new RewardEvent(eventId, source.playerId());
            if (ledger.entries(event).values().stream()
                    .anyMatch(entry -> entry.status() != RewardClaimLedger.EntryStatus.GRANTED)) {
                continue;
            }
            if (source.isPermanentlyProven(server) && ledger.removeEvent(event)) {
                removed++;
            }
        }
        return removed;
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

    /**
     * A ledger entry can be left APPLYING only across a process stop. Currency and title source
     * data carry the same event key, so replaying those writes is idempotent. Inventory and
     * command side effects cannot be atomically committed with SavedData, therefore they become
     * visible administrator work rather than being replayed and potentially duplicated.
     */
    private SingleResult recoverApplying(ServerPlayer player, RewardDefinition reward, RewardClaimLedger ledger,
                                         RewardEvent event, RewardClaimLedger.Entry entry) {
        return switch (reward.type()) {
            case CURRENCY -> grantCurrency(player, reward, ledger, event);
            case TITLE -> grantTitle(player, reward, ledger, event);
            case ITEM -> blocked(ledger, event, reward, "item_delivery_outcome_unknown");
            case COMMAND -> blocked(ledger, event, reward, "command_dispatch_outcome_unknown");
        };
    }

    private SingleResult grantCurrency(ServerPlayer player, RewardDefinition reward, RewardClaimLedger ledger,
                                       RewardEvent event) {
        ledger.beginApplying(event, reward.id(), "currency_apply");
        CheckinData.CurrencyRewardResult result = CheckinData.get(player).applyRewardCurrency(player.getUUID(),
                event.id(), reward.id(), reward.amount(), player.getGameProfile().name());
        if (result == CheckinData.CurrencyRewardResult.OVERFLOW) {
            ledger.mark(event, reward.id(), RewardClaimLedger.EntryStatus.FAILED, "currency_overflow");
            return new SingleResult(RewardClaimLedger.EntryStatus.FAILED, "currency_overflow");
        }
        ledger.mark(event, reward.id(), RewardClaimLedger.EntryStatus.GRANTED, "");
        return SingleResult.granted();
    }

    private SingleResult grantItem(ServerPlayer player, RewardDefinition reward, RewardClaimLedger ledger,
                                   RewardEvent event) {
        ItemStack pending = ledger.queueItem(event, reward.id(), reward.createItemStack());
        if (pending.isEmpty()) {
            ledger.mark(event, reward.id(), RewardClaimLedger.EntryStatus.FAILED, "invalid_item_snapshot");
            return new SingleResult(RewardClaimLedger.EntryStatus.FAILED, "invalid_item_snapshot");
        }
        if (!canFitFully(player, pending)) {
            String reason = "inventory_full";
            ledger.mark(event, reward.id(), RewardClaimLedger.EntryStatus.PENDING, reason);
            return new SingleResult(RewardClaimLedger.EntryStatus.PENDING, reason);
        }
        // The player inventory and world SavedData do not share a transaction. APPLYING makes an
        // interrupted delivery auditable; recovery blocks it for an administrator instead of
        // blindly replaying an item that may already be present.
        ledger.beginApplying(event, reward.id(), "item_delivery");
        insertFully(player, pending);
        ledger.mark(event, reward.id(), RewardClaimLedger.EntryStatus.GRANTED, "");
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
        if (ModMindEntry.titleConfig().definition(reward.titleId()).isEmpty()) {
            ledger.mark(event, reward.id(), RewardClaimLedger.EntryStatus.FAILED, "unknown_title");
            return new SingleResult(RewardClaimLedger.EntryStatus.FAILED, "unknown_title");
        }
        ledger.beginApplying(event, reward.id(), "title_apply");
        TitleData.get(player).grantReward(player.getUUID(), player.getGameProfile().name(), reward.titleId(),
                event.id(), reward.id());
        ledger.mark(event, reward.id(), RewardClaimLedger.EntryStatus.GRANTED, "");
        TitleDisplayService.refreshPlayer(player);
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
        String command = substitute(reward.command(), player);
        if (!ModMindEntry.configSnapshot().root().commandSecurity().allows(command)) {
            ledger.mark(event, reward.id(), RewardClaimLedger.EntryStatus.FAILED, "command_security_blocked");
            return new SingleResult(RewardClaimLedger.EntryStatus.FAILED, "command_security_blocked");
        }
        ledger.beginApplying(event, reward.id(), "command_prepare");
        ledger.markCommandDispatched(event, reward.id(), command);
        try {
            player.level().getServer().getCommands().performPrefixedCommand(
                    player.level().getServer().createCommandSourceStack(), command);
            ledger.mark(event, reward.id(), RewardClaimLedger.EntryStatus.GRANTED, "command_dispatched");
            System.out.println("[omnitools] Dispatched reward command " + reward.id() + " for "
                    + player.getUUID() + " in event " + event.id() + ": " + command);
        } catch (RuntimeException exception) {
            // A command may partially mutate server state before throwing. It is never retried
            // automatically; the administrator can inspect the persisted command and resolve it.
            ledger.mark(event, reward.id(), RewardClaimLedger.EntryStatus.BLOCKED,
                    "command_dispatch_failed_no_replay");
            System.err.println("[omnitools] Reward command " + reward.id() + " for " + player.getUUID()
                    + " is blocked for manual review in event " + event.id() + ": " + command
                    + " (" + exception.getClass().getSimpleName() + ": " + exception.getMessage() + ")");
            return new SingleResult(RewardClaimLedger.EntryStatus.BLOCKED,
                    "command_dispatch_failed_no_replay");
        }
        return SingleResult.granted();
    }

    private static SingleResult blocked(RewardClaimLedger ledger, RewardEvent event, RewardDefinition reward,
                                        String reason) {
        ledger.mark(event, reward.id(), RewardClaimLedger.EntryStatus.BLOCKED, reason);
        return new SingleResult(RewardClaimLedger.EntryStatus.BLOCKED, reason);
    }

    private static boolean isManualResolutionRequired(String reason) {
        return "item_delivery_outcome_unknown".equals(reason)
                || "command_dispatch_outcome_unknown".equals(reason)
                || "command_dispatch_failed_no_replay".equals(reason);
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

    private sealed interface EventSource permits DailySource, MonthlySource, AchievementSource, OnlineSource {
        UUID playerId();

        boolean isPermanentlyProven(MinecraftServer server);

        static EventSource parse(String eventId) {
            try {
                String[] parts = eventId.split(":", -1);
                if (parts.length == 4 && parts[0].equals("checkin") && parts[2].equals("daily")) {
                    return new DailySource(UUID.fromString(parts[1]), Long.parseLong(parts[3]));
                }
                if (parts.length == 5 && parts[0].equals("checkin") && parts[2].equals("monthly")) {
                    return new MonthlySource(UUID.fromString(parts[1]), YearMonth.parse(parts[3]),
                            Integer.parseInt(parts[4]));
                }
                if (parts.length == 3 && parts[0].equals("achievement")) {
                    return new AchievementSource(UUID.fromString(parts[1]), parts[2]);
                }
                if (parts.length == 4 && parts[0].equals("online")) {
                    return new OnlineSource(UUID.fromString(parts[1]), Long.parseLong(parts[2]), parts[3]);
                }
            } catch (RuntimeException ignored) {
                // Unknown keys are retained for inspection rather than being treated as disposable.
            }
            return null;
        }
    }

    private record DailySource(UUID playerId, long day) implements EventSource {
        @Override
        public boolean isPermanentlyProven(MinecraftServer server) {
            return CheckinData.get(server).hasSigned(playerId, day);
        }
    }

    private record MonthlySource(UUID playerId, YearMonth month, int milestone) implements EventSource {
        @Override
        public boolean isPermanentlyProven(MinecraftServer server) {
            return CheckinData.get(server).hasClaimedMonthlyReward(playerId, month, milestone);
        }
    }

    private record AchievementSource(UUID playerId, String achievementId) implements EventSource {
        @Override
        public boolean isPermanentlyProven(MinecraftServer server) {
            return AchievementData.get(server).isClaimed(playerId, achievementId);
        }
    }

    private record OnlineSource(UUID playerId, long day, String milestoneId) implements EventSource {
        @Override
        public boolean isPermanentlyProven(MinecraftServer server) {
            return CheckinData.get(server).hasClaimedOnlineTimeReward(playerId, day, milestoneId);
        }
    }
}
