package dev.modmind.omnitools;

import dev.modmind.omnitools.diagnostics.OperationalErrorReporter;
import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.reward.RewardClaimLedger;
import dev.modmind.omnitools.reward.RewardEvent;
import dev.modmind.omnitools.reward.RewardGrantResult;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Tracks connected time on the server thread and credits it to the appropriate server-local day. */
public final class OnlineTimeRewardService {
    private static final long FLUSH_INTERVAL_TICKS = 20L;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private long nextPeriodicFlushTick = Long.MIN_VALUE;

    public void onJoin(ServerPlayer player) {
        sessions.put(player.getUUID(), new Session(System.currentTimeMillis()));
    }

    public void onDisconnect(ServerPlayer player) {
        flush(player, System.currentTimeMillis());
        sessions.remove(player.getUUID());
    }

    public void tick(MinecraftServer server) {
        long tick = server.getTickCount();
        if (nextPeriodicFlushTick != Long.MIN_VALUE && tick < nextPeriodicFlushTick) {
            return;
        }
        nextPeriodicFlushTick = tick + FLUSH_INTERVAL_TICKS;
        long now = System.currentTimeMillis();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sessions.computeIfAbsent(player.getUUID(), ignored -> new Session(now));
            flush(player, now);
        }
    }

    public void flushAll(MinecraftServer server) {
        long now = System.currentTimeMillis();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            flush(player, now);
        }
        sessions.clear();
    }

    public long getTodayOnlineTime(ServerPlayer player) {
        long today = CheckinData.today(player.level().getServer()).toEpochDay();
        long persisted = CheckinData.get(player).getOnlineTime(player.getUUID(), today);
        Session session = sessions.get(player.getUUID());
        if (session == null) {
            return persisted;
        }
        return saturatingAdd(persisted, elapsedWithinDay(session.lastFlushedMillis, System.currentTimeMillis(), today));
    }

    public ClaimResult claim(ServerPlayer player, CheckinRewardConfig.OnlineTimeReward reward) {
        return claim(player, -1, reward);
    }

    public ClaimResult claim(ServerPlayer player, int legacySlot, CheckinRewardConfig.OnlineTimeReward reward) {
        legacySlot = legacySlot >= 0 && legacySlot < CheckinRewardConfig.ONLINE_TIME_REWARD_COUNT
                ? legacySlot : -1;
        long now = System.currentTimeMillis();
        flush(player, now);
        long day = CheckinData.today(player.level().getServer()).toEpochDay();
        CheckinData data = CheckinData.get(player);
        long onlineMillis = data.getOnlineTime(player.getUUID(), day);
        if (onlineMillis < reward.minutes() * 60_000L) {
            return new ClaimResult(ClaimStatus.NOT_READY, onlineMillis, data.getBalance(player.getUUID()));
        }
        RewardEvent event = RewardEvent.online(player.getUUID(), day, reward.id());
        RewardClaimLedger ledger = RewardClaimLedger.get(player);
        // A pre-ledger claim belongs to the old currency-only implementation. It is final and
        // must never be converted into a fresh event after upgrading the configuration format.
        if (data.hasClaimedOnlineTimeReward(player.getUUID(), day, reward.id(), legacySlot)
                && !ledger.hasEvent(event)) {
            return new ClaimResult(ClaimStatus.ALREADY_CLAIMED, onlineMillis, data.getBalance(player.getUUID()));
        }
        RewardGrantResult result = ModMindEntry.rewardGrantService().grant(player, event, reward.rewards());
        if (result.complete()) {
            data.markOnlineTimeRewardClaimed(player.getUUID(), day, reward.id(), player.getGameProfile().name());
        }
        return new ClaimResult(toClaimStatus(result), onlineMillis, data.getBalance(player.getUUID()), result.reason(),
                result.granted(), result.alreadyGranted());
    }

    /** Retries only persisted online events, including an event that originated before midnight. */
    public void retryPending(ServerPlayer player) {
        RewardClaimLedger ledger = RewardClaimLedger.get(player);
        String prefix = "online:" + player.getUUID() + ":";
        for (String eventId : ledger.eventIdsStartingWith(prefix)) {
            retryEvent(player, eventId);
        }
    }

    /** Retries a configured online milestone without creating a new claim event. */
    public boolean retryEvent(ServerPlayer player, String eventId) {
        String prefix = "online:" + player.getUUID() + ":";
        if (eventId == null || !eventId.startsWith(prefix)) {
            return false;
        }
        String[] parts = eventId.split(":", -1);
        if (parts.length != 4) {
            return false;
        }
        try {
            long day = Long.parseLong(parts[2]);
            CheckinRewardConfig.OnlineTimeReward reward = ModMindEntry.rewardService().onlineTimeRewards().stream()
                    .filter(candidate -> candidate.id().equals(parts[3]))
                    .findFirst().orElse(null);
            if (reward == null) {
                return false;
            }
            RewardGrantResult result = ModMindEntry.rewardGrantService().retry(player,
                    RewardEvent.online(player.getUUID(), day, reward.id()), reward.rewards());
            if (result.complete()) {
                CheckinData.get(player).markOnlineTimeRewardClaimed(player.getUUID(), day, reward.id(),
                        player.getGameProfile().name());
            }
            return true;
        } catch (RuntimeException exception) {
            // A malformed or stale event is retained for administrator inspection.
            OperationalErrorReporter.global().warn(OperationalErrorReporter.Context
                    .forModule(ModuleId.ONLINE_REWARD, "retry_reward_event")
                    .withPlayer(player.getUUID())
                    .withWorld(player.level().dimension().identifier().toString())
                    .withOperation(null)
                    .withParameters(Map.of("eventId", eventId == null ? "" : eventId))
                    .withRecoveryAction("event_retained_for_administrator"), exception);
            return false;
        }
    }

    public RewardStatus status(ServerPlayer player, int legacySlot, CheckinRewardConfig.OnlineTimeReward reward) {
        legacySlot = legacySlot >= 0 && legacySlot < CheckinRewardConfig.ONLINE_TIME_REWARD_COUNT
                ? legacySlot : -1;
        long day = CheckinData.today(player.level().getServer()).toEpochDay();
        CheckinData data = CheckinData.get(player);
        if (data.hasClaimedOnlineTimeReward(player.getUUID(), day, reward.id(), legacySlot)) {
            return RewardStatus.CLAIMED;
        }
        RewardEvent event = RewardEvent.online(player.getUUID(), day, reward.id());
        java.util.Map<String, RewardClaimLedger.Entry> entries = RewardClaimLedger.get(player).entries(event);
        if (!entries.isEmpty()) {
            if (entries.values().stream().anyMatch(entry -> entry.status() == RewardClaimLedger.EntryStatus.BLOCKED)) {
                return RewardStatus.BLOCKED;
            }
            if (entries.values().stream().anyMatch(entry -> entry.status() == RewardClaimLedger.EntryStatus.FAILED)) {
                return RewardStatus.FAILED;
            }
            return RewardStatus.PENDING;
        }
        return getTodayOnlineTime(player) >= reward.minutes() * 60_000L
                ? RewardStatus.AVAILABLE : RewardStatus.NOT_READY;
    }

    public String statusReason(ServerPlayer player, CheckinRewardConfig.OnlineTimeReward reward) {
        long day = CheckinData.today(player.level().getServer()).toEpochDay();
        RewardEvent event = RewardEvent.online(player.getUUID(), day, reward.id());
        return RewardClaimLedger.get(player).entries(event).values().stream()
                .filter(entry -> !entry.reason().isBlank())
                .map(RewardClaimLedger.Entry::reason)
                .findFirst().orElse("");
    }

    private static ClaimStatus toClaimStatus(RewardGrantResult result) {
        return switch (result.status()) {
            case SUCCESS -> ClaimStatus.CLAIMED;
            case PENDING -> ClaimStatus.PENDING;
            case BLOCKED -> ClaimStatus.BLOCKED;
            case FAILED -> ClaimStatus.FAILED;
        };
    }

    private void flush(ServerPlayer player, long now) {
        Session session = sessions.get(player.getUUID());
        if (session == null) {
            return;
        }
        if (now <= session.lastFlushedMillis) {
            session.lastFlushedMillis = now;
            return;
        }

        long cursor = session.lastFlushedMillis;
        CheckinData data = CheckinData.get(player);
        ZoneId zone = ModMindEntry.configuredZone(player.level().getServer());
        while (cursor < now) {
            LocalDate date = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate();
            long nextDay = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            long segmentEnd = Math.min(now, nextDay);
            data.addOnlineTime(player.getUUID(), date.toEpochDay(), segmentEnd - cursor,
                    player.getGameProfile().name());
            cursor = segmentEnd;
        }
        session.lastFlushedMillis = now;
    }

    private static long elapsedWithinDay(long startMillis, long endMillis, long day) {
        if (endMillis <= startMillis) {
            return 0L;
        }
        ZoneId zone = ModMindEntry.configuredZone();
        LocalDate date = LocalDate.ofEpochDay(day);
        long dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli();
        long nextDay = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        long start = Math.max(startMillis, dayStart);
        long end = Math.min(endMillis, nextDay);
        return Math.max(0L, end - start);
    }

    private static long saturatingAdd(long left, long right) {
        if (right > Long.MAX_VALUE - left) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    public enum ClaimStatus {
        CLAIMED,
        NOT_READY,
        ALREADY_CLAIMED,
        PENDING,
        BLOCKED,
        FAILED
    }

    public enum RewardStatus {
        NOT_READY,
        AVAILABLE,
        CLAIMED,
        PENDING,
        BLOCKED,
        FAILED
    }

    public record ClaimResult(ClaimStatus status, long onlineMillis, long balance, String reason,
                              int granted, int alreadyGranted) {
        public ClaimResult(ClaimStatus status, long onlineMillis, long balance) {
            this(status, onlineMillis, balance, "", 0, 0);
        }
    }

    private static final class Session {
        private long lastFlushedMillis;

        private Session(long lastFlushedMillis) {
            this.lastFlushedMillis = lastFlushedMillis;
        }
    }
}
