package dev.modmind.omnitools;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.DimensionDataStorage;

/**
 * Imports world data written by the former qiandao mod id into the current
 * omnitools SavedData ids. The legacy files are deliberately left in place so
 * administrators can roll back or inspect them after an upgrade.
 */
public final class LegacySavedDataMigration {
    private LegacySavedDataMigration() {
    }

    public static void migrate(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            System.err.println("[omnitools] Could not migrate legacy world data: overworld is unavailable");
            return;
        }

        DimensionDataStorage storage = overworld.getDataStorage();
        migrateCheckin(storage);
        migrateTitles(storage);
        migrateAchievements(storage);
        migrateCloudStorage(storage);
    }

    private static void migrateCheckin(DimensionDataStorage storage) {
        migrate("checkin", storage, CheckinData.TYPE, CheckinData.legacyType());
    }

    private static void migrateTitles(DimensionDataStorage storage) {
        migrate("titles", storage, TitleData.TYPE, TitleData.legacyType());
    }

    private static void migrateAchievements(DimensionDataStorage storage) {
        migrate("achievements", storage, AchievementData.TYPE, AchievementData.legacyType());
    }

    private static void migrateCloudStorage(DimensionDataStorage storage) {
        migrate("cloud storage", storage, CloudStorageData.TYPE, CloudStorageData.legacyType());
    }

    private static <T extends net.minecraft.world.level.saveddata.SavedData> void migrate(
            String label,
            DimensionDataStorage storage,
            net.minecraft.world.level.saveddata.SavedDataType<T> currentType,
            net.minecraft.world.level.saveddata.SavedDataType<T> legacyType) {
        try {
            // A non-null current value means the new id was already loaded, so
            // never replace it with a legacy copy.
            if (storage.get(currentType) != null) {
                return;
            }
            T legacyData = storage.get(legacyType);
            if (legacyData == null) {
                return;
            }
            storage.set(currentType, legacyData);
            System.out.println("[omnitools] Migrated legacy " + label + " data from "
                    + legacyType.id() + " to " + currentType.id());
        } catch (Exception exception) {
            // A broken legacy file must not prevent the server from starting;
            // the normal SavedData loader will create an empty current record.
            System.err.println("[omnitools] Could not migrate legacy " + label + " data: "
                    + exception.getMessage());
        }
    }
}
