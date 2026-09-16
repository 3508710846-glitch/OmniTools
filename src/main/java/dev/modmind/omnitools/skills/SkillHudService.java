package dev.modmind.omnitools.skills;

import dev.modmind.omnitools.ServerText;
import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.diagnostics.OperationalErrorReporter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * mcMMO-style skill feedback implemented with vanilla server APIs.  It owns no persistent data
 * and never mutates progression; HUD failures are isolated to the affected player.
 */
public final class SkillHudService {
    private final Map<UUID, PlayerSkillHudState> states = new HashMap<>();
    private volatile SkillTreeConfig.HudSettings settings = SkillTreeConfig.HudSettings.defaults();
    private volatile SkillTreeService skillService;

    public void bind(SkillTreeService service) {
        this.skillService = service;
    }

    public synchronized void replaceSettings(SkillTreeConfig.HudSettings replacement) {
        settings = replacement == null ? SkillTreeConfig.HudSettings.defaults() : replacement;
        if (!settings.enabled()) {
            clearAll();
            return;
        }
        // A reload can disable one presentation channel while leaving the HUD service enabled.
        // Remove already-visible/queued output immediately instead of waiting for the next tick.
        if (!settings.bossbarEnabled()) {
            for (PlayerSkillHudState state : states.values()) clearBossBarState(state);
        }
        if (!settings.actionbarEnabled()) {
            for (PlayerSkillHudState state : states.values()) clearActionbarState(state);
        }
    }

    public synchronized void handleFeedback(ServerPlayer player, SkillFeedbackEvent event) {
        if (player == null || event == null || !player.getUUID().equals(event.playerUuid()) || !settings.enabled()) return;
        try {
            PlayerSkillHudState state = state(player);
            long now = player.level().getServer().getTickCount();
            switch (event.kind()) {
                case XP_GRANTED -> handleXp(player, state, event, now);
                case ACTIVE_ACTIVATED -> handleActive(player, state, event, now);
                case PASSIVE_TRIGGERED -> handlePassive(player, state, event, now);
            }
        } catch (RuntimeException exception) {
            report(player, event, "hud_feedback", exception, "remove_player_hud_state");
            // Do not keep a partially-rendered bar or stale queue after an API failure.  The
            // progression mutation already succeeded and remains authoritative.
            remove(player);
        }
    }

    private void handleXp(ServerPlayer player, PlayerSkillHudState state,
                          SkillFeedbackEvent event, long now) {
        String previousSkill = state.skillId;
        state.skillId = event.skillId();
        state.pendingXp = state.pendingXp > 0L && previousSkill.equals(event.skillId())
                ? saturatedAdd(state.pendingXp, event.xpAmount()) : event.xpAmount();
        state.currentXp = event.currentXp();
        state.nextLevelXp = event.nextLevelXp();
        state.expiresAtTick = now + settings.durationTicks();
        state.lastLevel = event.newLevel();
        if (settings.bossbarEnabled()) {
            if (now >= state.nextUpdateTick) renderXp(player, state, now);
            state.bossBar.setVisible(true);
        } else {
            clearBossBarState(state);
        }
        if (event.newLevel() > event.oldLevel()) {
            if (settings.levelUpTitle()) showLevelUp(player, event);
            enqueue(state, ServerText.translatable("message.omnitools.skills.level_up_actionbar",
                    display(player, event.skillId()), event.oldLevel(), event.newLevel()));
            if (event.newLevel() >= 100 && event.oldLevel() < 100) {
                enqueue(state, ServerText.translatable("message.omnitools.skills.ability_unlocked",
                        display(player, event.skillId())));
            }
            player.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
        }
        // Normal XP is represented by the merged BossBar above.  Do not enqueue one ActionBar
        // message per block/entity event; high-frequency sources must not turn into chat spam.
    }

    private void handleActive(ServerPlayer player, PlayerSkillHudState state,
                              SkillFeedbackEvent event, long now) {
        state.activeSkillId = event.skillId();
        state.activeAbilityLevel = event.oldLevel();
        if (!settings.actionbarEnabled()) {
            clearActionbarState(state);
            return;
        }
        SkillTreeService service = skillService;
        long remaining = service == null ? 0L : service.activeRemainingSeconds(player, event.skillId());
        state.activeUntilTick = remaining > 0L ? now + remaining * 20L : 0L;
        state.nextActiveUpdateTick = now;
    }

