package dev.modmind.omnitools.leaderboard;

import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.diagnostics.OperationalErrorReporter;
import dev.modmind.omnitools.statistics.StatisticQuery;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds bounded, in-memory leaderboard snapshots. Disk reads happen only in the scheduled scan;
 * GUI, chat, placeholders and sidebars consume {@link BoardSnapshot} without scanning statistics.
 */
public final class LeaderboardService {
    private LeaderboardConfig config = LeaderboardConfig.empty();
    private Map<String, BoardSnapshot> snapshots = Map.of();
    private final Map<UUID, String> knownNames = new HashMap<>();
    private List<Path> scanFiles = List.of();
    private int scanIndex;
    private Map<UUID, PlayerScores> stagedScores = Map.of();
    private boolean scanActive;
    private long lastRefreshTick = Long.MIN_VALUE;

    public void replace(LeaderboardConfig next) {
        config = next == null ? LeaderboardConfig.empty() : next;
        snapshots = Map.of();
        scanFiles = List.of();
        stagedScores = Map.of();
        scanIndex = 0;
        scanActive = false;
        lastRefreshTick = Long.MIN_VALUE;
    }

    public LeaderboardConfig config() {
        return config;
    }

    public void onJoin(ServerPlayer player) {
        knownNames.put(player.getUUID(), player.getGameProfile().name());
    }

    public void tick(MinecraftServer server) {
        if (config.leaderboards().isEmpty()) {
            snapshots = Map.of();
            return;
        }
        long tick = server.getTickCount();
        if (!scanActive && (lastRefreshTick == Long.MIN_VALUE
                || tick - lastRefreshTick >= config.refreshIntervalTicks())) {
            beginRefresh(server, tick);
        }
        if (scanActive) {
            scanFiles(server);
        }
    }

    public List<BoardSnapshot> boards() {
        return config.leaderboards().stream().map(board -> snapshots.getOrDefault(board.id(), BoardSnapshot.empty(board)))
                .toList();
    }

    public Optional<BoardSnapshot> board(String id) {
        return config.definition(id).map(definition -> snapshots.getOrDefault(definition.id(), BoardSnapshot.empty(definition)));
    }

    public boolean hasBoard(String id) {
        return config.definition(id).isPresent();
    }

    private void beginRefresh(MinecraftServer server, long tick) {
        Map<UUID, PlayerScores> staged = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            knownNames.put(player.getUUID(), player.getGameProfile().name());
            staged.put(player.getUUID(), scores(player.getUUID(), player.getGameProfile().name(), player.getStats()));
        }
        stagedScores = staged;
        lastRefreshTick = tick;
        if (!config.includeOfflinePlayers()) {
            finishRefresh(tick);
            return;
        }
        Path statistics = server.getWorldPath(LevelResource.PLAYER_STATS_DIR);
        try (var paths = Files.list(statistics)) {
            scanFiles = paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        } catch (IOException exception) {
            OperationalErrorReporter.global().warn(OperationalErrorReporter.Context
                    .forModule(ModuleId.LEADERBOARDS, "enumerate_offline_statistics")
                    .withParameters(Map.of("path", statistics.toString()))
                    .withRecoveryAction("offline_scan_skipped"), exception);
            scanFiles = List.of();
        }
        scanIndex = 0;
        scanActive = true;
        if (scanFiles.isEmpty()) {
            finishRefresh(tick);
        }
    }

    private void scanFiles(MinecraftServer server) {
        int processed = 0;
        while (scanIndex < scanFiles.size() && processed++ < config.maxFilesPerTick()) {
            Path file = scanFiles.get(scanIndex++);
            UUID playerId = fileUuid(file);
            if (playerId == null || stagedScores.containsKey(playerId)) {
                continue;
            }
            try {
                StatsCounter stats = new ServerStatsCounter(server, file);
                String name = knownNames.getOrDefault(playerId, shortUuid(playerId));
                stagedScores.put(playerId, scores(playerId, name, stats));
            } catch (RuntimeException exception) {
                OperationalErrorReporter.global().warn(OperationalErrorReporter.Context
                        .forModule(ModuleId.LEADERBOARDS, "read_offline_statistics")
                        .withParameters(Map.of("file", file.getFileName().toString()))
                        .withRecoveryAction("file_skipped"), exception);
            }
        }
        if (scanIndex >= scanFiles.size()) {
            finishRefresh(server.getTickCount());
        }
    }

    private void finishRefresh(long tick) {
        Map<String, BoardSnapshot> built = new HashMap<>();
        for (LeaderboardConfig.LeaderboardDefinition definition : config.leaderboards()) {
            List<RankedEntry> entries = stagedScores.values().stream()
                    .map(scores -> new Candidate(scores.playerId(), scores.playerName(),
                            scores.values().getOrDefault(definition.id(), 0L)))
                    .filter(candidate -> !config.excludeZeroScores() || candidate.value() > 0L)
                    .sorted(Comparator.comparingLong(Candidate::value).reversed()
                            .thenComparing(candidate -> candidate.name().toLowerCase(Locale.ROOT))
                            .thenComparing(Candidate::playerId))
                    .map(new DenseRanker())
                    .toList();
            built.put(definition.id(), new BoardSnapshot(definition, entries, tick));
        }
        snapshots = Map.copyOf(built);
        stagedScores = Map.of();
        scanFiles = List.of();
        scanIndex = 0;
        scanActive = false;
    }

    private PlayerScores scores(UUID playerId, String playerName, StatsCounter stats) {
        Map<String, Long> values = new HashMap<>();
        for (LeaderboardConfig.LeaderboardDefinition definition : config.leaderboards()) {
            values.put(definition.id(), definition.stat().value(stats));
        }
        return new PlayerScores(playerId, playerName == null || playerName.isBlank() ? shortUuid(playerId) : playerName,
                Map.copyOf(values));
    }

    private static UUID fileUuid(Path file) {
        String name = file.getFileName().toString();
        try {
            return UUID.fromString(name.substring(0, name.length() - ".json".length()));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String shortUuid(UUID id) {
        return id.toString().substring(0, 8);
    }

    private record PlayerScores(UUID playerId, String playerName, Map<String, Long> values) {
    }

    private record Candidate(UUID playerId, String name, long value) {
    }

    private static final class DenseRanker implements java.util.function.Function<Candidate, RankedEntry> {
        private long previous = Long.MIN_VALUE;
        private int rank;

        @Override
        public RankedEntry apply(Candidate candidate) {
            if (candidate.value() != previous) {
                rank++;
                previous = candidate.value();
            }
            return new RankedEntry(rank, candidate.playerId(), candidate.name(), candidate.value());
        }
    }

    public record RankedEntry(int rank, UUID playerId, String playerName, long value) {
    }

    public record BoardSnapshot(LeaderboardConfig.LeaderboardDefinition definition, List<RankedEntry> entries,
                                long refreshedAtTick) {
        public BoardSnapshot {
            entries = List.copyOf(entries == null ? List.of() : entries);
        }

        public static BoardSnapshot empty(LeaderboardConfig.LeaderboardDefinition definition) {
            return new BoardSnapshot(definition, List.of(), Long.MIN_VALUE);
        }

        public Optional<RankedEntry> entry(UUID playerId) {
            return entries.stream().filter(entry -> entry.playerId().equals(playerId)).findFirst();
        }

        public String format(long value) {
            StatisticQuery query = definition.stat();
            return query.format(value);
        }
    }
}
