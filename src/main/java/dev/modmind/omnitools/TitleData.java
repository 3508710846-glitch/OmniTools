package dev.modmind.omnitools;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.modmind.omnitools.config.ConfigPaths;
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
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** World-persistent player title state, kept separate from administrator definitions. */
public final class TitleData extends SavedData {
    private static final String DATA_ID = ModMindEntry.MOD_ID + "_titles";
    private static final String PLAYERS_KEY = "players";
    private static volatile MinecraftServer currentServer;

    public static final SavedDataType<TitleData> TYPE = new SavedDataType<>(
            DATA_ID,
            TitleData::new,
            CompoundTag.CODEC.xmap(TitleData::fromTag, TitleData::toTag),
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, PlayerRecord> players = new HashMap<>();

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
        Path path = ConfigPaths.legacyDir().resolve("omnitools-titles.json");
        if (!Files.exists(path)) {
            path = ConfigPaths.oldConfig("omnitools-titles.json");
        }
        if (!Files.exists(path)) {
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
                if (value.has("unlocked") && value.get("unlocked").isJsonArray()) {
                    for (JsonElement title : value.getAsJsonArray("unlocked")) {
                        if (title.isJsonPrimitive() && title.getAsJsonPrimitive().isString()) {
                            record.unlocked.add(title.getAsString().trim().toLowerCase(java.util.Locale.ROOT));
                        }
                    }
                }
                record.selected = value.has("selected") ? value.get("selected").getAsString()
                        .trim().toLowerCase(java.util.Locale.ROOT) : "";
                record.effectsEnabled = !value.has("effects_enabled") || value.get("effects_enabled").getAsBoolean();
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

    public synchronized boolean grant(UUID playerId, String playerName, String titleId) {
        PlayerRecord record = record(playerId, playerName);
        boolean changed = record.unlocked.add(titleId);
        if (changed) {
            setDirty();
        }
        return changed;
    }

    public synchronized boolean revoke(UUID playerId, String playerName, String titleId) {
        PlayerRecord record = record(playerId, playerName);
        boolean changed = record.unlocked.remove(titleId);
        if (changed && titleId.equals(record.selected)) {
            record.selected = "";
        }
        if (changed) {
            setDirty();
        }
        return changed;
    }

    public synchronized boolean select(UUID playerId, String playerName, String titleId) {
        PlayerRecord record = record(playerId, playerName);
        if (!record.unlocked.contains(titleId)) {
            return false;
        }
        boolean changed = !titleId.equals(record.selected);
        record.selected = titleId;
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

    private static TitleData fromTag(CompoundTag root) {
        TitleData data = new TitleData();
        CompoundTag playerTags = root.getCompoundOrEmpty(PLAYERS_KEY);
        for (String key : playerTags.keySet()) {
            try {
                UUID id = UUID.fromString(key);
                CompoundTag tag = playerTags.getCompoundOrEmpty(key);
                PlayerRecord record = new PlayerRecord();
                record.name = tag.getStringOr("name", "");
                readIds(tag.getListOrEmpty("unlocked"), record.unlocked);
                record.selected = tag.getStringOr("selected", "");
                record.effectsEnabled = tag.getBooleanOr("effects_enabled", true);
                data.players.put(id, record);
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed player keys.
            }
        }
        return data;
    }

    private static void readIds(ListTag list, Set<String> destination) {
        for (int index = 0; index < list.size(); index++) {
            list.getString(index).ifPresent(value -> {
                if (!value.isBlank()) {
                    destination.add(value.trim().toLowerCase(java.util.Locale.ROOT));
                }
            });
        }
    }

    private static CompoundTag toTag(TitleData data) {
        CompoundTag root = new CompoundTag();
        CompoundTag players = new CompoundTag();
        for (Map.Entry<UUID, PlayerRecord> entry : data.players.entrySet()) {
            PlayerRecord record = entry.getValue();
            CompoundTag tag = new CompoundTag();
            tag.putString("name", record.name);
            ListTag unlocked = new ListTag();
            record.unlocked.forEach(id -> unlocked.add(StringTag.valueOf(id)));
            tag.put("unlocked", unlocked);
            tag.putString("selected", record.selected);
            tag.putBoolean("effects_enabled", record.effectsEnabled);
            players.put(entry.getKey().toString(), tag);
        }
        root.put(PLAYERS_KEY, players);
        return root;
    }

    public static final class PlayerRecord {
        private String name = "";
        private final Set<String> unlocked = new HashSet<>();
        private String selected = "";
        private boolean effectsEnabled = true;

        public String name() {
            return name;
        }

        public Set<String> unlocked() {
            return Set.copyOf(unlocked);
        }

        public String selected() {
            return selected;
        }

        public boolean effectsEnabled() {
            return effectsEnabled;
        }
    }
}
