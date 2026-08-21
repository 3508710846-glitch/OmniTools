package dev.modmind.qiandao;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.PlayerTeam;

import java.util.Optional;

/** Keeps the three title visibility tiers synchronized with online players. */
public final class TitleDisplayService {
    private static final String NAME_TAG_MARKER = "qiandao:title";

    private TitleDisplayService() {
    }

    public static Component tabListDisplayName(ServerPlayer player) {
        Optional<TitleConfig.TitleDefinition> selected = ModMindEntry.titleConfig().selectedTitle(player.getUUID());
        if (selected.isEmpty() || !selected.get().rarity().appearsInTabList()) {
            return null;
        }

        MutableComponent playerName = PlayerTeam.formatNameForTeam(player.getTeam(),
                Component.literal(player.getGameProfile().name()));
        return playerName.append(Component.literal(" ")).append(selected.get().displayComponent());
    }

    public static Component chatName(ServerPlayer player, TitleConfig.TitleDefinition title) {
        return Component.empty()
                .append(title.displayComponent())
                .append(Component.literal(player.getGameProfile().name()).withStyle(ChatFormatting.RESET));
    }

    public static void refreshPlayer(ServerPlayer player) {
        Optional<TitleConfig.TitleDefinition> selected = ModMindEntry.titleConfig().selectedTitle(player.getUUID());
        updateNameTag(player, selected.orElse(null));
        player.level().getServer().getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, player));
    }

    public static void refreshAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            refreshPlayer(player);
        }
    }

    public static Component unwrapManagedNameTag(Component customName) {
        if (customName == null || !NAME_TAG_MARKER.equals(customName.getStyle().getInsertion())) {
            return null;
        }

        MutableComponent result = Component.empty();
        for (Component sibling : customName.getSiblings()) {
            result.append(sibling.copy());
        }
        return result;
    }

    private static void updateNameTag(ServerPlayer player, TitleConfig.TitleDefinition title) {
        Component currentName = player.getCustomName();
        boolean managedName = unwrapManagedNameTag(currentName) != null;
        if (title != null && title.rarity().appearsAboveHead()) {
            if (currentName == null || managedName) {
                player.setCustomName(managedNameTag(player, title));
                player.setCustomNameVisible(true);
            }
            return;
        }

        if (managedName) {
            player.setCustomName(null);
            player.setCustomNameVisible(false);
        }
    }

    private static Component managedNameTag(Player player, TitleConfig.TitleDefinition title) {
        return Component.empty()
                .withStyle(style -> style.withInsertion(NAME_TAG_MARKER))
                .append(title.displayComponent())
                .append(Component.literal(player.getGameProfile().name()).withStyle(ChatFormatting.RESET));
    }
}
