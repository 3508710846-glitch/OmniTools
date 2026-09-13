package dev.modmind.omnitools.skills;

import dev.modmind.omnitools.ModMindEntry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Persistent idempotency ledger for skill XP and ability events.
 *
 * Only bounded operation identifiers are stored.  The ledger is intentionally separate from the
 * player progression snapshot so a replayed event cannot be mistaken for a legitimate XP change
 * after a restart.
 */
public final class SkillLedgerData extends SavedData {
    private static final String DATA_ID = ModMindEntry.MOD_ID + "_skill_ledger";
    private static final String PLAYERS_KEY = "players";
    private static final String OPERATIONS_KEY = "operations";
    private static final int MAX_PLAYERS = 20_000;

    public static final SavedDataType<SkillLedgerData> TYPE = new SavedDataType<>(DATA_ID, SkillLedgerData::new,
            CompoundTag.CODEC.xmap(SkillLedgerData::fromTag, SkillLedgerData::toTag),
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, SkillLedger> players = new HashMap<>();

    public static SkillLedgerData get(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) throw new IllegalStateException("The overworld is unavailable while loading skill ledger");
        return overworld.getDataStorage().computeIfAbsent(TYPE);
    }

    /** Atomically claims an operation for a player; returns false for a replay. */
    public synchronized boolean claim(UUID playerId, String operationId) {
        if (playerId == null) return false;
        if (operationId == null || operationId.isBlank()) return true;
        SkillLedger ledger = players.computeIfAbsent(playerId, ignored -> new SkillLedger());
        boolean claimed = ledger.claim(operationId);
        if (claimed) setDirty();
        trimPlayers();
        return claimed;
    }

    public synchronized boolean contains(UUID playerId, String operationId) {
        SkillLedger ledger = players.get(playerId);
        return ledger != null && ledger.contains(operationId);
    }

    public synchronized Set<String> operations(UUID playerId) {
        SkillLedger ledger = players.get(playerId);
        return ledger == null ? Set.of() : ledger.snapshot();
    }

    private void trimPlayers() {
        if (players.size() <= MAX_PLAYERS) return;
        players.keySet().stream().limit(players.size() - MAX_PLAYERS).toList().forEach(players::remove);
    }

    private static SkillLedgerData fromTag(CompoundTag root) {
        SkillLedgerData data = new SkillLedgerData();
        CompoundTag playersTag = root.getCompoundOrEmpty(PLAYERS_KEY);
        for (String key : playersTag.keySet()) {
            UUID playerId;
            try { playerId = UUID.fromString(key); } catch (IllegalArgumentException ignored) { continue; }
            ListTag operations = playersTag.getCompoundOrEmpty(key).getListOrEmpty(OPERATIONS_KEY);
            SkillLedger ledger = new SkillLedger();
            for (int index = 0; index < operations.size(); index++) {
                operations.getString(index).ifPresent(value -> ledger.claim(value));
            }
            if (!ledger.snapshot().isEmpty()) data.players.put(playerId, ledger);
        }
        data.trimPlayers();
        return data;
    }

    private static CompoundTag toTag(SkillLedgerData data) {
        CompoundTag root = new CompoundTag();
        CompoundTag playersTag = new CompoundTag();
        for (Map.Entry<UUID, SkillLedger> entry : data.players.entrySet()) {
            Set<String> operations = entry.getValue().snapshot();
            if (operations.isEmpty()) continue;
            CompoundTag playerTag = new CompoundTag();
            ListTag list = new ListTag();
            operations.forEach(operation -> list.add(StringTag.valueOf(operation)));
            playerTag.put(OPERATIONS_KEY, list);
            playersTag.put(entry.getKey().toString(), playerTag);
        }
        root.put(PLAYERS_KEY, playersTag);
        return root;
    }
}
