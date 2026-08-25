package dev.modmind.omnitools;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** World-persistent sign-in records shared by every dimension in a server. */
public final class CheckinData extends SavedData {
    private static final String DATA_ID = ModMindEntry.MOD_ID + "_data";
    private static final String LEGACY_DATA_ID = "qiandao_data";
    private static final String PLAYERS_KEY = "players";
    private static final String DAILY_SIGNERS_KEY = "daily_signers";
    private static final String SIGN_IN_TIMES_KEY = "sign_in_times";
    private static final String LAST_KNOWN_NAME_KEY = "last_known_name";
    private static final String BALANCE_KEY = "balance";
    private static final String MONTHLY_REWARDS_KEY = "monthly_rewards";
    private static final String ONLINE_TIME_DAY_KEY = "online_time_day";
    private static final String ONLINE_TIME_MILLIS_KEY = "online_time_millis";
    private static final String ONLINE_TIME_REWARDS_KEY = "online_time_rewards";

    public static final SavedDataType<CheckinData> TYPE = new SavedDataType<>(
            DATA_ID,
            CheckinData::new,
            CompoundTag.CODEC.xmap(CheckinData::fromTag, CheckinData::toTag),
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private static final SavedDataType<CheckinData> LEGACY_TYPE = new SavedDataType<>(
            LEGACY_DATA_ID,
            CheckinData::new,
            CompoundTag.CODEC.xmap(CheckinData::fromTag, CheckinData::toTag),
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    static SavedDataType<CheckinData> legacyType() {
        return LEGACY_TYPE;
    }

    private final Map<UUID, PlayerRecord> players = new HashMap<>();
    private final Map<Long, Integer> dailySigners = new HashMap<>();
    /** One current-day view per player; invalidated on every sign-in roster change. */
    private final Map<UUID, CachedPlayerStats> statsCache = new HashMap<>();

    public static CheckinData get(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("The overworld is not available while loading omnitools data");
        }
        return overworld.getDataStorage().computeIfAbsent(TYPE);
    }

    public static CheckinData get(ServerPlayer player) {
        return get(player.level().getServer());
    }

    public static LocalDate today() {
        return LocalDate.now(ModMindEntry.configuredZone());
    }

    public static LocalDate today(MinecraftServer server) {
        return LocalDate.now(ModMindEntry.configuredZone(server));
    }

    public synchronized SignInResult signIn(UUID playerId, long day) {
        return signIn(playerId, day, "", System.currentTimeMillis());
    }

    public synchronized SignInResult signIn(UUID playerId, long day, String playerName) {
        return signIn(playerId, day, playerName, System.currentTimeMillis());
    }

    public synchronized SignInResult signIn(UUID playerId, long day, String playerName, long signedAt) {
        PlayerRecord record = players.computeIfAbsent(playerId, ignored -> new PlayerRecord());
        if (playerName != null && !playerName.isBlank() && !playerName.equals(record.lastKnownName)) {
            record.lastKnownName = playerName;
            setDirty();
        }
        if (record.signedDays.contains(day)) {
            return new SignInResult(false, day, getStats(playerId, day));
        }

        int ordinal = dailySigners.merge(day, 1, Integer::sum);
        record.signedDays.add(day);
        record.signInOrdinals.put(day, ordinal);
        record.signInTimes.put(day, signedAt);
        record.totalDays++;
        record.streakDays = record.lastSignedDay == day - 1 ? record.streakDays + 1 : 1;
        record.lastSignedDay = day;
        statsCache.clear();
        setDirty();
        return new SignInResult(true, day, new PlayerStats(true, ordinal, record.totalDays, record.streakDays,
                monthDayCount(record, LocalDate.ofEpochDay(day))));
    }

    public synchronized PlayerStats getStats(UUID playerId, long day) {
        CachedPlayerStats cached = statsCache.get(playerId);
        if (cached != null && cached.day() == day) {
            return cached.stats();
        }
        PlayerRecord record = players.get(playerId);
        PlayerStats stats;
        if (record == null) {
            stats = new PlayerStats(false, dailySigners.getOrDefault(day, 0) + 1, 0, 0, 0);
        } else {
            boolean signed = record.signedDays.contains(day);
            int ordinal = signed
                    ? record.signInOrdinals.getOrDefault(day, dailySigners.getOrDefault(day, 1))
                    : dailySigners.getOrDefault(day, 0) + 1;
            int visibleStreak = signed || record.lastSignedDay == day - 1 ? record.streakDays : 0;
            stats = new PlayerStats(signed, Math.max(ordinal, 1), record.totalDays, visibleStreak,
                    monthDayCount(record, LocalDate.ofEpochDay(day)));
        }
        statsCache.put(playerId, new CachedPlayerStats(day, stats));
        return stats;
    }

    public synchronized long getBalance(UUID playerId) {
        PlayerRecord record = players.get(playerId);
        return record == null ? 0L : record.balance;
    }

    public synchronized long addCurrency(UUID playerId, long amount, String playerName) {
        requireNonNegative(amount);
        PlayerRecord record = getOrCreateRecord(playerId, playerName);
        record.balance = saturatingAdd(record.balance, amount);
        setDirty();
        return record.balance;
    }

    /** Removes up to {@code amount} and returns the amount actually removed. */
    public synchronized long removeCurrency(UUID playerId, long amount, String playerName) {
        requireNonNegative(amount);
        PlayerRecord record = getOrCreateRecord(playerId, playerName);
        long removed = Math.min(record.balance, amount);
        record.balance -= removed;
        setDirty();
        return removed;
    }

    public synchronized boolean claimMonthlyReward(UUID playerId, YearMonth month, int days, String playerName) {
        if (hasClaimedMonthlyReward(playerId, month, days)) {
            return false;
        }
        markMonthlyRewardClaimed(playerId, month, days, playerName);
        return true;
    }

    public synchronized boolean hasClaimedMonthlyReward(UUID playerId, YearMonth month, int days) {
        if (days <= 0) {
            throw new IllegalArgumentException("Monthly reward threshold must be positive");
        }
        PlayerRecord record = players.get(playerId);
        return record != null && record.claimedMonthlyRewards.contains(month + ":" + days);
    }

    public synchronized boolean markMonthlyRewardClaimed(UUID playerId, YearMonth month, int days, String playerName) {
        if (days <= 0) {
            throw new IllegalArgumentException("Monthly reward threshold must be positive");
        }
        PlayerRecord record = getOrCreateRecord(playerId, playerName);
        boolean claimed = record.claimedMonthlyRewards.add(month + ":" + days);
        if (claimed) {
            setDirty();
        }
        return claimed;
    }

    public synchronized long addOnlineTime(UUID playerId, long day, long milliseconds, String playerName) {
        requireNonNegative(milliseconds);
        PlayerRecord record = getOrCreateRecord(playerId, playerName);
        if (record.onlineTimeDay != day) {
            record.onlineTimeDay = day;
            record.onlineTimeMillis = 0L;
            record.claimedOnlineTimeRewards.removeIf(key -> !key.startsWith(day + ":"));
        }
        if (milliseconds > 0L) {
            record.onlineTimeMillis = saturatingAdd(record.onlineTimeMillis, milliseconds);
            setDirty();
        }
        return record.onlineTimeMillis;
    }

    public synchronized long getOnlineTime(UUID playerId, long day) {
        PlayerRecord record = players.get(playerId);
        return record != null && record.onlineTimeDay == day ? record.onlineTimeMillis : 0L;
    }

    public synchronized boolean hasClaimedOnlineTimeReward(UUID playerId, long day, int rewardSlot) {
        validateOnlineTimeRewardSlot(rewardSlot);
        PlayerRecord record = players.get(playerId);
        return record != null && record.claimedOnlineTimeRewards.contains(onlineTimeRewardKey(day, rewardSlot));
    }

    /** Stable-ID lookup with compatibility for legacy day:slot records. */
    public synchronized boolean hasClaimedOnlineTimeReward(UUID playerId, long day, String rewardId) {
        return hasClaimedOnlineTimeReward(playerId, day, rewardId, -1);
    }

    public synchronized boolean hasClaimedOnlineTimeReward(UUID playerId, long day, String rewardId,
                                                            int legacySlot) {
        String normalizedId = normalizeRewardId(rewardId);
        PlayerRecord record = players.get(playerId);
        if (record == null) {
            return false;
        }
        if (record.claimedOnlineTimeRewards.contains(onlineTimeRewardKey(day, normalizedId))) {
            return true;
        }
        if (legacySlot >= 0 && record.claimedOnlineTimeRewards.contains(onlineTimeRewardKey(day, legacySlot))) {
            record.claimedOnlineTimeRewards.add(onlineTimeRewardKey(day, normalizedId));
            setDirty();
            return true;
        }
        return false;
    }

    public synchronized boolean claimOnlineTimeReward(UUID playerId, long day, int rewardSlot, String playerName) {
        validateOnlineTimeRewardSlot(rewardSlot);
        PlayerRecord record = getOrCreateRecord(playerId, playerName);
        if (record.onlineTimeDay != day) {
            return false;
        }
        boolean claimed = record.claimedOnlineTimeRewards.add(onlineTimeRewardKey(day, rewardSlot));
        if (claimed) {
            setDirty();
        }
        return claimed;
    }

    public synchronized boolean claimOnlineTimeReward(UUID playerId, long day, String rewardId, String playerName) {
        return claimOnlineTimeReward(playerId, day, rewardId, -1, playerName);
    }

    public synchronized boolean claimOnlineTimeReward(UUID playerId, long day, String rewardId, int legacySlot,
                                                       String playerName) {
        String normalizedId = normalizeRewardId(rewardId);
        PlayerRecord record = getOrCreateRecord(playerId, playerName);
        if (record.onlineTimeDay != day) {
            return false;
        }
        if (hasClaimedOnlineTimeReward(playerId, day, normalizedId, legacySlot)) {
            return false;
        }
        boolean claimed = record.claimedOnlineTimeRewards.add(onlineTimeRewardKey(day, normalizedId));
        if (claimed) {
            setDirty();
        }
        return claimed;
    }

    private PlayerRecord getOrCreateRecord(UUID playerId, String playerName) {
        PlayerRecord record = players.computeIfAbsent(playerId, ignored -> new PlayerRecord());
        if (playerName != null && !playerName.isBlank() && !playerName.equals(record.lastKnownName)) {
            record.lastKnownName = playerName;
            setDirty();
        }
        return record;
    }

    private static int monthDayCount(PlayerRecord record, LocalDate date) {
        YearMonth month = YearMonth.from(date);
        int count = 0;
        for (long signedDay : record.signedDays) {
            if (YearMonth.from(LocalDate.ofEpochDay(signedDay)).equals(month)) {
                count++;
            }
        }
        return count;
    }

    private static void requireNonNegative(long amount) {
        if (amount < 0L) {
            throw new IllegalArgumentException("Currency amount must not be negative");
        }
    }

    private static long saturatingAdd(long left, long right) {
        if (right > Long.MAX_VALUE - left) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static void validateOnlineTimeRewardSlot(int rewardSlot) {
        if (rewardSlot < 0 || rewardSlot >= CheckinRewardConfig.ONLINE_TIME_REWARD_COUNT) {
            throw new IllegalArgumentException("Online time reward slot is out of range");
        }
    }

    private static String onlineTimeRewardKey(long day, int rewardSlot) {
        return day + ":" + rewardSlot;
    }

    private static String onlineTimeRewardKey(long day, String rewardId) {
        return day + ":" + normalizeRewardId(rewardId);
    }

    private static String normalizeRewardId(String rewardId) {
        if (rewardId == null || rewardId.isBlank()) {
            throw new IllegalArgumentException("Online time reward id must not be blank");
        }
        return rewardId.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public synchronized boolean hasSigned(UUID playerId, long day) {
        PlayerRecord record = players.get(playerId);
        return record != null && record.signedDays.contains(day);
    }

    public synchronized int clearToday() {
        return clearDay(today().toEpochDay());
    }

    public synchronized int clearDay(long day) {
        int clearedPlayers = 0;
        for (PlayerRecord record : players.values()) {
            if (!record.signedDays.remove(day)) {
                continue;
            }
            record.signInOrdinals.remove(day);
            record.signInTimes.remove(day);
            record.totalDays = Math.max(0, record.totalDays - 1);
            rebuildLatestStats(record);
            clearedPlayers++;
        }
        boolean hadDailyCount = dailySigners.remove(day) != null;
        if (clearedPlayers > 0 || hadDailyCount) {
            statsCache.clear();
            setDirty();
        }
        return clearedPlayers;
    }

    public synchronized List<DailySignInRecord> getDailyRecords(long day) {
        List<DailySignInRecord> records = new ArrayList<>();
        for (Map.Entry<UUID, PlayerRecord> entry : players.entrySet()) {
            PlayerRecord record = entry.getValue();
            if (!record.signedDays.contains(day)) {
                continue;
            }
            records.add(new DailySignInRecord(
                    entry.getKey(),
                    record.lastKnownName,
                    record.signInOrdinals.getOrDefault(day, 0),
                    record.signInTimes.getOrDefault(day, 0L)));
        }
        // Legacy records have no timestamp and necessarily precede records created after this feature.
        records.sort(Comparator
                .comparingLong((DailySignInRecord record) -> record.signedAt() > 0L
                        ? record.signedAt() : Long.MIN_VALUE)
                .thenComparingInt(DailySignInRecord::ordinal)
                .thenComparing(record -> record.playerId().toString()));
        return List.copyOf(records);
    }

    private static void rebuildLatestStats(PlayerRecord record) {
        if (record.signedDays.isEmpty()) {
            record.lastSignedDay = Long.MIN_VALUE;
            record.streakDays = 0;
            return;
        }

        long latestDay = Long.MIN_VALUE;
        for (long signedDay : record.signedDays) {
            latestDay = Math.max(latestDay, signedDay);
        }
        int streak = 1;
        long cursor = latestDay;
        while (cursor > Long.MIN_VALUE && record.signedDays.contains(cursor - 1)) {
            cursor--;
            streak++;
        }
        record.lastSignedDay = latestDay;
        record.streakDays = streak;
    }

    private static CheckinData fromTag(CompoundTag root) {
        CheckinData data = new CheckinData();
        CompoundTag playerTags = root.getCompoundOrEmpty(PLAYERS_KEY);
        for (String key : playerTags.keySet()) {
            try {
                UUID playerId = UUID.fromString(key);
                CompoundTag playerTag = playerTags.getCompoundOrEmpty(key);
                PlayerRecord record = new PlayerRecord();
                for (long day : playerTag.getLongArray("signed_days").orElseGet(() -> new long[0])) {
                    record.signedDays.add(day);
                }
                CompoundTag ordinalTags = playerTag.getCompoundOrEmpty("sign_in_ordinals");
                for (String dayKey : ordinalTags.keySet()) {
                    try {
                        record.signInOrdinals.put(Long.parseLong(dayKey), ordinalTags.getIntOr(dayKey, 0));
                    } catch (NumberFormatException ignored) {
                        // Ignore malformed day keys.
                    }
                }
                CompoundTag timeTags = playerTag.getCompoundOrEmpty(SIGN_IN_TIMES_KEY);
                for (String dayKey : timeTags.keySet()) {
                    try {
                        record.signInTimes.put(Long.parseLong(dayKey), timeTags.getLongOr(dayKey, 0L));
                    } catch (NumberFormatException ignored) {
                        // Ignore malformed day keys.
                    }
                }
                record.lastKnownName = playerTag.getStringOr(LAST_KNOWN_NAME_KEY, "");
                record.balance = Math.max(0L, playerTag.getLongOr(BALANCE_KEY, 0L));
                CompoundTag claimedTags = playerTag.getCompoundOrEmpty(MONTHLY_REWARDS_KEY);
                record.claimedMonthlyRewards.addAll(claimedTags.keySet());
                record.onlineTimeDay = playerTag.getLongOr(ONLINE_TIME_DAY_KEY, Long.MIN_VALUE);
                record.onlineTimeMillis = Math.max(0L, playerTag.getLongOr(ONLINE_TIME_MILLIS_KEY, 0L));
                CompoundTag onlineClaimedTags = playerTag.getCompoundOrEmpty(ONLINE_TIME_REWARDS_KEY);
                record.claimedOnlineTimeRewards.addAll(onlineClaimedTags.keySet());
                record.totalDays = playerTag.getIntOr("total_days", record.signedDays.size());
                record.streakDays = playerTag.getIntOr("streak_days", 0);
                record.lastSignedDay = playerTag.getLongOr("last_signed_day", Long.MIN_VALUE);
                data.players.put(playerId, record);
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed records so one bad player cannot prevent a world from loading.
            }
        }

        CompoundTag signerTags = root.getCompoundOrEmpty(DAILY_SIGNERS_KEY);
        for (String key : signerTags.keySet()) {
            try {
                data.dailySigners.put(Long.parseLong(key), signerTags.getIntOr(key, 0));
            } catch (NumberFormatException ignored) {
                // Ignore malformed day keys.
            }
        }
        return data;
    }

    private static CompoundTag toTag(CheckinData data) {
        CompoundTag root = new CompoundTag();
        CompoundTag playerTags = new CompoundTag();
        for (Map.Entry<UUID, PlayerRecord> entry : data.players.entrySet()) {
            PlayerRecord record = entry.getValue();
            CompoundTag playerTag = new CompoundTag();
            long[] signedDays = record.signedDays.stream().mapToLong(Long::longValue).toArray();
            playerTag.putLongArray("signed_days", signedDays);
            playerTag.putInt("total_days", record.totalDays);
            playerTag.putInt("streak_days", record.streakDays);
            playerTag.putLong("last_signed_day", record.lastSignedDay);
            CompoundTag ordinalTags = new CompoundTag();
            for (Map.Entry<Long, Integer> ordinalEntry : record.signInOrdinals.entrySet()) {
                ordinalTags.putInt(Long.toString(ordinalEntry.getKey()), ordinalEntry.getValue());
            }
            playerTag.put("sign_in_ordinals", ordinalTags);
            CompoundTag timeTags = new CompoundTag();
            for (Map.Entry<Long, Long> timeEntry : record.signInTimes.entrySet()) {
                timeTags.putLong(Long.toString(timeEntry.getKey()), timeEntry.getValue());
            }
            playerTag.put(SIGN_IN_TIMES_KEY, timeTags);
            playerTag.putString(LAST_KNOWN_NAME_KEY, record.lastKnownName);
            playerTag.putLong(BALANCE_KEY, record.balance);
            CompoundTag claimedTags = new CompoundTag();
            for (String rewardKey : record.claimedMonthlyRewards) {
                claimedTags.putBoolean(rewardKey, true);
            }
            playerTag.put(MONTHLY_REWARDS_KEY, claimedTags);
            playerTag.putLong(ONLINE_TIME_DAY_KEY, record.onlineTimeDay);
            playerTag.putLong(ONLINE_TIME_MILLIS_KEY, record.onlineTimeMillis);
            CompoundTag onlineClaimedTags = new CompoundTag();
            for (String rewardKey : record.claimedOnlineTimeRewards) {
                onlineClaimedTags.putBoolean(rewardKey, true);
            }
            playerTag.put(ONLINE_TIME_REWARDS_KEY, onlineClaimedTags);
            playerTags.put(entry.getKey().toString(), playerTag);
        }
        root.put(PLAYERS_KEY, playerTags);

        CompoundTag signerTags = new CompoundTag();
        for (Map.Entry<Long, Integer> entry : data.dailySigners.entrySet()) {
            signerTags.putInt(Long.toString(entry.getKey()), entry.getValue());
        }
        root.put(DAILY_SIGNERS_KEY, signerTags);
        return root;
    }

    public record PlayerStats(boolean signedToday, int todayOrdinal, int totalDays, int streakDays, int monthlyDays) {
    }

    public record SignInResult(boolean newlySigned, long day, PlayerStats stats) {
    }

    public record DailySignInRecord(UUID playerId, String playerName, int ordinal, long signedAt) {
    }

    private record CachedPlayerStats(long day, PlayerStats stats) {
    }

    private static final class PlayerRecord {
        private final Set<Long> signedDays = new HashSet<>();
        private final Map<Long, Integer> signInOrdinals = new HashMap<>();
        private final Map<Long, Long> signInTimes = new HashMap<>();
        private String lastKnownName = "";
        private int totalDays;
        private int streakDays;
        private long lastSignedDay = Long.MIN_VALUE;
        private long balance;
        private final Set<String> claimedMonthlyRewards = new HashSet<>();
        private long onlineTimeDay = Long.MIN_VALUE;
        private long onlineTimeMillis;
        private final Set<String> claimedOnlineTimeRewards = new HashSet<>();
    }
}
