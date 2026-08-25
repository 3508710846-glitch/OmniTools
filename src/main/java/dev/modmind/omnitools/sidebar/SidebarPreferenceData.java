package dev.modmind.omnitools.sidebar;

import dev.modmind.omnitools.ModMindEntry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** World-persistent per-player visibility preferences for the sidebar. */
public final class SidebarPreferenceData extends SavedData {
    private static final String DATA_ID = ModMindEntry.MOD_ID + "_sidebar_preferences";
    private static final String PLAYERS_KEY = "players";
    private static final String VISIBLE_KEY = "visible";

    public static final SavedDataType<SidebarPreferenceData> TYPE = new SavedDataType<>(
            DATA_ID,
            SidebarPreferenceData::new,
            CompoundTag.CODEC.xmap(SidebarPreferenceData::fromTag, SidebarPreferenceData::toTag),
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, Boolean> visibleByPlayer = new HashMap<>();

    public static SidebarPreferenceData get(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("The overworld is not available while loading sidebar preferences");
        }
        return overworld.getDataStorage().computeIfAbsent(TYPE);
    }

    public static SidebarPreferenceData get(ServerPlayer player) {
        return get(player.level().getServer());
    }

    public synchronized boolean visible(UUID playerId, boolean defaultVisible) {
        return visibleByPlayer.getOrDefault(playerId, defaultVisible);
    }

    public synchronized void setVisible(UUID playerId, boolean visible) {
        Boolean previous = visibleByPlayer.put(playerId, visible);
        if (previous == null || previous != visible) {
            setDirty();
        }
    }

    public synchronized boolean toggle(UUID playerId, boolean defaultVisible) {
        boolean next = !visible(playerId, defaultVisible);
        setVisible(playerId, next);
        return next;
    }

    private static SidebarPreferenceData fromTag(CompoundTag root) {
        SidebarPreferenceData data = new SidebarPreferenceData();
        CompoundTag players = root.getCompoundOrEmpty(PLAYERS_KEY);
        for (String key : players.keySet()) {
            try {
                CompoundTag player = players.getCompoundOrEmpty(key);
                data.visibleByPlayer.put(UUID.fromString(key), player.getBooleanOr(VISIBLE_KEY, true));
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed player keys without preventing the world from loading.
            }
        }
        return data;
    }

    private static CompoundTag toTag(SidebarPreferenceData data) {
        CompoundTag root = new CompoundTag();
        CompoundTag players = new CompoundTag();
        for (Map.Entry<UUID, Boolean> entry : data.visibleByPlayer.entrySet()) {
            CompoundTag value = new CompoundTag();
            value.putBoolean(VISIBLE_KEY, entry.getValue());
            players.put(entry.getKey().toString(), value);
        }
        root.put(PLAYERS_KEY, players);
        return root;
    }
}
