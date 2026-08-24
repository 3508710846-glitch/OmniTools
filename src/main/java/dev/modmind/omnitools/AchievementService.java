package dev.modmind.omnitools;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.achievement.ConditionProgress;
import dev.modmind.omnitools.achievement.AchievementCondition;
import dev.modmind.omnitools.achievement.AllCondition;
import dev.modmind.omnitools.achievement.AnyCondition;
import dev.modmind.omnitools.achievement.NotCondition;
import dev.modmind.omnitools.achievement.StatCondition;
import dev.modmind.omnitools.achievement.SumCondition;
import dev.modmind.omnitools.achievement.StatisticEvaluationContext;

/** Server-side achievement progression, unlock checks, and one-time reward claims. */
public final class AchievementService {
    public static final int CHECK_INTERVAL_TICKS = 10;

    private AchievementConfig config;
    private int revision;
    /**
     * Progress is expensive only when an achievement menu is open. Keep one
     * server-authoritative snapshot per open menu and refresh it from the
     * normal periodic achievement check instead of evaluating statistics twice.
     */
    private final Map<UUID, MenuSnapshot> openMenuSnapshots = new HashMap<>();

    private AchievementService(AchievementConfig config) {
        this.config = config;
    }

    public static AchievementService load() {
        return new AchievementService(ModMindEntry.configSnapshot().achievements());
    }

    public static AchievementService empty() {
        return new AchievementService(AchievementConfig.empty());
    }

    public static AchievementService from(AchievementConfig config) {
        return new AchievementService(config);
    }

    public synchronized void replace(AchievementConfig config) {
        this.config = config;
        revision++;
        openMenuSnapshots.clear();
    }

    public synchronized AchievementConfig config() {
        return config;
    }

    public synchronized int revision() {
        return revision;
    }

    public void reload(MinecraftServer server) {
        synchronized (this) {
            config = ModMindEntry.configSnapshot().achievements();
            revision++;
            openMenuSnapshots.clear();
        }
        checkAll(server);
    }

