package dev.modmind.omnitools.skills;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Mutable, transient HUD state for one online player. */
final class PlayerSkillHudState {
    final UUID playerId;
    final ServerBossEvent bossBar;
    final ArrayDeque<Component> queuedMessages = new ArrayDeque<>();
    final Map<String, Long> observedCooldowns = new HashMap<>();
    String skillId = "";
    long pendingXp;
    long currentXp;
    long nextLevelXp;
    long expiresAtTick;
    long nextUpdateTick;
    long nextMessageTick;
    String activeSkillId = "";
    int activeAbilityLevel;
    long activeUntilTick;
    long nextActiveUpdateTick;
    long lastLevel;

    PlayerSkillHudState(UUID playerId, ServerBossEvent bossBar) {
        this.playerId = playerId;
        this.bossBar = bossBar;
    }
}
