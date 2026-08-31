package dev.modmind.omnitools.skills;

import dev.modmind.omnitools.ModMindEntry;
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

/** World-persistent, server-authoritative skill-tree state. */
public final class SkillTreeData extends SavedData {
    private static final String DATA_ID = ModMindEntry.MOD_ID + "_skill_trees";
    private static final String PLAYERS_KEY = "players";
    private static final String TREES_KEY = "trees";
    private static final String LEVEL_KEY = "level";
    private static final String XP_KEY = "current_xp";
    private static final String TOTAL_XP_KEY = "total_xp";
    private static final String AVAILABLE_POINTS_KEY = "available_points";
    private static final String ATTRIBUTE_POINTS_KEY = "attribute_points";
    private static final String SKILL_POINTS_KEY = "skill_points";
    private static final String UNLOCKED_KEY = "unlocked_skills";
    private static final String OVERFLOW_XP_KEY = "overflow_xp";
    private static final String DAILY_XP_KEY = "daily_xp";
    private static final String DAILY_EPOCH_DAY_KEY = "daily_epoch_day";

    public static final SavedDataType<SkillTreeData> TYPE = new SavedDataType<>(DATA_ID, SkillTreeData::new,
            CompoundTag.CODEC.xmap(SkillTreeData::fromTag, SkillTreeData::toTag),
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, Map<String, Progress>> players = new HashMap<>();

    public static SkillTreeData get(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) throw new IllegalStateException("The overworld is unavailable while loading skill-tree data");
        return overworld.getDataStorage().computeIfAbsent(TYPE);
    }

    public static SkillTreeData get(ServerPlayer player) {
        return get(player.level().getServer());
    }

    public synchronized Progress progress(UUID playerId, String treeId) {
        Map<String, Progress> trees = players.get(playerId);
        return trees == null ? Progress.empty() : trees.getOrDefault(treeId, Progress.empty());
    }

    public synchronized void replace(UUID playerId, String treeId, Progress progress) {
        if (playerId == null || treeId == null || treeId.isBlank() || progress == null) return;
        players.computeIfAbsent(playerId, ignored -> new HashMap<>()).put(treeId, progress);
        setDirty();
    }

    public synchronized void clear(UUID playerId, String treeId) {
        Map<String, Progress> trees = players.get(playerId);
        if (trees != null && trees.remove(treeId) != null) {
            if (trees.isEmpty()) players.remove(playerId);
            setDirty();
        }
    }

    private static SkillTreeData fromTag(CompoundTag root) {
        SkillTreeData data = new SkillTreeData();
        CompoundTag playersTag = root.getCompoundOrEmpty(PLAYERS_KEY);
        for (String key : playersTag.keySet()) {
            UUID playerId;
            try { playerId = UUID.fromString(key); } catch (IllegalArgumentException ignored) { continue; }
            CompoundTag treeTags = playersTag.getCompoundOrEmpty(key).getCompoundOrEmpty(TREES_KEY);
            Map<String, Progress> trees = new HashMap<>();
            for (String treeId : treeTags.keySet()) {
                CompoundTag tag = treeTags.getCompoundOrEmpty(treeId);
                Set<String> unlocked = new HashSet<>();
                ListTag list = tag.getListOrEmpty(UNLOCKED_KEY);
                for (int index = 0; index < list.size(); index++) {
                    list.getString(index).ifPresent(id -> { if (!id.isBlank()) unlocked.add(id); });
                }
                Progress progress = new Progress(Math.max(0, tag.getIntOr(LEVEL_KEY, 0)),
                        Math.max(0L, tag.getLongOr(XP_KEY, 0L)), Math.max(0L, tag.getLongOr(TOTAL_XP_KEY, 0L)),
                        Math.max(0, tag.getIntOr(AVAILABLE_POINTS_KEY, 0)), Math.max(0, tag.getIntOr(ATTRIBUTE_POINTS_KEY, 0)),
                        Math.max(0, tag.getIntOr(SKILL_POINTS_KEY, 0)), unlocked,
                        Math.max(0L, tag.getLongOr(OVERFLOW_XP_KEY, 0L)), Math.max(0L, tag.getLongOr(DAILY_XP_KEY, 0L)),
                        tag.getLongOr(DAILY_EPOCH_DAY_KEY, Long.MIN_VALUE));
                trees.put(treeId, progress);
            }
            if (!trees.isEmpty()) data.players.put(playerId, trees);
        }
        return data;
    }

    private static CompoundTag toTag(SkillTreeData data) {
        CompoundTag root = new CompoundTag();
        CompoundTag playersTag = new CompoundTag();
        for (Map.Entry<UUID, Map<String, Progress>> player : data.players.entrySet()) {
            CompoundTag playerTag = new CompoundTag();
            CompoundTag treeTags = new CompoundTag();
            for (Map.Entry<String, Progress> entry : player.getValue().entrySet()) {
                Progress progress = entry.getValue();
                CompoundTag tag = new CompoundTag();
                tag.putInt(LEVEL_KEY, progress.level());
                tag.putLong(XP_KEY, progress.currentXp());
                tag.putLong(TOTAL_XP_KEY, progress.totalXp());
                tag.putInt(AVAILABLE_POINTS_KEY, progress.availablePoints());
                tag.putInt(ATTRIBUTE_POINTS_KEY, progress.attributePoints());
                tag.putInt(SKILL_POINTS_KEY, progress.skillPoints());
                ListTag unlocked = new ListTag();
                progress.unlockedSkills().forEach(id -> unlocked.add(StringTag.valueOf(id)));
                tag.put(UNLOCKED_KEY, unlocked);
                tag.putLong(OVERFLOW_XP_KEY, progress.overflowXp());
                tag.putLong(DAILY_XP_KEY, progress.dailyXp());
                tag.putLong(DAILY_EPOCH_DAY_KEY, progress.dailyEpochDay());
                treeTags.put(entry.getKey(), tag);
            }
            playerTag.put(TREES_KEY, treeTags);
            playersTag.put(player.getKey().toString(), playerTag);
        }
        root.put(PLAYERS_KEY, playersTag);
        return root;
    }

    /** Immutable progression snapshot; all updates pass through SkillTreeService validation. */
    public record Progress(int level, long currentXp, long totalXp, int availablePoints, int attributePoints,
                           int skillPoints, Set<String> unlockedSkills, long overflowXp, long dailyXp,
                           long dailyEpochDay) {
        public Progress {
            level = Math.max(0, level);
            currentXp = Math.max(0L, currentXp);
            totalXp = Math.max(0L, totalXp);
            availablePoints = Math.max(0, availablePoints);
            attributePoints = Math.max(0, attributePoints);
            skillPoints = Math.max(0, skillPoints);
            unlockedSkills = Set.copyOf(unlockedSkills == null ? Set.of() : unlockedSkills);
            overflowXp = Math.max(0L, overflowXp);
            dailyXp = Math.max(0L, dailyXp);
        }
        public static Progress empty() { return new Progress(0, 0L, 0L, 0, 0, 0, Set.of(), 0L, 0L, Long.MIN_VALUE); }
    }
}
