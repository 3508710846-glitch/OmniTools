package dev.modmind.omnitools.sidebar;

import dev.modmind.omnitools.LegacyTitleText;
import dev.modmind.omnitools.ModMindEntry;
import dev.modmind.omnitools.OmniToolsPlaceholderResolver;
import dev.modmind.omnitools.config.ModuleId;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Sends a private scoreboard objective to each player without touching the server scoreboard. */
public final class SidebarService {
    private static final Pattern PLACEHOLDER = Pattern.compile("%([^%]+)%");
    private static final NumberFormat HIDDEN_NUMBER_FORMAT = BlankFormat.INSTANCE;
    private static final int MAX_RENDERED_LINE_LENGTH = 40;
    private final Map<UUID, PlayerState> states = new HashMap<>();
    private final Set<String> warnedPlaceholders = new HashSet<>();

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
            if (dimensionChanged || configChanged || tick - state.lastRefreshTick >= config.refreshIntervalTicks()) {
                refresh(player, dimensionChanged || configChanged);
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
        if (config.lines().isEmpty()) {
            clear(player);
            return;
        }
        PlayerState state = states.computeIfAbsent(player.getUUID(), this::newState);
        List<Component> rendered = new ArrayList<>(config.lines().size());
        for (SidebarLine line : config.lines()) {
            rendered.add(renderLine(player, line.text()));
        }

        if (state.objective == null || state.configRevision != ModMindEntry.configSnapshot().revision()) {
            if (state.objective != null) {
                removeObjective(player, state);
            }
            state.objective = createObjective(player, config.title());
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
    }

    public void clear(ServerPlayer player) {
        PlayerState state = states.get(player.getUUID());
        if (state == null || state.objective == null) {
            return;
        }
        player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, null));
        for (int index = 0; index < state.rendered.size(); index++) {
            player.connection.send(new ClientboundResetScorePacket(owner(index), state.objective.getName()));
        }
        player.connection.send(new ClientboundSetObjectivePacket(state.objective, 1));
        state.objective = null;
        state.rendered = List.of();
        state.configRevision = Long.MIN_VALUE;
        state.dimensionId = "";
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

    private Component renderText(ServerPlayer player, String text) {
        String source = text == null ? "" : text.replace('&', '\u00a7').replace('\n', ' ');
        Matcher matcher = PLACEHOLDER.matcher(source);
        MutableComponent result = Component.empty();
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() > cursor) {
                result.append(LegacyTitleText.parse(source.substring(cursor, matcher.start())));
            }
            String token = matcher.group(1).trim();
            String id = token.toLowerCase(java.util.Locale.ROOT);
            if (id.startsWith("omnitools:")) {
                id = id.substring("omnitools:".length());
            }
            if (OmniToolsPlaceholderResolver.IDS.contains(id)) {
                result.append(OmniToolsPlaceholderResolver.resolve(player, id));
            } else {
                Component external = dev.modmind.omnitools.PlaceholderBootstrap.resolveExternal(player, token);
                if (external != null) {
                    result.append(external);
                } else {
                    if (warnedPlaceholders.add(token)) {
                        System.err.println("[omnitools] Unknown sidebar placeholder: " + token);
                    }
                    result.append(Component.literal("-"));
                }
            }
            cursor = matcher.end();
        }
        if (cursor < source.length()) {
            result.append(LegacyTitleText.parse(source.substring(cursor)));
        }
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
    }
}