    public void checkAll(MinecraftServer server) {
        if (config().achievements().isEmpty()) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            check(player);
        }
    }

    /** Checks all configured achievements for a player and permanently records newly met targets. */
    public int check(ServerPlayer player) {
        return checkInternal(player, true);
    }

    /** Checks achievements using a caller-owned per-refresh statistic cache. */
    public int check(ServerPlayer player, StatisticEvaluationContext context) {
        return checkInternal(player, true, context);
    }

    /** Builds one server-authoritative menu snapshot from one shared statistic context. */
    public MenuSnapshot menuSnapshot(ServerPlayer player) {
        int currentRevision = revision();
        MenuSnapshot cached = openMenuSnapshots.get(player.getUUID());
        if (cached != null && cached.revision() == currentRevision) {
            return cached;
        }
        checkInternal(player, true, new StatisticEvaluationContext(player), true);
        return openMenuSnapshots.getOrDefault(player.getUUID(), new MenuSnapshot(Map.of(), currentRevision));
    }

    /** Drops cached menu-only progress as soon as a player leaves the achievement screen. */
    public void forgetMenuSnapshot(ServerPlayer player) {
        openMenuSnapshots.remove(player.getUUID());
    }

    private static ConditionProgress completedProgress(AchievementCondition condition) {
        if (condition instanceof StatCondition stat) {
            if (stat.match() == dev.modmind.omnitools.achievement.TargetMatch.SUM) {
                return ConditionProgress.leaf(stat.atLeast(), stat.atLeast(), true);
            }
            List<ConditionProgress> children = stat.requirements().stream()
                    .map(ignored -> ConditionProgress.leaf(stat.atLeast(), stat.atLeast(), true)).toList();
            return ConditionProgress.group(true, children.size(), children.size(), children);
        }
        if (condition instanceof SumCondition sum) {
            return ConditionProgress.leaf(sum.atLeast(), sum.atLeast(), true);
        }
        if (condition instanceof AllCondition all) {
            List<ConditionProgress> children = all.children().stream().map(AchievementService::completedProgress).toList();
            return ConditionProgress.group(true, children.size(), children.size(), children);
        }
        if (condition instanceof AnyCondition any) {
            List<ConditionProgress> children = any.children().stream().map(AchievementService::completedProgress).toList();
            return ConditionProgress.group(true, 1L, 1L, children);
        }
        if (condition instanceof NotCondition not) {
            return ConditionProgress.group(true, 1L, 1L, List.of(completedProgress(not.child())));
        }
        return ConditionProgress.leaf(1L, 1L, true);
    }

    public Evaluation evaluation(ServerPlayer player, AchievementConfig.AchievementDefinition achievement,
                                 ConditionProgress progress) {
        AchievementData data = AchievementData.get(player);
        State state;
        if (data.isClaimed(player.getUUID(), achievement.id())) {
            state = State.CLAIMED;
        } else if (data.isUnlocked(player.getUUID(), achievement.id()) || progress.completed()) {
            state = State.CLAIMABLE;
        } else {
            state = State.IN_PROGRESS;
        }
        return new Evaluation(state, progress);
    }

    private int checkInternal(ServerPlayer player, boolean announce) {
        return checkInternal(player, announce, new StatisticEvaluationContext(player),
                openMenuSnapshots.containsKey(player.getUUID()));
    }

    private int checkInternal(ServerPlayer player, boolean announce, StatisticEvaluationContext context) {
        return checkInternal(player, announce, context, openMenuSnapshots.containsKey(player.getUUID()));
    }

    private int checkInternal(ServerPlayer player, boolean announce, StatisticEvaluationContext context,
                              boolean captureMenuProgress) {
        AchievementConfig snapshot = config();
        int currentRevision = revision();
        AchievementData data = AchievementData.get(player);
        UUID playerId = player.getUUID();
        Map<String, Evaluation> evaluations = captureMenuProgress ? new LinkedHashMap<>() : null;
        int newlyUnlocked = 0;
        for (AchievementConfig.AchievementDefinition achievement : snapshot.achievements()) {
            boolean unlocked = data.isUnlocked(playerId, achievement.id());
            ConditionProgress progress = unlocked
                    ? completedProgress(achievement.condition()) : achievement.progress(context);
            if (!unlocked && progress.completed() && data.unlock(playerId, achievement.id())) {
                unlocked = true;
                newlyUnlocked++;
                if (announce) {
                    player.displayClientMessage(Component.translatable(
                            "message.omnitools.achievement.unlocked", achievement.display()), true);
                }
            }
            if (evaluations != null) {
                State state = data.isClaimed(playerId, achievement.id())
                        ? State.CLAIMED : (unlocked || progress.completed() ? State.CLAIMABLE : State.IN_PROGRESS);
                evaluations.put(achievement.id(), new Evaluation(state, progress));
            }
        }
        if (evaluations != null) {
            openMenuSnapshots.put(playerId, new MenuSnapshot(Map.copyOf(evaluations), currentRevision));
        }
        return newlyUnlocked;
    }

    public ClaimResult claim(ServerPlayer player, String achievementId) {
        Optional<AchievementConfig.AchievementDefinition> optional = config().definition(achievementId);
        if (optional.isEmpty()) {
            return new ClaimResult(ClaimStatus.UNKNOWN_ACHIEVEMENT, 0L, 0);
        }

        AchievementConfig.AchievementDefinition achievement = optional.get();
        AchievementData data = AchievementData.get(player);
        if (data.isClaimed(player.getUUID(), achievement.id())) {
            return new ClaimResult(ClaimStatus.ALREADY_CLAIMED,
                    CheckinData.get(player).getBalance(player.getUUID()), 0);
        }
        // A click can arrive before the next periodic check, so validate live statistics unless
        // the achievement was already unlocked and therefore remains permanently complete.
        StatisticEvaluationContext context = new StatisticEvaluationContext(player);
        if (!data.isUnlocked(player.getUUID(), achievement.id()) && !achievement.complete(context)) {
            return new ClaimResult(ClaimStatus.NOT_COMPLETED,
                    CheckinData.get(player).getBalance(player.getUUID()), 0);
        }
        data.unlock(player.getUUID(), achievement.id());

        AchievementConfig.Reward reward = achievement.rewards();
        long balance = reward.coins() > 0L
                ? CheckinData.get(player).addCurrency(player.getUUID(), reward.coins(),
                player.getGameProfile().name())
                : CheckinData.get(player).getBalance(player.getUUID());
        int grantedTitles = 0;
        if (ModMindEntry.isModuleEnabled(ModuleId.TITLES)) {
            for (String titleId : reward.titles()) {
                TitleConfig.GrantResult result = ModMindEntry.titleConfig().grant(
                        player.getUUID(), player.getGameProfile().name(), titleId);
                if (result == TitleConfig.GrantResult.GRANTED) {
                    grantedTitles++;
                }
            }
        }
        data.markClaimed(player.getUUID(), achievement.id());
        updateCachedClaimState(player.getUUID(), achievement.id());
        return new ClaimResult(ClaimStatus.CLAIMED, balance, grantedTitles);
    }

    private void updateCachedClaimState(UUID playerId, String achievementId) {
        MenuSnapshot cached = openMenuSnapshots.get(playerId);
        if (cached == null || cached.revision() != revision()) {
            return;
        }
        Evaluation previous = cached.evaluation(achievementId);
        if (previous == null) {
            return;
        }
        Map<String, Evaluation> updated = new LinkedHashMap<>(cached.evaluations());
        updated.put(achievementId, new Evaluation(State.CLAIMED, previous.progress()));
        openMenuSnapshots.put(playerId, new MenuSnapshot(Map.copyOf(updated), cached.revision()));
    }

    public State state(ServerPlayer player, AchievementConfig.AchievementDefinition achievement) {
        return state(player, achievement, new StatisticEvaluationContext(player));
    }

    /** Returns the server-authoritative progress tree for one achievement. */
    public ConditionProgress progress(ServerPlayer player, AchievementConfig.AchievementDefinition achievement) {
        return achievement.progress(new StatisticEvaluationContext(player));
    }

    public State state(ServerPlayer player, AchievementConfig.AchievementDefinition achievement,
                       StatisticEvaluationContext context) {
        return evaluation(player, achievement, achievement.progress(context)).state();
    }

    public int currentValue(ServerPlayer player, AchievementConfig.Requirement requirement) {
        return Math.toIntExact(Math.min(Integer.MAX_VALUE, requirement.current(player)));
    }

    public int unlockedCount(ServerPlayer player) {
        AchievementData data = AchievementData.get(player);
        int count = 0;
        for (AchievementConfig.AchievementDefinition achievement : config().achievements()) {
            if (data.isUnlocked(player.getUUID(), achievement.id())) {
                count++;
            }
        }
        return count;
    }

    public int claimedCount(ServerPlayer player) {
        AchievementData data = AchievementData.get(player);
        int count = 0;
        for (AchievementConfig.AchievementDefinition achievement : config().achievements()) {
            if (data.isClaimed(player.getUUID(), achievement.id())) {
                count++;
            }
        }
        return count;
    }

    public enum State {
        IN_PROGRESS,
        CLAIMABLE,
        CLAIMED
    }

    public enum ClaimStatus {
        CLAIMED,
        ALREADY_CLAIMED,
        NOT_COMPLETED,
        UNKNOWN_ACHIEVEMENT
    }

    public record ClaimResult(ClaimStatus status, long balance, int grantedTitles) {
    }

    public record Evaluation(State state, ConditionProgress progress) {
    }

    public record MenuSnapshot(Map<String, Evaluation> evaluations, int revision) {
        public MenuSnapshot {
            evaluations = Map.copyOf(evaluations);
        }

        public Evaluation evaluation(String achievementId) {
            return evaluations.get(achievementId);
        }
    }
}
