package dev.modmind.qiandao;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** World-persistent achievement unlock and reward-claim state. */
public final class AchievementData extends SavedData {
    private static final String DATA_ID = ModMindEntry.MOD_ID + "_achievements";
    private static final String PLAYERS_KEY = "players";
    private static final String UNLOCKED_KEY = "unlocked";
    private static final String CLAIMED_KEY = "claimed";

    public static final SavedDataType<AchievementData> TYPE = new SavedDataType<>(
            DATA_ID,
            AchievementData::new,
            CompoundTag.CODEC.xmap(AchievementData::fromTag, AchievementData::toTag),
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, PlayerRecord> players = new HashMap<>();

    public static AchievementData get(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("The overworld is not available while loading achievement data");
        }
        return overworld.getDataStorage().computeIfAbsent(TYPE);
    }

    public static AchievementData get(ServerPlayer player) {
        return get(player.level().getServer());
    }

    public synchronized boolean isUnlocked(UUID playerId, String achievementId) {
        PlayerRecord record = players.get(playerId);
        return record != null && record.unlocked.contains(achievementId);
    }

    public synchronized boolean isClaimed(UUID playerId, String achievementId) {
        PlayerRecord record = players.get(playerId);
        return record != null && record.claimed.contains(achievementId);
    }

    public synchronized boolean unlock(UUID playerId, String achievementId) {
        PlayerRecord record = players.computeIfAbsent(playerId, ignored -> new PlayerRecord());
        boolean changed = record.unlocked.add(achievementId);
        if (changed) {
            setDirty();
        }
        return changed;
    }

    public synchronized boolean markClaimed(UUID playerId, String achievementId) {
        PlayerRecord record = players.computeIfAbsent(playerId, ignored -> new PlayerRecord());
        boolean changed = record.claimed.add(achievementId);
        if (changed) {
            setDirty();
        }
        return changed;
    }

    public synchronized int unlockedCount(UUID playerId) {
        PlayerRecord record = players.get(playerId);
        return record == null ? 0 : record.unlocked.size();
    }

    public synchronized int claimedCount(UUID playerId) {
        PlayerRecord record = players.get(playerId);
        return record == null ? 0 : record.claimed.size();
    }

    private static AchievementData fromTag(CompoundTag root) {
        AchievementData data = new AchievementData();
        CompoundTag players = root.getCompoundOrEmpty(PLAYERS_KEY);
        for (String key : players.keySet()) {
            UUID playerId;
            try {
                playerId = UUID.fromString(key);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            CompoundTag playerTag = players.getCompoundOrEmpty(key);
            PlayerRecord record = new PlayerRecord();
            readIds(playerTag.getListOrEmpty(UNLOCKED_KEY), record.unlocked);
            readIds(playerTag.getListOrEmpty(CLAIMED_KEY), record.claimed);
            data.players.put(playerId, record);
        }
        return data;
    }

    private static void readIds(ListTag list, Set<String> destination) {
        for (int index = 0; index < list.size(); index++) {
            list.getString(index).ifPresent(value -> {
                String id = value.trim().toLowerCase(java.util.Locale.ROOT);
                if (!id.isEmpty()) {
                    destination.add(id);
                }
            });
        }
    }

    private static CompoundTag toTag(AchievementData data) {
        CompoundTag root = new CompoundTag();
        CompoundTag players = new CompoundTag();
        for (Map.Entry<UUID, PlayerRecord> entry : data.players.entrySet()) {
            CompoundTag playerTag = new CompoundTag();
            ListTag unlocked = new ListTag();
            entry.getValue().unlocked.forEach(id -> unlocked.add(StringTag.valueOf(id)));
            playerTag.put(UNLOCKED_KEY, unlocked);
            ListTag claimed = new ListTag();
            entry.getValue().claimed.forEach(id -> claimed.add(StringTag.valueOf(id)));
            playerTag.put(CLAIMED_KEY, claimed);
            players.put(entry.getKey().toString(), playerTag);
        }
        root.put(PLAYERS_KEY, players);
        return root;
    }

    private static final class PlayerRecord {
        private final Set<String> unlocked = new HashSet<>();
        private final Set<String> claimed = new HashSet<>();
    }
}
