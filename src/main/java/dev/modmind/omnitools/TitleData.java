package dev.modmind.omnitools;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.modmind.omnitools.config.ConfigPaths;
import dev.modmind.omnitools.entitlement.TimedEntitlement;
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

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** World-persistent title ownership and active-time entitlement state. */
public final class TitleData extends SavedData {
    private static final String DATA_ID = ModMindEntry.MOD_ID + "_titles";
    private static final String LEGACY_DATA_ID = "qiandao_titles";
    private static final String DATA_VERSION_KEY = "data_version";
    private static final int DATA_VERSION = 2;
    private static final String PLAYERS_KEY = "players";
    private static final String UNLOCKED_KEY = "unlocked";
    private static final String TITLES_KEY = "titles";
    private static final String REWARD_EVENTS_KEY = "reward_events";
    private static final String MODE_KEY = "mode";
    private static final String REMAINING_TICKS_KEY = "remaining_active_ticks";
    private static final String TOTAL_TICKS_KEY = "total_granted_ticks";
    private static final String GRANTED_AT_KEY = "granted_at";
    private static final String RENEWAL_KEY = "renewal";
    private static volatile MinecraftServer currentServer;

    public static final SavedDataType<TitleData> TYPE = new SavedDataType<>(
            DATA_ID,
            TitleData::new,
            CompoundTag.CODEC.xmap(TitleData::fromTag, TitleData::toTag),
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private static final SavedDataType<TitleData> LEGACY_TYPE = new SavedDataType<>(
            LEGACY_DATA_ID,
            TitleData::new,
            CompoundTag.CODEC.xmap(TitleData::fromTag, TitleData::toTag),
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, PlayerRecord> players = new HashMap<>();
    private boolean timedChangesPending;

    static SavedDataType<TitleData> legacyType() {
        return LEGACY_TYPE;
    }

    public static void bind(MinecraftServer server) {
        currentServer = server;
    }

    public static void unbind(MinecraftServer server) {
        if (currentServer == server) {
            currentServer = null;
        }
    }

    public static TitleData current() {
        MinecraftServer server = currentServer;
        return server == null ? null : get(server);
    }

    public static TitleData get(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("The overworld is not available while loading title data");
        }
        return overworld.getDataStorage().computeIfAbsent(TYPE);
    }

    public static TitleData get(ServerPlayer player) {
        return get(player.level().getServer());
    }

    /** Imports player state from the archived pre-SavedData title file once per player. */
    public static void importLegacy(MinecraftServer server) {
        Path path = findLegacyTitleFile();
        if (path == null) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement root = new Gson().fromJson(reader, JsonElement.class);
            if (root == null || !root.isJsonObject()) {
                return;
            }
            JsonObject playersObject = root.getAsJsonObject().getAsJsonObject(PLAYERS_KEY);
            if (playersObject == null) {
                return;
            }
            TitleData data = get(server);
            boolean changed = false;
            for (var entry : playersObject.entrySet()) {
                UUID playerId;
                try {
                    playerId = UUID.fromString(entry.getKey());
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                if (!entry.getValue().isJsonObject() || data.players.containsKey(playerId)) {
                    continue;
                }
                JsonObject value = entry.getValue().getAsJsonObject();
                PlayerRecord record = new PlayerRecord();
                record.name = value.has("name") ? value.get("name").getAsString() : "";
                if (value.has(UNLOCKED_KEY) && value.get(UNLOCKED_KEY).isJsonArray()) {
                    for (JsonElement title : value.getAsJsonArray(UNLOCKED_KEY)) {
                        if (title.isJsonPrimitive() && title.getAsJsonPrimitive().isString()) {
                            record.unlocked.add(normalizeTitleId(title.getAsString()));
                        }
                    }
                }
                record.selected = value.has("selected") ? normalizeTitleId(value.get("selected").getAsString()) : "";
                record.effectsEnabled = !value.has("effects_enabled") || value.get("effects_enabled").getAsBoolean();
                record.migrateLegacyUnlocked();
                data.players.put(playerId, record);
                changed = true;
            }
            if (changed) {
                data.setDirty();
            }
        } catch (Exception exception) {
            System.err.println("[omnitools] Could not import legacy title player state: " + exception.getMessage());
        }
    }

    public synchronized PlayerRecord record(UUID playerId, String playerName) {
        PlayerRecord record = players.computeIfAbsent(playerId, ignored -> new PlayerRecord());
        if (playerName != null && !playerName.isBlank() && !playerName.equals(record.name)) {
            record.name = playerName.trim();
            setDirty();
        }
        return record;
    }

    public synchronized PlayerRecord read(UUID playerId) {
        return players.get(playerId);
    }

    public synchronized void remember(UUID playerId, String playerName) {
        record(playerId, playerName);
    }

    public synchronized boolean toggleEffects(UUID playerId, String playerName) {
        PlayerRecord record = record(playerId, playerName);
        record.effectsEnabled = !record.effectsEnabled;
        setDirty();
        return record.effectsEnabled;
    }

    /** Existing administrator grants remain permanent. */
    public synchronized boolean grant(UUID playerId, String playerName, String titleId) {
        return grantEntitlement(playerId, playerName, titleId, TimedEntitlement.permanentGrant(), System.currentTimeMillis())
                == GrantResult.GRANTED;
    }

    public synchronized GrantResult grantEntitlement(UUID playerId, String playerName, String titleId,
                                                       TimedEntitlement.Grant grant, long grantedAt) {
        String normalizedTitle = normalizeTitleId(titleId);
        if (normalizedTitle.isEmpty()) {
            throw new IllegalArgumentException("title id must not be blank");
        }
        PlayerRecord record = record(playerId, playerName);
        TimedEntitlement existing = record.titles.get(normalizedTitle);
        TimedEntitlement next = grant.applyTo(existing, grantedAt);
        if (next.equals(existing)) {
            return GrantResult.ALREADY_OWNED;
        }
        boolean previouslyOwned = record.unlocked.contains(normalizedTitle);
        record.titles.put(normalizedTitle, next);
        record.unlocked.add(normalizedTitle); // Retained for backward-compatible SavedData readers.
        setDirty();
        return previouslyOwned ? GrantResult.RENEWED : GrantResult.GRANTED;
    }

    /** Records the event before title mutation so recovery never renews the same reward twice. */
    public synchronized GrantResult grantReward(UUID playerId, String playerName, String titleId, String eventId,
                                                String rewardId, TimedEntitlement.Grant grant) {
        PlayerRecord record = record(playerId, playerName);
        if (!record.rewardEvents.add(rewardEventKey(eventId, rewardId))) {
            return GrantResult.ALREADY_OWNED;
        }
        GrantResult result = grantEntitlement(playerId, playerName, titleId, grant, System.currentTimeMillis());
        setDirty();
        return result;
    }

    /** Source-compatible permanent reward method for legacy callers. */
    public synchronized boolean grantReward(UUID playerId, String playerName, String titleId, String eventId,
                                            String rewardId) {
        return grantReward(playerId, playerName, titleId, eventId, rewardId,
                TimedEntitlement.permanentGrant()) == GrantResult.GRANTED;
    }

    public synchronized boolean revoke(UUID playerId, String playerName, String titleId) {
        PlayerRecord record = record(playerId, playerName);
        String normalizedTitle = normalizeTitleId(titleId);
        boolean changed = record.unlocked.remove(normalizedTitle);
        changed |= record.titles.remove(normalizedTitle) != null;
        if (changed && normalizedTitle.equals(record.selected)) {
            record.selected = "";
        }
        if (changed) {
            setDirty();
        }
        return changed;
    }

    public synchronized boolean select(UUID playerId, String playerName, String titleId) {
        PlayerRecord record = record(playerId, playerName);
        String normalizedTitle = normalizeTitleId(titleId);
        if (!record.owns(normalizedTitle)) {
            return false;
        }
        boolean changed = !normalizedTitle.equals(record.selected);
        record.selected = normalizedTitle;
        if (changed) {
            setDirty();
        }
        return true;
    }

    public synchronized boolean clearSelection(UUID playerId, String playerName) {
        PlayerRecord record = record(playerId, playerName);
        boolean changed = !record.selected.isEmpty();
        record.selected = "";
        if (changed) {
            setDirty();
        }
        return changed;
    }

    /** Consumes exactly one tick only while the selected temporary title is actively worn online. */
    public synchronized TickResult consumeSelectedActiveTick(UUID playerId) {
        PlayerRecord record = players.get(playerId);
        if (record == null || record.selected.isEmpty()) {
            return TickResult.NONE;
        }
        String titleId = record.selected;
        TimedEntitlement entitlement = record.titles.get(titleId);
        if (entitlement == null || entitlement.isPermanent()) {
            return TickResult.NONE;
        }
        if (!entitlement.isActive()) {
            expire(record, titleId);
            return new TickResult(titleId, true, false);
        }
        TimedEntitlement next = entitlement.consumeActiveTick();
        if (!next.isActive()) {
            expire(record, titleId);
            return new TickResult(titleId, true, true);
        }
        record.titles.put(titleId, next);
        timedChangesPending = true;
        return new TickResult(titleId, false, true);
    }

    /** Batched persistence avoids writing SavedData on every active-wear tick. */
    public synchronized void flushTimedChanges() {
        if (timedChangesPending) {
            timedChangesPending = false;
            setDirty();
        }
    }

    private void expire(PlayerRecord record, String titleId) {
        record.titles.remove(titleId);
        record.unlocked.remove(titleId);
        if (titleId.equals(record.selected)) {
            record.selected = "";
        }
        timedChangesPending = false;
        setDirty();
    }

    static TitleData fromTag(CompoundTag root) {
        TitleData data = new TitleData();
        CompoundTag playerTags = root.getCompoundOrEmpty(PLAYERS_KEY);
        for (String key : playerTags.keySet()) {
            try {
                UUID id = UUID.fromString(key);
                CompoundTag tag = playerTags.getCompoundOrEmpty(key);
                PlayerRecord record = new PlayerRecord();
                record.name = tag.getStringOr("name", "");
                readIds(tag.getListOrEmpty(UNLOCKED_KEY), record.unlocked);
                readIds(tag.getListOrEmpty(REWARD_EVENTS_KEY), record.rewardEvents);
                record.selected = normalizeTitleId(tag.getStringOr("selected", ""));
                record.effectsEnabled = tag.getBooleanOr("effects_enabled", true);
                CompoundTag titleTags = tag.getCompoundOrEmpty(TITLES_KEY);
                for (String titleId : titleTags.keySet()) {
                    readEntitlement(titleId, titleTags.getCompoundOrEmpty(titleId)).ifPresent(value ->
                            record.titles.put(normalizeTitleId(titleId), value));
                }
                record.migrateLegacyUnlocked();
                if (!record.selected.isEmpty() && !record.owns(record.selected)) {
                    record.selected = "";
                }
                data.players.put(id, record);
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed player keys and malformed entitlement values.
            }
        }
        return data;
    }

    private static Optional<TimedEntitlement> readEntitlement(String titleId, CompoundTag tag) {
        String normalizedTitle = normalizeTitleId(titleId);
        if (normalizedTitle.isEmpty()) {
            return Optional.empty();
        }
        try {
            TimedEntitlement.Mode mode = TimedEntitlement.Mode.parse(tag.getStringOr(MODE_KEY, "permanent"));
            TimedEntitlement.RenewalPolicy renewal = TimedEntitlement.RenewalPolicy.parse(
                    tag.getStringOr(RENEWAL_KEY, "extend"));
            return Optional.of(mode == TimedEntitlement.Mode.PERMANENT
                    ? TimedEntitlement.permanent(tag.getLongOr(GRANTED_AT_KEY, 0L))
                    : new TimedEntitlement(mode, tag.getLongOr(REMAINING_TICKS_KEY, 0L),
                    tag.getLongOr(TOTAL_TICKS_KEY, 0L), tag.getLongOr(GRANTED_AT_KEY, 0L), renewal));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    static CompoundTag toTag(TitleData data) {
        CompoundTag root = new CompoundTag();
        root.putInt(DATA_VERSION_KEY, DATA_VERSION);
        CompoundTag players = new CompoundTag();
        for (Map.Entry<UUID, PlayerRecord> entry : data.players.entrySet()) {
            PlayerRecord record = entry.getValue();
            CompoundTag tag = new CompoundTag();
            tag.putString("name", record.name);
            ListTag unlocked = new ListTag();
            record.unlocked.stream().sorted().forEach(id -> unlocked.add(StringTag.valueOf(id)));
            tag.put(UNLOCKED_KEY, unlocked);
            CompoundTag titles = new CompoundTag();
            record.titles.forEach((id, entitlement) -> titles.put(id, writeEntitlement(entitlement)));
            tag.put(TITLES_KEY, titles);
            ListTag rewardEvents = new ListTag();
            record.rewardEvents.stream().sorted().forEach(id -> rewardEvents.add(StringTag.valueOf(id)));
            tag.put(REWARD_EVENTS_KEY, rewardEvents);
            tag.putString("selected", record.selected);
            tag.putBoolean("effects_enabled", record.effectsEnabled);
            players.put(entry.getKey().toString(), tag);
        }
        root.put(PLAYERS_KEY, players);
        return root;
    }

    private static CompoundTag writeEntitlement(TimedEntitlement entitlement) {
        CompoundTag tag = new CompoundTag();
        tag.putString(MODE_KEY, entitlement.mode().serializedName());
        tag.putLong(GRANTED_AT_KEY, entitlement.grantedAt());
        tag.putString(RENEWAL_KEY, entitlement.renewalPolicy().serializedName());
        if (!entitlement.isPermanent()) {
            tag.putLong(REMAINING_TICKS_KEY, entitlement.remainingActiveTicks());
            tag.putLong(TOTAL_TICKS_KEY, entitlement.totalGrantedTicks());
        }
        return tag;
    }

    private static void readIds(ListTag list, Set<String> destination) {
        for (int index = 0; index < list.size(); index++) {
            list.getString(index).ifPresent(value -> {
                String id = normalizeTitleId(value);
                if (!id.isEmpty()) {
                    destination.add(id);
                }
            });
        }
    }

    private static Path findLegacyTitleFile() {
        Path[] candidates = {
                ConfigPaths.legacyDir().resolve("omnitools-titles.json"),
                ConfigPaths.oldConfig("omnitools-titles.json"),
                ConfigPaths.legacyDir().resolve("qiandao-titles.json"),
                ConfigPaths.oldConfig("qiandao-titles.json")
        };
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String rewardEventKey(String eventId, String rewardId) {
        if (eventId == null || eventId.isBlank() || rewardId == null || rewardId.isBlank()) {
            throw new IllegalArgumentException("Reward event and reward ids must not be blank");
        }
        return eventId.trim() + "#" + rewardId.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String normalizeTitleId(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public enum GrantResult {
        GRANTED,
        RENEWED,
        ALREADY_OWNED
    }

    public record TickResult(String titleId, boolean expired, boolean consumed) {
        static final TickResult NONE = new TickResult("", false, false);
    }

    public static final class PlayerRecord {
        private String name = "";
        /** Retained for backward compatibility; title ownership is authoritative in {@link #titles}. */
        private final Set<String> unlocked = new HashSet<>();
        private final Map<String, TimedEntitlement> titles = new LinkedHashMap<>();
        private final Set<String> rewardEvents = new HashSet<>();
        private String selected = "";
        private boolean effectsEnabled = true;

        private void migrateLegacyUnlocked() {
            for (String titleId : Set.copyOf(unlocked)) {
                titles.putIfAbsent(titleId, TimedEntitlement.permanent(0L));
            }
            unlocked.addAll(titles.keySet());
        }

        private boolean owns(String titleId) {
            TimedEntitlement entitlement = titles.get(titleId);
            return entitlement != null && (entitlement.isPermanent() || entitlement.isActive());
        }

        public String name() {
            return name;
        }

        public Set<String> unlocked() {
            return titles.entrySet().stream().filter(entry -> entry.getValue().isPermanent() || entry.getValue().isActive())
                    .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        public Optional<TimedEntitlement> entitlement(String titleId) {
            return Optional.ofNullable(titles.get(normalizeTitleId(titleId)));
        }

        public String selected() {
            return selected;
        }

        public boolean effectsEnabled() {
            return effectsEnabled;
        }
    }
}
