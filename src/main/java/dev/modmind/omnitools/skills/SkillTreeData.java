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
    private static final String REWARD_POINTS_KEY = "reward_points";
    private static final String MASTERY_POINTS_KEY = "mastery_points";
    private static final String UNLOCKED_KEY = "unlocked_skills";
    private static final String OVERFLOW_XP_KEY = "overflow_xp";
    private static final String DAILY_XP_KEY = "daily_xp";
    private static final String DAILY_EPOCH_DAY_KEY = "daily_epoch_day";
    private static final String ULTIMATE_COOLDOWN_UNTIL_KEY = "ultimate_cooldown_until";
    private static final String ANNOUNCEMENTS_KEY = "announcements";
    private static final String LAST_ANNOUNCEMENT_AT_KEY = "last_announcement_at";
    private static final String PENDING_TREE_ID_KEY = "pending_tree_id";
    private static final String PENDING_TREE_LEVEL_KEY = "pending_tree_level";
    private static final String PENDING_TOTAL_LEVEL_KEY = "pending_total_level";

    public static final SavedDataType<SkillTreeData> TYPE = new SavedDataType<>(DATA_ID, SkillTreeData::new,
            CompoundTag.CODEC.xmap(SkillTreeData::fromTag, SkillTreeData::toTag),
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, Map<String, Progress>> players = new HashMap<>();
    private final Map<UUID, AnnouncementState> announcements = new HashMap<>();

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

    public synchronized AnnouncementState announcementState(UUID playerId) {
        return announcements.getOrDefault(playerId, AnnouncementState.empty());
    }

    public synchronized void replaceAnnouncementState(UUID playerId, AnnouncementState state) {
        if (playerId == null || state == null) return;
        if (state.isEmpty()) announcements.remove(playerId);
        else announcements.put(playerId, state);
        setDirty();
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
                        Math.max(0, tag.getIntOr(SKILL_POINTS_KEY, 0)), Math.max(0, tag.getIntOr(REWARD_POINTS_KEY, 0)),
                        Math.max(0, tag.getIntOr(MASTERY_POINTS_KEY, 0)), unlocked,
                        Math.max(0L, tag.getLongOr(OVERFLOW_XP_KEY, 0L)), Math.max(0L, tag.getLongOr(DAILY_XP_KEY, 0L)),
                        tag.getLongOr(DAILY_EPOCH_DAY_KEY, Long.MIN_VALUE),
                        Math.max(0L, tag.getLongOr(ULTIMATE_COOLDOWN_UNTIL_KEY, 0L)));
                trees.put(treeId, progress);
            }
            if (!trees.isEmpty()) data.players.put(playerId, trees);
            CompoundTag announcementTag = playersTag.getCompoundOrEmpty(key).getCompoundOrEmpty(ANNOUNCEMENTS_KEY);
            AnnouncementState announcement = new AnnouncementState(
                    Math.max(0L, announcementTag.getLongOr(LAST_ANNOUNCEMENT_AT_KEY, 0L)),
                    announcementTag.getStringOr(PENDING_TREE_ID_KEY, ""),
                    Math.max(0, announcementTag.getIntOr(PENDING_TREE_LEVEL_KEY, 0)),
                    Math.max(0, announcementTag.getIntOr(PENDING_TOTAL_LEVEL_KEY, 0)));
            if (!announcement.isEmpty()) data.announcements.put(playerId, announcement);
        }
        return data;
    }

    private static CompoundTag toTag(SkillTreeData data) {
        CompoundTag root = new CompoundTag();
        CompoundTag playersTag = new CompoundTag();
        Set<UUID> playerIds = new HashSet<>(data.players.keySet());
        playerIds.addAll(data.announcements.keySet());
        for (UUID playerId : playerIds) {
            CompoundTag playerTag = new CompoundTag();
            CompoundTag treeTags = new CompoundTag();
            for (Map.Entry<String, Progress> entry : data.players.getOrDefault(playerId, Map.of()).entrySet()) {
                Progress progress = entry.getValue();
                CompoundTag tag = new CompoundTag();
                tag.putInt(LEVEL_KEY, progress.level());
                tag.putLong(XP_KEY, progress.currentXp());
                tag.putLong(TOTAL_XP_KEY, progress.totalXp());
                tag.putInt(AVAILABLE_POINTS_KEY, progress.availablePoints());
                tag.putInt(ATTRIBUTE_POINTS_KEY, progress.attributePoints());
                tag.putInt(SKILL_POINTS_KEY, progress.skillPoints());
                tag.putInt(REWARD_POINTS_KEY, progress.rewardPoints());
                tag.putInt(MASTERY_POINTS_KEY, progress.masteryPoints());
                ListTag unlocked = new ListTag();
                progress.unlockedSkills().forEach(id -> unlocked.add(StringTag.valueOf(id)));
                tag.put(UNLOCKED_KEY, unlocked);
                tag.putLong(OVERFLOW_XP_KEY, progress.overflowXp());
                tag.putLong(DAILY_XP_KEY, progress.dailyXp());
                tag.putLong(DAILY_EPOCH_DAY_KEY, progress.dailyEpochDay());
                tag.putLong(ULTIMATE_COOLDOWN_UNTIL_KEY, progress.ultimateCooldownUntilEpochMillis());
                treeTags.put(entry.getKey(), tag);
            }
            playerTag.put(TREES_KEY, treeTags);
            AnnouncementState announcement = data.announcements.get(playerId);
            if (announcement != null && !announcement.isEmpty()) {
                CompoundTag announcementTag = new CompoundTag();
                announcementTag.putLong(LAST_ANNOUNCEMENT_AT_KEY, announcement.lastBroadcastAt());
                if (!announcement.pendingTreeId().isBlank()) {
                    announcementTag.putString(PENDING_TREE_ID_KEY, announcement.pendingTreeId());
                }
                announcementTag.putInt(PENDING_TREE_LEVEL_KEY, announcement.pendingTreeLevel());
                announcementTag.putInt(PENDING_TOTAL_LEVEL_KEY, announcement.pendingTotalLevel());
                playerTag.put(ANNOUNCEMENTS_KEY, announcementTag);
            }
            playersTag.put(playerId.toString(), playerTag);
        }
        root.put(PLAYERS_KEY, playersTag);
        return root;
    }

    /** Immutable progression snapshot; all updates pass through SkillTreeService validation. */
    public record Progress(int level, long currentXp, long totalXp, int availablePoints, int attributePoints,
                           int skillPoints, int rewardPoints, int masteryPoints, Set<String> unlockedSkills, long overflowXp, long dailyXp,
                           long dailyEpochDay, long ultimateCooldownUntilEpochMillis) {
        public Progress {
            level = Math.max(0, level);
            currentXp = Math.max(0L, currentXp);
            totalXp = Math.max(0L, totalXp);
            availablePoints = Math.max(0, availablePoints);
            attributePoints = Math.max(0, attributePoints);
            skillPoints = Math.max(0, skillPoints);
            rewardPoints = Math.max(0, rewardPoints);
            masteryPoints = Math.max(0, masteryPoints);
            unlockedSkills = Set.copyOf(unlockedSkills == null ? Set.of() : unlockedSkills);
            overflowXp = Math.max(0L, overflowXp);
            dailyXp = Math.max(0L, dailyXp);
            ultimateCooldownUntilEpochMillis = Math.max(0L, ultimateCooldownUntilEpochMillis);
        }
        /** Compatibility constructor for worlds saved before reward and mastery point accounting. */
        public Progress(int level, long currentXp, long totalXp, int availablePoints, int attributePoints,
                        int skillPoints, Set<String> unlockedSkills, long overflowXp, long dailyXp,
                        long dailyEpochDay) {
            this(level, currentXp, totalXp, availablePoints, attributePoints, skillPoints, 0, 0, unlockedSkills,
                    overflowXp, dailyXp, dailyEpochDay, 0L);
        }
        public long masteryXp() { return overflowXp; }
        /** Compatibility constructor for progress snapshots that predate ultimate cooldown persistence. */
        public Progress(int level, long currentXp, long totalXp, int availablePoints, int attributePoints,
                        int skillPoints, int rewardPoints, int masteryPoints, Set<String> unlockedSkills, long overflowXp,
                        long dailyXp, long dailyEpochDay) {
            this(level, currentXp, totalXp, availablePoints, attributePoints, skillPoints, rewardPoints, masteryPoints,
                    unlockedSkills, overflowXp, dailyXp, dailyEpochDay, 0L);
        }
        public static Progress empty() { return new Progress(0, 0L, 0L, 0, 0, 0, 0, 0, Set.of(), 0L, 0L, Long.MIN_VALUE, 0L); }
    }

    /** Persisted announcement throttle and merged milestone payload for one player. */
    public record AnnouncementState(long lastBroadcastAt, String pendingTreeId, int pendingTreeLevel,
                                    int pendingTotalLevel) {
        public AnnouncementState {
            lastBroadcastAt = Math.max(0L, lastBroadcastAt);
            pendingTreeId = pendingTreeId == null ? "" : pendingTreeId.trim().toLowerCase(java.util.Locale.ROOT);
            pendingTreeLevel = Math.max(0, pendingTreeLevel);
            pendingTotalLevel = Math.max(0, pendingTotalLevel);
            if (pendingTreeId.isBlank()) pendingTreeLevel = 0;
        }
        public boolean hasPending() { return pendingTreeLevel > 0 || pendingTotalLevel > 0; }
        public boolean isEmpty() { return lastBroadcastAt == 0L && !hasPending(); }
        public static AnnouncementState empty() { return new AnnouncementState(0L, "", 0, 0); }
    }
}