    private void handlePassive(ServerPlayer player, PlayerSkillHudState state,
                               SkillFeedbackEvent event, long now) {
        if (!settings.passiveFeedback()) return;
        enqueue(state, ServerText.translatable("message.omnitools.skills.passive_actionbar",
                display(player, event.skillId()), event.detail()));
        state.expiresAtTick = Math.max(state.expiresAtTick, now + settings.durationTicks());
    }

    private void renderXp(ServerPlayer player, PlayerSkillHudState state, long now) {
        long required = state.nextLevelXp;
        float progress = required <= 0L ? 1.0F : Math.max(0.0F,
                Math.min(1.0F, (float) state.currentXp / (float) required));
        Object requirement = required <= 0L
                ? ServerText.translatable("message.omnitools.skills.max_level")
                : state.currentXp + " / " + required;
        Component name = ServerText.translatable("message.omnitools.skills.bossbar",
                icon(state.skillId), display(player, state.skillId), state.lastLevel,
                state.pendingXp, requirement);
        state.bossBar.setName(name);
        state.bossBar.setProgress(progress);
        state.nextUpdateTick = now + settings.updateIntervalTicks();
    }

    private void showLevelUp(ServerPlayer player, SkillFeedbackEvent event) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 35, 10));
        player.connection.send(new ClientboundSetTitleTextPacket(ServerText.translatable(
                "message.omnitools.skills.level_up_title", display(player, event.skillId()))));
        player.connection.send(new ClientboundSetSubtitleTextPacket(ServerText.translatable(
                "message.omnitools.skills.level_up_subtitle", event.oldLevel(), event.newLevel())));
    }

    public synchronized void tick(MinecraftServer server) {
        if (server == null) return;
        if (!settings.enabled()) {
            clearAll();
            return;
        }
        long now = server.getTickCount();
        boolean bossbarEnabled = settings.bossbarEnabled();
        boolean actionbarEnabled = settings.actionbarEnabled();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayerSkillHudState state = states.get(player.getUUID());
            if (state == null) continue;
            try {
                if (!bossbarEnabled) {
                    clearBossBarState(state);
                } else if (!state.skillId.isBlank() && now < state.expiresAtTick) {
                    if (now >= state.nextUpdateTick) renderXp(player, state, now);
                    state.bossBar.setVisible(true);
                } else if (!state.skillId.isBlank()) {
                    clearBossBarState(state);
                }
                if (!actionbarEnabled) {
                    clearActionbarState(state);
                }
                boolean activeRunning = actionbarEnabled && state.activeUntilTick > now
                        && !state.activeSkillId.isBlank();
                if (activeRunning) {
                    SkillTreeService service = skillService;
                    long remaining = service == null ? 0L : service.activeRemainingSeconds(player, state.activeSkillId);
                    if (remaining > 0L) {
                        if (now >= state.nextActiveUpdateTick) {
                            int abilityLevel = state.activeAbilityLevel;
                            if (service != null) {
                                abilityLevel = service.abilitySnapshot(player, state.activeSkillId).abilityLevel();
                            }
                            player.displayClientMessage(ServerText.translatable("message.omnitools.skills.active_actionbar",
                                    display(player, state.activeSkillId), abilityLevel,
                                    formatDuration(remaining)), true);
                            state.nextActiveUpdateTick = now + settings.updateIntervalTicks();
                        }
                    } else {
                        clearActiveState(state);
                        activeRunning = false;
                    }
                }
                // Active ability feedback owns the ActionBar for its duration.  Delaying queued
                // XP/passive notices prevents the two channels from overwriting each other every
                // few ticks and preserves the queued messages for after the ability ends.
                if (actionbarEnabled && !activeRunning && now >= state.nextMessageTick
                        && !state.queuedMessages.isEmpty()) {
                    player.displayClientMessage(state.queuedMessages.removeFirst(), true);
                    state.nextMessageTick = now + settings.updateIntervalTicks();
                }
                checkCooldownReady(player, state);
            } catch (RuntimeException exception) {
                report(player, null, "hud_tick", exception, "remove_player_hud_state");
                remove(player);
            }
        }
    }

    private void checkCooldownReady(ServerPlayer player, PlayerSkillHudState state) {
        SkillTreeService service = skillService;
        String skill = !state.activeSkillId.isBlank() ? state.activeSkillId : state.skillId;
        if (service == null || skill.isBlank()) return;
        long current = service.getAbilityCooldown(player, skill);
        Long prior = state.observedCooldowns.put(skill, current);
        if (prior != null && prior > 0L && current == 0L && settings.actionbarEnabled()) {
            enqueue(state, ServerText.translatable("message.omnitools.skills.ability_ready",
                    display(player, skill)));
        }
    }

    public synchronized void onJoin(ServerPlayer player) {
        if (player != null) remove(player);
    }

    public synchronized void remove(ServerPlayer player) {
        if (player == null) return;
        PlayerSkillHudState state = states.remove(player.getUUID());
        if (state != null) {
            state.bossBar.removePlayer(player);
            state.bossBar.removeAllPlayers();
            state.bossBar.setVisible(false);
        }
    }

    public synchronized void clearAll(MinecraftServer server) {
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) remove(player);
        }
        clearAll();
    }

    public synchronized void clearAll() {
        for (PlayerSkillHudState state : states.values()) {
            state.bossBar.removeAllPlayers();
            state.bossBar.setVisible(false);
        }
        states.clear();
    }

    private PlayerSkillHudState state(ServerPlayer player) {
        return states.computeIfAbsent(player.getUUID(), ignored -> {
            ServerBossEvent bossBar = new ServerBossEvent(Component.empty(), BossEvent.BossBarColor.GREEN,
                    BossEvent.BossBarOverlay.PROGRESS);
            bossBar.addPlayer(player);
            bossBar.setVisible(false);
            return new PlayerSkillHudState(player.getUUID(), bossBar);
        });
    }

    private void enqueue(PlayerSkillHudState state, Component message) {
        if (!settings.actionbarEnabled() || message == null || settings.maxQueuedMessages() == 0) return;
        while (state.queuedMessages.size() >= settings.maxQueuedMessages()) state.queuedMessages.removeFirst();
        state.queuedMessages.addLast(message);
    }

    private static void clearBossBarState(PlayerSkillHudState state) {
        state.bossBar.setVisible(false);
        state.pendingXp = 0L;
        state.currentXp = 0L;
        state.nextLevelXp = 0L;
        state.expiresAtTick = 0L;
        state.nextUpdateTick = 0L;
    }

    private static void clearActionbarState(PlayerSkillHudState state) {
        state.queuedMessages.clear();
        state.nextMessageTick = 0L;
        clearActiveState(state);
        state.activeSkillId = "";
    }

    private static void clearActiveState(PlayerSkillHudState state) {
        state.activeAbilityLevel = 0;
        state.activeUntilTick = 0L;
        state.nextActiveUpdateTick = 0L;
    }

    private String display(ServerPlayer player, String skillId) {
        if (skillService == null) return skillId;
        return skillService.config().tree(skillId).map(SkillTreeConfig.TreeDefinition::display).orElse(skillId);
    }

    private static String icon(String skillId) {
        return switch (SkillTreeServiceNormalized.skill(skillId)) {
            case "mining" -> "⛏";
            case "woodcutting" -> "🪓";
            case "herbalism" -> "✿";
            case "excavation" -> "◆";
            case "swords" -> "⚔";
            case "axes" -> "🪓";
            case "archery" -> "➳";
            case "repair" -> "⚒";
            case "alchemy" -> "⚗";
            default -> "★";
        };
    }

    private static String formatDuration(long seconds) {
        long safe = Math.max(0L, seconds);
        return String.format(java.util.Locale.ROOT, "%02d:%02d", safe / 60L, safe % 60L);
    }

    private void report(ServerPlayer player, SkillFeedbackEvent event, String feature,
                        Throwable exception, String recoveryAction) {
        OperationalErrorReporter.Context context = OperationalErrorReporter.Context
                .forModule(ModuleId.SKILLS, feature).withPlayer(player == null ? null : player.getUUID())
                .withWorld(player == null ? "" : player.level().dimension().toString())
                .withState("HUD").withRecoveryAction(recoveryAction);
        if (event != null) context = context.withParameters(Map.of("skill", event.skillId(),
                "kind", event.kind().name(), "operationId", event.operationId()));
        OperationalErrorReporter.global().warn(context, exception);
    }

    private static long saturatedAdd(long left, long right) {
        return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    /** Keeps icon normalization local without exposing SkillTreeService's private helper. */
    private static final class SkillTreeServiceNormalized {
        static String skill(String value) {
            return LegacySkillAdapter.canonical(value);
        }
    }
}
