package dev.modmind.omnitools.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigMigrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void migratesV1WithLegacyCommandCompatibilityAndSafeNewModules() throws IOException {
        Path root = temporaryDirectory.resolve("omnitools");
        writeRoot(root, """
                {
                  "format_version": 1,
                  "global": { "timezone": "UTC" },
                  "modules": { "daily_checkin": { "enabled": true } }
                }
                """);

        ConfigMigration.migrate(root, temporaryDirectory.resolve("legacy-config"));

        OmniToolsRootConfig migrated = OmniToolsRootConfig.load(root.resolve("config.json"));
        assertEquals(OmniToolsRootConfig.CURRENT_FORMAT_VERSION, migrated.formatVersion());
        assertEquals("UTC", migrated.timezone());
        assertTrue(migrated.commandSecurity().isPermissive());
        assertEquals(CommandSecurityConfig.LEGACY_COOLDOWN_TICKS, migrated.commandSecurity().cooldownTicks());
        assertFalse(migrated.enabled(ModuleId.COMMAND_MENU));
        assertFalse(migrated.enabled(ModuleId.SIDEBAR));
        assertFalse(migrated.enabled(ModuleId.CDK));
        assertFalse(migrated.enabled(ModuleId.LEADERBOARDS));
        assertTrue(hasBackup(root));
    }

    @Test
    void migratesV2WithoutWeakeningAnExplicitCommandWhitelist() throws IOException {
        Path root = temporaryDirectory.resolve("omnitools");
        writeRoot(root, """
                {
                  "format_version": 2,
                  "global": {
                    "language": "en_us",
                    "command_security": {
                      "allowed_roots": ["spawn", "home"],
                      "max_command_length": 200,
                      "cooldown_ticks": 15
                    }
                  },
                  "modules": { "command_menu": { "enabled": true } }
                }
                """);

        ConfigMigration.migrate(root, temporaryDirectory.resolve("legacy-config"));

        OmniToolsRootConfig migrated = OmniToolsRootConfig.load(root.resolve("config.json"));
        assertEquals("en_us", migrated.language());
        assertEquals(java.util.List.of("spawn", "home"), migrated.commandSecurity().allowedRoots());
        assertEquals(15, migrated.commandSecurity().cooldownTicks());
        assertTrue(migrated.enabled(ModuleId.COMMAND_MENU));
    }

    @Test
    void leavesCurrentV4RootUntouched() throws IOException {
        Path root = temporaryDirectory.resolve("omnitools");
        String source = """
                {
                  "format_version": 4,
                  "global": {
                    "debug": true,
                    "timezone": "Asia/Shanghai",
                    "language": "zh_cn",
                    "data_retention": "full",
                    "reward_security": { "allow_command_rewards": false, "max_command_length": 1024 },
                    "command_security": {
                      "allowed_roots": ["warp"],
                      "max_command_length": 1024,
                      "cooldown_ticks": 10
                    }
                  },
                  "integrations": { "placeholder_api": { "enabled": false } },
                  "modules": { }
                }
                """;
        writeRoot(root, source);

        ConfigMigration.migrate(root, temporaryDirectory.resolve("legacy-config"));

        assertEquals(source.trim(), Files.readString(root.resolve("config.json"), StandardCharsets.UTF_8).trim());
        assertFalse(hasBackup(root));
    }

    @Test
    void migrationBackupRestoresTheExactPreMigrationRoot() throws IOException {
        Path root = temporaryDirectory.resolve("omnitools");
        String source = """
                {
                  "format_version": 1,
                  "global": { "timezone": "UTC" },
                  "modules": { "daily_checkin": { "enabled": true } }
                }
                """;
        writeRoot(root, source);

        ConfigMigration.migrate(root, temporaryDirectory.resolve("legacy-config"));

        Path backup;
        try (Stream<Path> files = Files.list(root)) {
            backup = files.filter(path -> path.getFileName().toString().startsWith("config.json.v1.bak-"))
                    .findFirst().orElseThrow();
        }
        Path restored = temporaryDirectory.resolve("restored").resolve("config.json");
        Files.createDirectories(restored.getParent());
        Files.copy(backup, restored);

        assertEquals(source.trim(), Files.readString(restored, StandardCharsets.UTF_8).trim());
    }

    @Test
    void rejectsMalformedModuleEntriesInsteadOfSilentlyEnablingThem() throws IOException {
        Path root = temporaryDirectory.resolve("omnitools");
        writeRoot(root, """
                {
                  "format_version": 4,
                  "global": {},
                  "integrations": {},
                  "modules": { "shop": true }
                }
                """);
        assertThrows(RuntimeException.class, () -> OmniToolsRootConfig.load(root.resolve("config.json")));
    }

    @Test
    void missingLeaderboardFlagDefaultsToDisabled() throws IOException {
        Path root = temporaryDirectory.resolve("omnitools");
        writeRoot(root, """
                {
                  "format_version": 4,
                  "global": {},
                  "integrations": {},
                  "modules": { "daily_checkin": { "enabled": true } }
                }
                """);

        OmniToolsRootConfig loaded = OmniToolsRootConfig.load(root.resolve("config.json"));

        assertFalse(loaded.enabled(ModuleId.LEADERBOARDS));
        assertTrue(loaded.enabled(ModuleId.DAILY_CHECKIN));
    }

    @Test
    void completedMigrationNeverLetsStaleRootFilesOverrideTheModuleAuthority() throws IOException {
        Path root = temporaryDirectory.resolve("omnitools");
        Path legacy = temporaryDirectory.resolve("legacy-config");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("omnitools-shop.json"), "[{\"id\":\"legacy\"}]", StandardCharsets.UTF_8);

        ConfigMigration.migrate(root, legacy);
        Path authority = root.resolve("shop").resolve("config.json");
        assertTrue(Files.exists(root.resolve("legacy").resolve("migration-state.json")));
        Files.writeString(authority, "{\"format_version\":1,\"products\":[{\"id\":\"authoritative\"}]}",
                StandardCharsets.UTF_8);
        Files.writeString(legacy.resolve("omnitools-shop.json"), "[{\"id\":\"stale\"}]", StandardCharsets.UTF_8);

        ConfigMigration.migrate(root, legacy);

        assertTrue(Files.readString(authority, StandardCharsets.UTF_8).contains("authoritative"));
        assertFalse(Files.readString(authority, StandardCharsets.UTF_8).contains("stale"));
    }

    private static void writeRoot(Path root, String json) throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve("config.json"), json, StandardCharsets.UTF_8);
    }

    private static boolean hasBackup(Path root) throws IOException {
        try (Stream<Path> files = Files.list(root)) {
            return files.anyMatch(path -> path.getFileName().toString().contains(".bak-"));
        }
    }
}
