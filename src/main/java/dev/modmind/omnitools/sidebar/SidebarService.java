package dev.modmind.omnitools.sidebar;

import dev.modmind.omnitools.ModMindEntry;
import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.leaderboard.LeaderboardService;
import dev.modmind.omnitools.text.TextTemplateRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Sends a private scoreboard objective to each player without touching the server scoreboard. */
public final class SidebarService {
    private static final NumberFormat HIDDEN_NUMBER_FORMAT = BlankFormat.INSTANCE;
    private static final int MAX_RENDERED_LINE_LENGTH = 40;
    private final Map<UUID, PlayerState> states = new HashMap<>();

    public void onJoin(ServerPlayer player) {
        PlayerState state = states.computeIfAbsent(player.getUUID(), this::newState);
        // A respawn can retain the UUID while the client has a fresh connection state.
        // Remove the previous virtual objective before sending a complete snapshot.
        clear(player);
        boolean visible = SidebarPreferenceData.get(player).visible(player.getUUID(),
                ModMindEntry.sidebarConfig().defaultVisible());
        state.visible = visible;
        if (visible && ModMindEntry.isModuleEnabled(ModuleId.SIDEBAR)) {
            refresh(player, true);
        } else {
            clear(player);
        }
    }

    public void onDisconnect(ServerPlayer player) {
        clear(player);
        states.remove(player.getUUID());
    }

