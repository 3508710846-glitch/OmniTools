package dev.modmind.omnitools;

import dev.modmind.omnitools.config.ModuleId;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.PlayerTeam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps chat, tab-list, and vanilla scoreboard-team title display synchronized. */
public final class TitleDisplayService {
    private static final Logger LOGGER = LoggerFactory.getLogger("omnitools");
    private static final String LEGACY_NAME_TAG_MARKER = "omnitools:title";
    private static final String TEAM_PREFIX = "ot_t_";
    private static final Set<UUID> EXTERNAL_TEAM_WARNINGS = ConcurrentHashMap.newKeySet();
    private static final java.util.Map<UUID, ExternalTeamAssignment> REPLACED_EXTERNAL_TEAMS =
            new ConcurrentHashMap<>();

    private TitleDisplayService() {
    }

    public static Component tabListDisplayName(ServerPlayer player) {
        if (!ModMindEntry.isModuleEnabled(ModuleId.TITLES)) {
            return null;
        }
        Optional<TitleConfig.TitleDefinition> selected = ModMindEntry.titleConfig().selectedTitle(player.getUUID());
        if (selected.isEmpty() || !selected.get().rarity().appearsInTabList()) {
            return null;
        }

        Component playerName = Component.literal(player.getGameProfile().name());
        PlayerTeam team = player.getTeam();
        if (team != null && !isManagedTeam(team)) {
            playerName = PlayerTeam.formatNameForTeam(team, playerName);
        }
        return playerName.copy().append(Component.literal(" ")).append(selected.get().displayComponent());
    }

    public static Component chatName(ServerPlayer player, TitleConfig.TitleDefinition title) {
        return Component.empty()
                .append(title.displayComponent())
                .append(Component.literal(player.getGameProfile().name()).withStyle(ChatFormatting.RESET));
    }

