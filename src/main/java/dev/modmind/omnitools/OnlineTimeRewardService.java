package dev.modmind.omnitools;

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
    private static final long FLUSH_INTERVAL_MILLIS = 1_000L;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private long lastPeriodicFlushMillis;

    public void onJoin(ServerPlayer player) {
        sessions.put(player.getUUID(), new Session(System.currentTimeMillis()));
    }

    public void onDisconnect(ServerPlayer player) {
        flush(player, System.currentTimeMillis());
        sessions.remove(player.getUUID());
    }

    public void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (now - lastPeriodicFlushMillis < FLUSH_INTERVAL_MILLIS) {
            return;
        }
        lastPeriodicFlushMillis = now;
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
        long now = System.currentTimeMillis();
        flush(player, now);
        long day = CheckinData.today(player.level().getServer()).toEpochDay();
        CheckinData data = CheckinData.get(player);
        long onlineMillis = data.getOnlineTime(player.getUUID(), day);
        if (onlineMillis < reward.minutes() * 60_000L) {
            return new ClaimResult(ClaimStatus.NOT_READY, onlineMillis, data.getBalance(player.getUUID()));
        }
        if (!data.claimOnlineTimeReward(player.getUUID(), day, reward.id(), legacySlot,
                player.getGameProfile().name())) {
            return new ClaimResult(ClaimStatus.ALREADY_CLAIMED, onlineMillis, data.getBalance(player.getUUID()));
        }
        long balance = data.addCurrency(player.getUUID(), reward.coins(), player.getGameProfile().name());
        return new ClaimResult(ClaimStatus.CLAIMED, onlineMillis, balance);
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
        ALREADY_CLAIMED
    }

    public record ClaimResult(ClaimStatus status, long onlineMillis, long balance) {
    }

    private static final class Session {
        private long lastFlushedMillis;

        private Session(long lastFlushedMillis) {
            this.lastFlushedMillis = lastFlushedMillis;
        }
    }
}