    public void tick(MinecraftServer server) {
        if (!ModMindEntry.isModuleEnabled(ModuleId.SIDEBAR)) {
            return;
        }
        SidebarConfig config = ModMindEntry.sidebarConfig();
        long tick = server.getTickCount();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayerState state = states.computeIfAbsent(player.getUUID(), this::newState);
            if (!state.visible) {
                continue;
            }
            String dimensionId = player.level().dimension().identifier().toString();
            boolean dimensionChanged = !dimensionId.equals(state.dimensionId);
            boolean configChanged = state.configRevision != ModMindEntry.configSnapshot().revision();
            SidebarConfig.Page activePage = config.activePage(tick, this::pageAvailable);
            boolean pageChanged = activePage == null ? !state.pageId.isEmpty() : !activePage.id().equals(state.pageId);
            if (dimensionChanged || configChanged || pageChanged || tick - state.lastRefreshTick >= config.refreshIntervalTicks()) {
                refresh(player, dimensionChanged || configChanged || pageChanged);
            }
        }
    }

    public void setVisible(ServerPlayer player, boolean visible) {
        SidebarPreferenceData.get(player).setVisible(player.getUUID(), visible);
        PlayerState state = states.computeIfAbsent(player.getUUID(), this::newState);
        state.visible = visible;
        if (visible && ModMindEntry.isModuleEnabled(ModuleId.SIDEBAR)) {
            refresh(player, true);
        } else {
            clear(player);
        }
    }

    public boolean isVisible(ServerPlayer player) {
        PlayerState state = states.get(player.getUUID());
        if (state != null) {
            return state.visible;
        }
        return SidebarPreferenceData.get(player).visible(player.getUUID(), ModMindEntry.sidebarConfig().defaultVisible());
    }

    /** Returns the current in-memory conflict state without sending packets or changing preferences. */
    public DiagnosticStatus diagnosticStatus() {
        int visiblePlayers = 0;
        int managedPlayers = 0;
        int skippedByConflict = 0;
        for (PlayerState state : states.values()) {
            if (state.visible) {
                visiblePlayers++;
            }
            if (state.objective != null) {
                managedPlayers++;
            }
            if (state.skippedByConflict) {
                skippedByConflict++;
            }
        }
        return new DiagnosticStatus(ModMindEntry.sidebarConfig().conflictPolicy(), visiblePlayers,
                managedPlayers, skippedByConflict);
    }

    public void refreshAll(MinecraftServer server) {
        if (!ModMindEntry.isModuleEnabled(ModuleId.SIDEBAR)) {
            clearAll(server);
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayerState state = states.computeIfAbsent(player.getUUID(), this::newState);
            state.visible = SidebarPreferenceData.get(player).visible(player.getUUID(),
                    ModMindEntry.sidebarConfig().defaultVisible());
            if (state.visible) {
                refresh(player, true);
            } else {
                clear(player);
            }
        }
    }

    public void clearAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            clear(player);
        }
    }

    public void refresh(ServerPlayer player, boolean force) {
        if (!ModMindEntry.isModuleEnabled(ModuleId.SIDEBAR) || !isVisible(player)) {
            clear(player);
            return;
        }
        SidebarConfig config = ModMindEntry.sidebarConfig();
        SidebarConfig.Page page = config.activePage(player.level().getServer().getTickCount(), this::pageAvailable);
        if (page == null) {
            clear(player);
            return;
        }
        PlayerState state = states.computeIfAbsent(player.getUUID(), this::newState);
        Objective external = player.level().getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
        if (external != null && state.objective == null && config.conflictPolicy() == SidebarConfig.ConflictPolicy.SKIP) {
            state.skippedByConflict = true;
            return;
        }
        if (external != null && state.objective != null && config.conflictPolicy() == SidebarConfig.ConflictPolicy.SKIP) {
            releaseToExternal(player, state, external);
            state.skippedByConflict = true;
            return;
        }
        state.skippedByConflict = false;
        if (external != null && state.objective == null && config.conflictPolicy() == SidebarConfig.ConflictPolicy.RESTORE) {
            state.restoreObjective = external;
        }
        List<Component> rendered = renderPage(player, page);

        if (state.objective == null || state.configRevision != ModMindEntry.configSnapshot().revision()
                || !page.id().equals(state.pageId)) {
            if (state.objective != null) {
                removeObjective(player, state);
            }
            state.objective = createObjective(player, page.title());
            state.rendered = List.of();
            player.connection.send(new ClientboundSetObjectivePacket(state.objective, 0));
            player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, state.objective));
        }
        for (int index = 0; index < rendered.size(); index++) {
            Component value = rendered.get(index);
            if (force || index >= state.rendered.size() || !value.equals(state.rendered.get(index))) {
                sendLine(player, state.objective, index, value, rendered.size());
            }
        }
        for (int index = rendered.size(); index < state.rendered.size(); index++) {
            player.connection.send(new ClientboundResetScorePacket(owner(index), state.objective.getName()));
        }
        state.rendered = List.copyOf(rendered);
        state.configRevision = ModMindEntry.configSnapshot().revision();
        state.lastRefreshTick = player.level().getServer().getTickCount();
        state.dimensionId = player.level().dimension().identifier().toString();
        state.pageId = page.id();
    }

    public void clear(ServerPlayer player) {
        PlayerState state = states.get(player.getUUID());
        if (state == null || state.objective == null) {
            return;
        }
        player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, state.restoreObjective));
        for (int index = 0; index < state.rendered.size(); index++) {
            player.connection.send(new ClientboundResetScorePacket(owner(index), state.objective.getName()));
        }
        player.connection.send(new ClientboundSetObjectivePacket(state.objective, 1));
        state.objective = null;
        state.rendered = List.of();
        state.configRevision = Long.MIN_VALUE;
        state.dimensionId = "";
        state.pageId = "";
        state.restoreObjective = null;
        state.skippedByConflict = false;
    }

    private void releaseToExternal(ServerPlayer player, PlayerState state, Objective external) {
        for (int index = 0; index < state.rendered.size(); index++) {
            player.connection.send(new ClientboundResetScorePacket(owner(index), state.objective.getName()));
        }
        player.connection.send(new ClientboundSetObjectivePacket(state.objective, 1));
        player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, external));
        state.objective = null;
        state.rendered = List.of();
        state.configRevision = Long.MIN_VALUE;
        state.dimensionId = "";
        state.pageId = "";
    }

    private Objective createObjective(ServerPlayer player, String title) {
        String uuid = player.getUUID().toString().replace("-", "");
        String name = "ot_sb_" + uuid.substring(0, 10);
        Scoreboard scoreboard = new Scoreboard();
        return scoreboard.addObjective(name, ObjectiveCriteria.DUMMY, renderText(player, title),
                ObjectiveCriteria.RenderType.INTEGER, false, null);
    }

    private void removeObjective(ServerPlayer player, PlayerState state) {
        player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, null));
        for (int index = 0; index < state.rendered.size(); index++) {
            player.connection.send(new ClientboundResetScorePacket(owner(index), state.objective.getName()));
        }
        player.connection.send(new ClientboundSetObjectivePacket(state.objective, 1));
    }

    private void sendLine(ServerPlayer player, Objective objective, int index, Component value, int lineCount) {
        int score = lineCount - index;
        player.connection.send(new ClientboundSetScorePacket(owner(index), objective.getName(), score,
                Optional.of(value), Optional.of(HIDDEN_NUMBER_FORMAT)));
    }

    private Component renderLine(ServerPlayer player, String text) {
        return renderText(player, text);
    }

    private boolean pageAvailable(SidebarConfig.Page page) {
        return page.type() != SidebarConfig.PageType.LEADERBOARD
                || (ModMindEntry.isModuleEnabled(ModuleId.LEADERBOARDS)
                && ModMindEntry.leaderboardService().hasBoard(page.leaderboardId()));
    }

    private List<Component> renderPage(ServerPlayer player, SidebarConfig.Page page) {
        if (page.type() == SidebarConfig.PageType.TEXT) {
            List<Component> rendered = new ArrayList<>(page.lines().size());
            for (SidebarLine line : page.lines()) {
                rendered.add(renderLine(player, line.text()));
            }
            return rendered;
        }
        LeaderboardService.BoardSnapshot board = ModMindEntry.leaderboardService().board(page.leaderboardId())
                .orElse(null);
        if (board == null || board.entries().isEmpty()) {
            return List.of(Component.literal("-").withStyle(net.minecraft.ChatFormatting.GRAY));
        }
        List<Component> rendered = new ArrayList<>(Math.min(page.maxEntries(), board.entries().size()));
        for (int index = 0; index < board.entries().size() && index < page.maxEntries(); index++) {
            LeaderboardService.RankedEntry entry = board.entries().get(index);
            String line = page.lineFormat().replace("{rank}", Integer.toString(entry.rank()))
                    .replace("{player}", entry.playerName()).replace("{value}", board.format(entry.value()));
            rendered.add(renderLine(player, line));
        }
        return rendered;
    }

    private Component renderText(ServerPlayer player, String text) {
        Component result = TextTemplateRenderer.render(player, text);
        String plain = result.getString();
        if (plain.length() > MAX_RENDERED_LINE_LENGTH) {
            return Component.literal(plain.substring(0, MAX_RENDERED_LINE_LENGTH));
        }
        return result;
    }

    private static String owner(int index) {
        return "line_" + index;
    }

    private PlayerState newState(UUID ignored) {
        return new PlayerState();
    }

    private static final class PlayerState {
        private boolean visible;
        private Objective objective;
        private List<Component> rendered = List.of();
        private long configRevision = Long.MIN_VALUE;
        private long lastRefreshTick = Long.MIN_VALUE;
        private String dimensionId = "";
        private String pageId = "";
        private Objective restoreObjective;
        private boolean skippedByConflict;
    }

    public record DiagnosticStatus(SidebarConfig.ConflictPolicy policy, int visiblePlayers,
                                   int managedPlayers, int skippedByConflict) {
    }
}