    public static void refreshPlayer(ServerPlayer player) {
        clearLegacyNameTag(player);
        TitleConfig config = ModMindEntry.titleConfig();
        Optional<TitleConfig.TitleDefinition> selected = ModMindEntry.isModuleEnabled(ModuleId.TITLES)
                ? config.selectedTitle(player.getUUID()) : Optional.empty();
        updateNameplate(player, config, selected.orElse(null));
        player.level().getServer().getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, player));
    }

    public static void refreshAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            refreshPlayer(player);
        }
    }

    /** Restores an external team before the player leaves; joining reapplies the selected title if needed. */
    public static void onDisconnect(ServerPlayer player) {
        clearManagedNameplate(player);
    }

    /** Removes all OmniTools teams, including stale entries saved by earlier server sessions. */
    public static void clearAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            clearLegacyNameTag(player);
            clearManagedNameplate(player);
            server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(
                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, player));
        }
        ServerScoreboard scoreboard = server.getScoreboard();
        for (ExternalTeamAssignment assignment : List.copyOf(REPLACED_EXTERNAL_TEAMS.values())) {
            restoreExternalTeam(scoreboard, assignment);
        }
        for (PlayerTeam team : List.copyOf(scoreboard.getPlayerTeams())) {
            if (isManagedTeam(team)) {
                scoreboard.removePlayerTeam(team);
            }
        }
        EXTERNAL_TEAM_WARNINGS.clear();
        REPLACED_EXTERNAL_TEAMS.clear();
    }

    private static void updateNameplate(ServerPlayer player, TitleConfig config, TitleConfig.TitleDefinition title) {
        if (title == null || !title.rarity().appearsAboveHead()
                || config.nameplateMode() != TitleConfig.NameplateMode.SCOREBOARD_TEAM) {
            clearManagedNameplate(player);
            return;
        }

        ServerScoreboard scoreboard = player.level().getServer().getScoreboard();
        String holder = player.getScoreboardName();
        PlayerTeam current = scoreboard.getPlayersTeam(holder);
        if (current != null && !isManagedTeam(current)) {
            if (config.teamConflictPolicy() == TitleConfig.TeamConflictPolicy.PRESERVE_EXTERNAL_TEAM) {
                warnExternalTeam(player, current, "preserve_external_team");
                return;
            }
            warnExternalTeam(player, current, "omnitools_priority");
            REPLACED_EXTERNAL_TEAMS.putIfAbsent(player.getUUID(),
                    new ExternalTeamAssignment(holder, current.getName()));
            scoreboard.removePlayerFromTeam(holder, current);
        } else if (current != null) {
            scoreboard.removePlayerFromTeam(holder, current);
            removeIfEmpty(scoreboard, current);
        }

        String teamName = findAvailableTeamName(scoreboard, player.getUUID(), holder);
        if (teamName == null) {
            LOGGER.error("Could not allocate an OmniTools nameplate team for {}", player.getGameProfile().name());
            return;
        }
        PlayerTeam team = scoreboard.getPlayerTeam(teamName);
        if (team == null) {
            team = scoreboard.addPlayerTeam(teamName);
        }
        team.setPlayerPrefix(title.displayComponent());
        team.setPlayerSuffix(Component.empty());
        scoreboard.onTeamChanged(team);
        scoreboard.addPlayerToTeam(holder, team);
    }

    private static void clearManagedNameplate(ServerPlayer player) {
        ServerScoreboard scoreboard = player.level().getServer().getScoreboard();
        PlayerTeam team = scoreboard.getPlayersTeam(player.getScoreboardName());
        if (team == null || !isManagedTeam(team)) {
            return;
        }
        scoreboard.removePlayerFromTeam(player.getScoreboardName(), team);
        removeIfEmpty(scoreboard, team);
        restoreExternalTeam(player, scoreboard);
    }

    private static void removeIfEmpty(ServerScoreboard scoreboard, PlayerTeam team) {
        if (team.getPlayers().isEmpty() && isManagedTeam(team)) {
            scoreboard.removePlayerTeam(team);
        }
    }

    private static void restoreExternalTeam(ServerPlayer player, ServerScoreboard scoreboard) {
        ExternalTeamAssignment assignment = REPLACED_EXTERNAL_TEAMS.remove(player.getUUID());
        if (assignment == null) {
            return;
        }
        restoreExternalTeam(scoreboard, assignment);
    }

    private static void restoreExternalTeam(ServerScoreboard scoreboard, ExternalTeamAssignment assignment) {
        PlayerTeam previousTeam = scoreboard.getPlayerTeam(assignment.teamName());
        if (previousTeam == null || isManagedTeam(previousTeam)
                || scoreboard.getPlayersTeam(assignment.holder()) != null) {
            return;
        }
        scoreboard.addPlayerToTeam(assignment.holder(), previousTeam);
    }

    private static String findAvailableTeamName(ServerScoreboard scoreboard, UUID playerId, String holder) {
        String uuid = playerId.toString().replace("-", "");
        String direct = TEAM_PREFIX + uuid.substring(0, 11);
        if (canUseTeam(scoreboard.getPlayerTeam(direct), holder)) {
            return direct;
        }
        for (int suffix = 1; suffix <= 0xFFF; suffix++) {
            String candidate = TEAM_PREFIX + uuid.substring(0, 8)
                    + String.format(Locale.ROOT, "%03x", suffix);
            if (canUseTeam(scoreboard.getPlayerTeam(candidate), holder)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean canUseTeam(PlayerTeam team, String holder) {
        return team == null || (isManagedTeam(team)
                && (team.getPlayers().isEmpty() || team.getPlayers().contains(holder)));
    }

    private static boolean isManagedTeam(PlayerTeam team) {
        return team.getName().startsWith(TEAM_PREFIX);
    }

    private static void clearLegacyNameTag(Player player) {
        Component customName = player.getCustomName();
        if (customName != null && LEGACY_NAME_TAG_MARKER.equals(customName.getStyle().getInsertion())) {
            player.setCustomName(null);
            player.setCustomNameVisible(false);
        }
    }

    private static void warnExternalTeam(ServerPlayer player, PlayerTeam team, String policy) {
        if (EXTERNAL_TEAM_WARNINGS.add(player.getUUID())) {
            LOGGER.warn("Player {} is already in external scoreboard team {}; applying title policy {}",
                    player.getGameProfile().name(), team.getName(), policy);
        }
    }

    private record ExternalTeamAssignment(String holder, String teamName) {
    }
}
