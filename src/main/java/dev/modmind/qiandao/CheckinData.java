package dev.modmind.qiandao;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** World-persistent sign-in records shared by every dimension in a server. */
public final class CheckinData extends SavedData {
    private static final String DATA_ID = ModMindEntry.MOD_ID + "_data";
    private static final String PLAYERS_KEY = "players";
    private static final String DAILY_SIGNERS_KEY = "daily_signers";

    public static final SavedDataType<CheckinData> TYPE = new SavedDataType<>(
            DATA_ID,
            CheckinData::new,
            CompoundTag.CODEC.xmap(CheckinData::fromTag, CheckinData::toTag),
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, PlayerRecord> players = new HashMap<>();
    private final Map<Long, Integer> dailySigners = new HashMap<>();

    public static CheckinData get(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("The overworld is not available while loading qiandao data");
        }
        return overworld.getDataStorage().computeIfAbsent(TYPE);
    }

    public static CheckinData get(ServerPlayer player) {
        return get(player.level().getServer());
    }

    public static LocalDate today() {
        return LocalDate.now(ZoneId.systemDefault());
    }

    public synchronized SignInResult signIn(UUID playerId, long day) {
        PlayerRecord record = players.computeIfAbsent(playerId, ignored -> new PlayerRecord());
        if (record.signedDays.contains(day)) {
            return new SignInResult(false, getStats(playerId, day));
        }

        int ordinal = dailySigners.merge(day, 1, Integer::sum);
        record.signedDays.add(day);
        record.signInOrdinals.put(day, ordinal);
        record.totalDays++;
        record.streakDays = record.lastSignedDay == day - 1 ? record.streakDays + 1 : 1;
        record.lastSignedDay = day;
        setDirty();
        return new SignInResult(true, new PlayerStats(true, ordinal, record.totalDays, record.streakDays));
    }

    public synchronized PlayerStats getStats(UUID playerId, long day) {
        PlayerRecord record = players.get(playerId);
        if (record == null) {
            return new PlayerStats(false, dailySigners.getOrDefault(day, 0) + 1, 0, 0);
        }
        boolean signed = record.signedDays.contains(day);
        int ordinal = signed
                ? record.signInOrdinals.getOrDefault(day, dailySigners.getOrDefault(day, 1))
                : dailySigners.getOrDefault(day, 0) + 1;
        int visibleStreak = signed || record.lastSignedDay == day - 1 ? record.streakDays : 0;
        return new PlayerStats(signed, Math.max(ordinal, 1), record.totalDays, visibleStreak);
    }

    public synchronized boolean hasSigned(UUID playerId, long day) {
        PlayerRecord record = players.get(playerId);
        return record != null && record.signedDays.contains(day);
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

    public record PlayerStats(boolean signedToday, int todayOrdinal, int totalDays, int streakDays) {
    }

    public record SignInResult(boolean newlySigned, PlayerStats stats) {
    }

    private static final class PlayerRecord {
        private final Set<Long> signedDays = new HashSet<>();
        private final Map<Long, Integer> signInOrdinals = new HashMap<>();
        private int totalDays;
        private int streakDays;
        private long lastSignedDay = Long.MIN_VALUE;
    }
}
