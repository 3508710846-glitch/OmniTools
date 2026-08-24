package dev.modmind.omnitools;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** World-persistent, player-owned item storage used by the cloud storage menus. */
public final class CloudStorageData extends SavedData {
    public static final int SLOTS_PER_PAGE = 45;
    private static final String DATA_ID = ModMindEntry.MOD_ID + "_cloud_storage";
    private static final String LEGACY_DATA_ID = "qiandao_cloud_storage";
    private static final String PLAYERS_KEY = "players";
    private static final String UNLOCKED_PAGES_KEY = "unlocked_pages";
    private static final String PAGES_KEY = "pages";

    public static final SavedDataType<CloudStorageData> TYPE = new SavedDataType<>(
            DATA_ID,
            CloudStorageData::new,
            CompoundTag.CODEC.xmap(CloudStorageData::fromTag, CloudStorageData::toTag),
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private static final SavedDataType<CloudStorageData> LEGACY_TYPE = new SavedDataType<>(
            LEGACY_DATA_ID,
            CloudStorageData::new,
            CompoundTag.CODEC.xmap(CloudStorageData::fromTag, CloudStorageData::toTag),
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    static SavedDataType<CloudStorageData> legacyType() {
        return LEGACY_TYPE;
    }

    private final Map<UUID, StorageRecord> players = new HashMap<>();

    public static CloudStorageData get(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("The overworld is not available while loading cloud storage data");
        }
        return overworld.getDataStorage().computeIfAbsent(TYPE);
    }

    public static CloudStorageData get(ServerPlayer player) {
        return get(player.level().getServer());
    }

    public synchronized int unlockedPages(UUID playerId) {
        StorageRecord record = players.get(playerId);
        return record == null ? CloudStorageConfig.MIN_PAGES : record.unlockedPages;
    }

    public synchronized List<ItemStack> page(UUID playerId, int page) {
        StorageRecord record = players.get(playerId);
        if (record == null || page < 0 || page >= record.unlockedPages) {
            return emptyPage();
        }
        return copyPage(record.pages.get(page));
    }

    public synchronized void savePage(UUID playerId, int page, List<ItemStack> items) {
        if (page < 0 || page >= CloudStorageConfig.MAX_PAGES) {
            throw new IllegalArgumentException("Cloud storage page is out of range");
        }
        if (items.size() != SLOTS_PER_PAGE) {
            throw new IllegalArgumentException("Cloud storage page has an unexpected slot count");
        }

        StorageRecord record = players.computeIfAbsent(playerId, ignored -> new StorageRecord());
        if (page >= record.unlockedPages) {
            throw new IllegalStateException("Cannot write to a locked cloud storage page");
        }
        if (ItemStack.listMatches(record.pages.get(page), items)) {
            return;
        }
        record.pages.set(page, copyPage(items));
        setDirty();
    }

    public synchronized PageUnlockResult unlockNextPage(UUID playerId, int configuredMaximum) {
        int maximum = Math.max(CloudStorageConfig.MIN_PAGES,
                Math.min(CloudStorageConfig.MAX_PAGES, configuredMaximum));
        StorageRecord record = players.computeIfAbsent(playerId, ignored -> new StorageRecord());
        if (record.unlockedPages >= maximum) {
            return new PageUnlockResult(false, record.unlockedPages);
        }

        record.unlockedPages++;
        record.ensurePageCount(record.unlockedPages);
        setDirty();
        return new PageUnlockResult(true, record.unlockedPages);
    }

    private static CloudStorageData fromTag(CompoundTag root) {
        CloudStorageData data = new CloudStorageData();
        CompoundTag playerTags = root.getCompoundOrEmpty(PLAYERS_KEY);
        for (String key : playerTags.keySet()) {
            try {
                UUID playerId = UUID.fromString(key);
                CompoundTag recordTag = playerTags.getCompoundOrEmpty(key);
                int unlockedPages = clampPageCount(recordTag.getIntOr(UNLOCKED_PAGES_KEY,
                        CloudStorageConfig.MIN_PAGES));
                StorageRecord record = new StorageRecord(unlockedPages);
                CompoundTag pageTags = recordTag.getCompoundOrEmpty(PAGES_KEY);
                for (int page = 0; page < record.unlockedPages; page++) {
                    CompoundTag itemTags = pageTags.getCompoundOrEmpty(Integer.toString(page));
                    for (String slotKey : itemTags.keySet()) {
                        int slot;
                        try {
                            slot = Integer.parseInt(slotKey);
                        } catch (NumberFormatException ignored) {
                            continue;
                        }
                        if (slot < 0 || slot >= SLOTS_PER_PAGE) {
                            continue;
                        }
                        ItemStack stack = ItemStack.CODEC.parse(NbtOps.INSTANCE,
                                        itemTags.getCompoundOrEmpty(slotKey))
                                .result()
                                .orElse(ItemStack.EMPTY);
                        if (!stack.isEmpty()) {
                            record.pages.get(page).set(slot, stack);
                        }
                    }
                }
                data.players.put(playerId, record);
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed player keys so one corrupted record cannot block world loading.
            }
        }
        return data;
    }

    private static CompoundTag toTag(CloudStorageData data) {
        CompoundTag root = new CompoundTag();
        CompoundTag playerTags = new CompoundTag();
        for (Map.Entry<UUID, StorageRecord> entry : data.players.entrySet()) {
            StorageRecord record = entry.getValue();
            CompoundTag recordTag = new CompoundTag();
            recordTag.putInt(UNLOCKED_PAGES_KEY, record.unlockedPages);
            CompoundTag pageTags = new CompoundTag();
            for (int page = 0; page < record.unlockedPages; page++) {
                CompoundTag itemTags = new CompoundTag();
                List<ItemStack> items = record.pages.get(page);
                for (int slot = 0; slot < SLOTS_PER_PAGE; slot++) {
                    ItemStack stack = items.get(slot);
                    if (stack.isEmpty()) {
                        continue;
                    }
                    Tag encoded = ItemStack.CODEC.encodeStart(NbtOps.INSTANCE, stack).result().orElse(null);
                    if (encoded instanceof CompoundTag itemTag) {
                        itemTags.put(Integer.toString(slot), itemTag);
                    } else {
                        System.err.println("[omnitools] Could not serialize a cloud storage item in slot " + slot);
                    }
                }
                pageTags.put(Integer.toString(page), itemTags);
            }
            recordTag.put(PAGES_KEY, pageTags);
            playerTags.put(entry.getKey().toString(), recordTag);
        }
        root.put(PLAYERS_KEY, playerTags);
        return root;
    }

    private static int clampPageCount(int value) {
        return Math.max(CloudStorageConfig.MIN_PAGES, Math.min(CloudStorageConfig.MAX_PAGES, value));
    }

    private static List<ItemStack> emptyPage() {
        List<ItemStack> items = new ArrayList<>(SLOTS_PER_PAGE);
        for (int slot = 0; slot < SLOTS_PER_PAGE; slot++) {
            items.add(ItemStack.EMPTY);
        }
        return items;
    }

    private static List<ItemStack> copyPage(List<ItemStack> source) {
        List<ItemStack> copy = new ArrayList<>(SLOTS_PER_PAGE);
        for (int slot = 0; slot < SLOTS_PER_PAGE; slot++) {
            ItemStack stack = source.get(slot);
            copy.add(stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
        }
        return copy;
    }

    public record PageUnlockResult(boolean unlocked, int unlockedPages) {
    }

    private static final class StorageRecord {
        private int unlockedPages;
        private final List<List<ItemStack>> pages = new ArrayList<>();

        private StorageRecord() {
            this(CloudStorageConfig.MIN_PAGES);
        }

        private StorageRecord(int unlockedPages) {
            this.unlockedPages = clampPageCount(unlockedPages);
            ensurePageCount(this.unlockedPages);
        }

        private void ensurePageCount(int count) {
            while (pages.size() < count) {
                pages.add(emptyPage());
            }
        }
    }
}
