package dev.modmind.omnitools.skills;

import dev.modmind.omnitools.ModMindEntry;
import dev.modmind.omnitools.CheckinData;
import dev.modmind.omnitools.TitleConfig;
import dev.modmind.omnitools.TitleEffectConfig;
import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.diagnostics.AsyncAuditLogWriter;
import dev.modmind.omnitools.diagnostics.OperationalErrorReporter;
import net.minecraft.core.Holder;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Collections;
import java.util.ArrayDeque;
import java.nio.file.Path;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Server-authoritative skill progression and native-attribute synchronization. */
public final class SkillTreeService {
    private volatile SkillTreeConfig config;
    private volatile McmmoSkillModule mcmmoModule;
    private volatile long revision;
    private final Map<RateLimitKey, Long> latestSourceTick = new HashMap<>();
    private final Map<UUID, LastPosition> lastPositions = new HashMap<>();
    private final Map<UUID, Long> craftOperationSequences = new HashMap<>();
    private final Set<UUID> lumberChainsInProgress = new HashSet<>();
    private final Set<String> settledOperations = new java.util.LinkedHashSet<>();
    private final Map<String, Long> passiveTriggerCounts = new HashMap<>();
    private final SkillLedger transientLedger = new SkillLedger();
    private final SkillXpDiagnostics xpDiagnostics = new SkillXpDiagnostics();
    private boolean xpTransactionFlushPending;
    private long lastXpTransactionFlushTick = -20L;
    private volatile BiConsumer<ServerPlayer, SkillFeedbackEvent> feedbackListener = (player, event) -> { };

    public SkillTreeService(SkillTreeConfig config) {
        this.config = config == null ? SkillTreeConfig.empty() : config;
        this.mcmmoModule = new McmmoSkillModule(this.config);
    }

    public void replace(SkillTreeConfig config) {
        this.config = config == null ? SkillTreeConfig.empty() : config;
        this.mcmmoModule = new McmmoSkillModule(this.config);
        revision++;
        latestSourceTick.clear();
        lastPositions.clear();
        craftOperationSequences.clear();
    }

    public SkillTreeConfig config() { return config; }
    public long revision() { return revision; }

    /** Installs a transient listener for successful skill feedback (normally SkillHudService). */
    public void setFeedbackListener(BiConsumer<ServerPlayer, SkillFeedbackEvent> listener) {
        feedbackListener = listener == null ? (player, event) -> { } : listener;
    }

    /** Stable engine-facing API used by rewards, titles, sidebars and external integrations. */
    public SkillEngine engine() { return config.settings().engine(); }

    public int getSkillLevel(ServerPlayer player, String skillId) {
        if (player == null) return 0;
        String canonical = LegacySkillAdapter.canonical(skillId);
        return progress(player, canonical).level();
    }

    public int getPowerLevel(ServerPlayer player) { return totalLevel(player); }

    public long getAbilityCooldown(ServerPlayer player, String skillId) {
        if (player == null) return 0L;
        return activeCooldownRemainingSeconds(progress(player, LegacySkillAdapter.canonical(skillId)));
    }

    /** Event-oriented API. Every XP mutation requires a stable operation id for recovery and replay safety. */
    public synchronized XpResult grantSkillXp(ServerPlayer player, SkillXpEvent event) {
        return grantSkillXp(player, event, true);
    }

    public synchronized XpResult grantSkillXp(ServerPlayer player, SkillXpEvent event, boolean applyTitleXpBonus) {
        if (player == null || event == null || !player.getUUID().equals(event.playerId())) {
            return recordXpResult(XpResult.rejected(Status.INVALID_REQUEST), event == null ? null : event.source());
        }
        if (event.operationId().isBlank()) {
            return recordXpResult(XpResult.rejected(Status.OPERATION_ID_REQUIRED), event.source());
        }
        String skillId = LegacySkillAdapter.canonical(event.skillId());
        if (config.tree(skillId).isEmpty()) return recordXpResult(XpResult.rejected(Status.UNKNOWN_TREE), event.source());
        SkillTreeData.Progress before = progress(player, skillId);
        if (operationAlreadyClaimed(player, event.operationId())) {
            return recordXpResult(XpResult.rejected(Status.DUPLICATE_OPERATION), event.source());
        }
        XpResult result = addSkillXp(player, skillId, event.amount(), event.source(), applyTitleXpBonus,
                event.operationId());
        publishXpFeedback(player, event, skillId, before, result);
        if (!result.granted()) {
            audit("xp_rejected", event.operationId(), result.status().name().toLowerCase(java.util.Locale.ROOT));
        }
        return recordXpResult(result, event.source());
    }

    private XpResult recordXpResult(XpResult result, SkillXpSource source) {
        xpDiagnostics.record(result, source);
        return result;
    }

    private void publishXpFeedback(ServerPlayer player, SkillXpEvent input, String skillId,
                                   SkillTreeData.Progress before, XpResult result) {
        if (result == null || !result.granted() || result.acceptedXp() <= 0L) return;
        SkillTreeConfig.TreeDefinition tree = config.tree(skillId).orElse(null);
        if (tree == null) return;
        SkillTreeData.Progress after = result.progress();
        long next = after.level() >= config.settings().maxLevel() ? 0L : xpRequired(tree, after.level());
        dispatchFeedback(player, SkillFeedbackEvent.xp(player.getUUID(), skillId, input.source(),
                result.acceptedXp(), before == null ? 0 : before.level(), after.level(),
                after.currentXp(), next, input.operationId()));
    }

    private void dispatchFeedback(ServerPlayer player, SkillFeedbackEvent event) {
        try {
            feedbackListener.accept(player, event);
        } catch (Throwable failure) {
            OperationalErrorReporter.global().warn(OperationalErrorReporter.Context
                    .forModule(ModuleId.SKILLS, "feedback_listener")
                    .withPlayer(player == null ? null : player.getUUID())
                    .withWorld(player == null ? "" : player.level().dimension().toString())
                    .withState("HUD")
                    .withParameters(Map.of("skill", event == null ? "" : event.skillId(),
                            "operationId", event == null ? "" : event.operationId()))
                    .withRecoveryAction("skill_progress_kept;feedback_skipped"), failure);
        }
    }

    public McmmoSkillModule.AbilitySnapshot abilitySnapshot(ServerPlayer player, String skillId) {
        String canonical = LegacySkillAdapter.canonical(skillId);
        SkillTreeData.Progress progress = progress(player, canonical);
        long active = activeRemainingSeconds(player, canonical);
        return new McmmoSkillModule.AbilitySnapshot(canonical, progress.level(),
                mcmmoModule.abilityTier(progress.level()), mcmmoModule.abilityLevel(progress.level()),
                activeCooldownRemainingSeconds(progress), active);
    }

    private boolean operationAlreadyClaimed(ServerPlayer player, String operationId) {
        if (player == null || operationId == null || operationId.isBlank()) return false;
        String scoped = "xp:" + player.getUUID() + ":" + operationId;
        try {
            SkillLedgerData ledger = SkillLedgerData.get(player.level().getServer());
            return ledger.contains(player.getUUID(), operationId) || ledger.contains(player.getUUID(), scoped)
                    || SkillXpTransactionData.get(player.level().getServer())
                    .hasBlockingOperation(player.getUUID(), operationId);
        } catch (RuntimeException unavailable) {
            return transientLedger.contains(operationId) || transientLedger.contains(scoped);
        }
    }

    /**
     * Claims a world-side passive/ability operation in a separate namespace from XP events.
     *
     * The claim is made before any world, inventory or entity mutation.  Persisting the claim in
     * the skill ledger means a replay after a restart cannot grant the same drop/effect twice;
     * the bounded in-memory set remains a cheap fast path for nested callbacks in the same tick.
     */
    private boolean claimSideEffectOperation(ServerPlayer player, String operationId) {
        if (player == null || operationId == null || operationId.isBlank()) return false;
        String scoped = "side_effect:" + player.getUUID() + ":" + operationId;
        if (!settledOperations.add(scoped)) return false;
        boolean claimed;
        try {
            claimed = SkillLedgerData.get(player.level().getServer()).claim(player.getUUID(), scoped);
        } catch (RuntimeException unavailable) {
            // Unit/test worlds can lack SavedData; keep the same fail-closed semantics in memory.
            claimed = transientLedger.claim(scoped);
        }
        trimSettledOperations();
        return claimed;
    }

    private void trimSettledOperations() {
        while (settledOperations.size() > 8192) {
            var iterator = settledOperations.iterator();
            if (!iterator.hasNext()) return;
            iterator.next();
            iterator.remove();
        }
    }

    public SkillTreeData.Progress progress(ServerPlayer player, String treeId) {
        String requested = normalized(treeId);
        SkillTreeConfig.TreeDefinition configured = config.tree(requested).orElse(null);
        String id = configured == null ? requested : configured.id();
        SkillTreeData data = SkillTreeData.get(player);
        SkillTreeData.Progress current = data.progress(player.getUUID(), id);
        SkillTreeConfig.TreeDefinition tree = configured == null ? config.tree(id).orElse(null) : configured;
        if (tree != null && current.level() == 0 && current.totalXp() == 0L) {
            String selectedAlias = null;
            SkillTreeData.Progress selectedProgress = null;
            for (Map.Entry<String, String> alias : LegacySkillAdapter.aliases().entrySet()) {
                if (!alias.getValue().equals(id)) continue;
                SkillTreeData.Progress legacy = data.progress(player.getUUID(), alias.getKey());
                if (legacy.level() <= 0 && legacy.totalXp() <= 0L) continue;
                if (selectedProgress == null
                        || legacy.totalXp() > selectedProgress.totalXp()
                        || (legacy.totalXp() == selectedProgress.totalXp() && legacy.level() > selectedProgress.level())
                        || (legacy.totalXp() == selectedProgress.totalXp() && legacy.level() == selectedProgress.level()
                        && alias.getKey().compareTo(selectedAlias) < 0)) {
                    selectedAlias = alias.getKey();
                    selectedProgress = legacy;
                }
            }
            if (selectedProgress != null) {
                current = new SkillTreeData.Progress(Math.min(config.settings().maxLevel(), selectedProgress.level()),
                        selectedProgress.currentXp(), selectedProgress.totalXp(), selectedProgress.availablePoints(),
                        selectedProgress.attributePoints(), selectedProgress.skillPoints(), selectedProgress.rewardPoints(),
                        selectedProgress.masteryPoints(), selectedProgress.unlockedSkills(), selectedProgress.overflowXp(),
                        selectedProgress.dailyXp(), selectedProgress.dailyEpochDay(), selectedProgress.ultimateCooldownUntilEpochMillis(),
                        selectedProgress.skillLevels(), selectedProgress.activeCooldownUntilEpochMillis(),
                        selectedProgress.skillResetCooldownUntilEpochMillis());
                data.replace(player.getUUID(), id, current);
            }
        }
        if (tree == null || config.settings().maxLevel() >= SkillTreeConfig.LEGACY_MAX_LEVEL || current.level() <= config.settings().maxLevel()) {
            return current;
        }
        int clamped = config.settings().maxLevel();
        Set<String> unlocked = autoUnlockedSkills(tree, current.unlockedSkills(), clamped);
        SkillTreeData.Progress migrated = new SkillTreeData.Progress(clamped, current.currentXp(), current.totalXp(),
                current.availablePoints(), current.attributePoints(), current.skillPoints(), current.rewardPoints(), current.masteryPoints(),
                unlocked, current.overflowXp(), current.dailyXp(), current.dailyEpochDay(), current.ultimateCooldownUntilEpochMillis(),
                current.skillLevels(), current.activeCooldownUntilEpochMillis(), current.skillResetCooldownUntilEpochMillis());
        data.replace(player.getUUID(), id, migrated);
        return migrated;
    }

    public int totalLevel(ServerPlayer player) {
        if (player == null) return 0;
        return totalLevel(SkillTreeData.get(player), player.getUUID());
    }

    public long ultimateCooldownRemainingSeconds(SkillTreeData.Progress progress) {
        if (progress == null) return 0L;
        long remaining = progress.ultimateCooldownUntilEpochMillis() - System.currentTimeMillis();
        return remaining <= 0L ? 0L : (remaining + 999L) / 1_000L;
    }

    /** Remaining cooldown for the modern active skill, exposed to GUI and diagnostics. */
    public long activeCooldownRemainingSeconds(SkillTreeData.Progress progress) {
        if (progress == null) return 0L;
        long remaining = progress.activeCooldownUntilEpochMillis() - System.currentTimeMillis();
        return remaining <= 0L ? 0L : (remaining + 999L) / 1_000L;
    }

    public long activeRemainingSeconds(ServerPlayer player, String treeId) {
        if (player == null) return 0L;
        MobEffectInstance effect = player.getEffect(activeEffect(normalized(treeId)));
        return effect == null ? 0L : Math.max(0L, (effect.getDuration() + 19L) / 20L);
    }

    public int skillLevel(SkillTreeData.Progress progress, String skillId) {
        if (progress == null || skillId == null) return 0;
        String id = normalized(skillId);
        int stored = progress.skillLevels().getOrDefault(id, 0);
        if (mcmmoModule.enabled() && (id.equals("active") || id.equals("passive"))) {
            return Math.max(stored, mcmmoModule.abilityLevel(progress.level()));
        }
        return stored > 0 || progress.unlockedSkills().contains(id) ? Math.max(1, stored) : 0;
    }

    /** Spend one point to raise an unlocked modern active/passive skill, up to its configured cap. */
    public synchronized PointResult upgradeSkill(ServerPlayer player, String treeId, String skillId) {
        if (mcmmoModule.enabled()) return PointResult.rejected(Status.ENGINE_MANAGED);
        Optional<SkillTreeConfig.TreeDefinition> target = config.tree(treeId);
        if (player == null || target.isEmpty()) return PointResult.rejected(target.isEmpty() ? Status.UNKNOWN_TREE : Status.INVALID_REQUEST);
        SkillTreeConfig.SkillDefinition skill = target.get().skills().stream().filter(s -> s.id().equals(normalized(skillId))).findFirst().orElse(null);
        if (skill == null) return PointResult.rejected(Status.UNKNOWN_SKILL);
        SkillTreeData data = SkillTreeData.get(player);
        SkillTreeData.Progress progress = data.progress(player.getUUID(), target.get().id());
        if (progress.level() < skill.unlockLevel()) return PointResult.rejected(Status.LEVEL_REQUIRED);
        boolean firstFreeLevel = !progress.unlockedSkills().contains(skill.id());
        int current = skillLevel(progress, skill.id());
        if (!firstFreeLevel && current >= skill.maxLevel()) return PointResult.rejected(Status.SKILL_CAP_REACHED);
        if (!firstFreeLevel && progress.availablePoints() < 1) return PointResult.rejected(Status.NO_POINTS);
        Map<String, Integer> levels = new java.util.HashMap<>(progress.skillLevels());
        levels.put(skill.id(), firstFreeLevel ? 1 : current + 1);
        Set<String> unlocked = new java.util.HashSet<>(progress.unlockedSkills());
        unlocked.add(skill.id());
        int spent = firstFreeLevel ? 0 : 1;
        SkillTreeData.Progress updated = copy(progress, progress.availablePoints() - spent, progress.attributePoints(),
                progress.skillPoints() + spent, unlocked, levels, progress.activeCooldownUntilEpochMillis(), progress.skillResetCooldownUntilEpochMillis());
        data.replace(player.getUUID(), target.get().id(), updated);
        return new PointResult(Status.GRANTED, updated);
    }

    /** Reset modern skill allocations; reset cooldown prevents combat-time respec abuse. */
    public synchronized PointResult resetSkills(ServerPlayer player, String treeId) {
        if (mcmmoModule.enabled()) return PointResult.rejected(Status.ENGINE_MANAGED);
        Optional<SkillTreeConfig.TreeDefinition> target = config.tree(treeId);
        if (player == null || target.isEmpty()) return PointResult.rejected(target.isEmpty() ? Status.UNKNOWN_TREE : Status.INVALID_REQUEST);
        SkillTreeData data = SkillTreeData.get(player);
        SkillTreeData.Progress progress = data.progress(player.getUUID(), target.get().id());
        long now = System.currentTimeMillis();
        if (now < progress.skillResetCooldownUntilEpochMillis()) return PointResult.rejected(Status.RESET_COOLDOWN);
        if (progress.masteryPoints() < 1) return PointResult.rejected(Status.RESET_RESOURCE_REQUIRED);
        int spent = 0;
        Set<String> unlocked = new java.util.HashSet<>(progress.unlockedSkills());
        Map<String, Integer> levels = new java.util.HashMap<>();
        for (SkillTreeConfig.SkillDefinition skill : target.get().skills()) {
            if (skill.kind() == SkillTreeConfig.SkillKind.ACTIVE || skill.kind() == SkillTreeConfig.SkillKind.PASSIVE) {
                int level = skillLevel(progress, skill.id());
                spent += Math.max(0, level - (progress.unlockedSkills().contains(skill.id()) ? 1 : 0));
                if (level > 0 && progress.unlockedSkills().contains(skill.id())) {
                    levels.put(skill.id(), 1);
                } else unlocked.remove(skill.id());
            } else if (skill.pointCost() > 0) {
                unlocked.remove(skill.id());
            }
        }
        SkillTreeData.Progress updated = new SkillTreeData.Progress(progress.level(), progress.currentXp(), progress.totalXp(),
                progress.availablePoints() + spent, progress.attributePoints(), 0, progress.rewardPoints(),
                progress.masteryPoints() - 1, unlocked, progress.overflowXp(), progress.dailyXp(), progress.dailyEpochDay(),
                progress.ultimateCooldownUntilEpochMillis(), levels, progress.activeCooldownUntilEpochMillis(), now + 10 * 60_000L);
        data.replace(player.getUUID(), target.get().id(), updated);
        return new PointResult(Status.GRANTED, updated);
    }

    /** Server-authoritative active skill activation. Effects are applied on the server thread only. */
    public synchronized PointResult activateSkill(ServerPlayer player, String treeId) {
        Optional<SkillTreeConfig.TreeDefinition> target = config.tree(treeId);
        if (player == null || target.isEmpty()) return PointResult.rejected(target.isEmpty() ? Status.UNKNOWN_TREE : Status.INVALID_REQUEST);
        SkillTreeConfig.TreeDefinition tree = target.get();
        SkillTreeConfig.SkillDefinition active = tree.skills().stream().filter(s -> s.kind() == SkillTreeConfig.SkillKind.ACTIVE).findFirst().orElse(null);
        if (active == null) return PointResult.rejected(Status.UNKNOWN_SKILL);
        SkillTreeData data = SkillTreeData.get(player);
        SkillTreeData.Progress progress = data.progress(player.getUUID(), tree.id());
        int level = skillLevel(progress, active.id());
        if (progress.level() < active.unlockLevel() || level <= 0) return PointResult.rejected(Status.LEVEL_REQUIRED);
        if (activeCooldownRemainingSeconds(progress) > 0L) return PointResult.rejected(Status.ACTIVE_COOLDOWN);
        int durationSeconds = tunedInt(active.tuning().minDurationSeconds(), active.tuning().maxDurationSeconds(), level);
        long cooldown = tunedInt(active.tuning().maxCooldownSeconds(), active.tuning().minCooldownSeconds(), level) * 1000L;
        String canonicalTree = LegacySkillAdapter.canonical(tree.id());
        if (canonicalTree.equals("swords") || canonicalTree.equals("axes")) {
            net.minecraft.world.phys.Vec3 step = player.getLookAngle().normalize().scale(1.5D + level * 0.25D);
            if (!player.level().noCollision(player, player.getBoundingBox().move(step))) {
                return PointResult.rejected(Status.BLOCKED_BY_COLLISION);
            }
            player.setDeltaMovement(step.x, Math.max(0.15D, step.y), step.z);
        }
        if (canonicalTree.equals("exploration") || canonicalTree.equals("fishing")) {
            double radius = 32D + (level - 1) * (64D / 9D);
            net.minecraft.world.entity.LivingEntity nearest = player.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                    player.getBoundingBox().inflate(radius), entity -> entity != player && entity.isAlive()).stream()
                    .min(java.util.Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
            if (nearest != null) {
                player.sendSystemMessage(Component.literal("[远见侦察] 最近目标：" + Math.round(player.distanceTo(nearest)) + " 格"));
                nearest.addEffect(new MobEffectInstance(MobEffects.GLOWING, Math.min(20 * durationSeconds, 20 * 10), 0, false, false, true), player);
            }
        }
        if ((canonicalTree.equals("farmer") || canonicalTree.equals("herbalism"))
                && player.level() instanceof ServerLevel serverLevel) {
            int processed = 0;
            int radius = (int) Math.round(4D + (level - 1) * (6D / 9D));
            BlockPos center = player.blockPosition();
            for (BlockPos scan : BlockPos.betweenClosed(center.offset(-radius, -2, -radius), center.offset(radius, 2, radius))) {
                if (processed >= 32 + (level - 1) * 128 / 9) break;
                BlockState crop = serverLevel.getBlockState(scan);
                if (crop.getBlock() instanceof CropBlock cropBlock && cropBlock.isMaxAge(crop)
                        && serverLevel.destroyBlock(scan, true, player)) {
                    serverLevel.setBlock(scan, cropBlock.getStateForAge(0), 3);
                    processed++;
                }
            }
        }
        player.addEffect(new MobEffectInstance(activeEffect(tree.id()), durationSeconds * 20,
                Math.min(4, Math.max(0, (level - 1) / 2)), false, true, true), player);
        SkillTreeData.Progress updated = copy(progress, progress.availablePoints(), progress.attributePoints(), progress.skillPoints(),
                progress.unlockedSkills(), progress.skillLevels(), System.currentTimeMillis() + cooldown, progress.skillResetCooldownUntilEpochMillis());
        data.replace(player.getUUID(), tree.id(), updated);
        dispatchFeedback(player, SkillFeedbackEvent.active(player.getUUID(), tree.id(), level,
                "ability:" + player.getUUID() + ":" + tree.id() + ":" + updated.activeCooldownUntilEpochMillis(),
                active.display()));
        return new PointResult(Status.GRANTED, updated);
    }

    private static SkillTreeData.Progress copy(SkillTreeData.Progress p, int available, int attributes, int skillPoints,
                                               Set<String> unlocked, Map<String, Integer> levels, long activeCooldown, long resetCooldown) {
        return new SkillTreeData.Progress(p.level(), p.currentXp(), p.totalXp(), available, attributes, skillPoints,
                p.rewardPoints(), p.masteryPoints(), unlocked, p.overflowXp(), p.dailyXp(), p.dailyEpochDay(),
                p.ultimateCooldownUntilEpochMillis(), levels, activeCooldown, resetCooldown);
    }

    /**
     * Legacy convenience entry point retained for binary compatibility. It cannot provide a
     * durable operation id, so callers must migrate to {@link #grantSkillXp(ServerPlayer, SkillXpEvent)}.
     */
    @Deprecated(forRemoval = false)
    public synchronized XpResult addSkillXp(ServerPlayer player, String treeId, long requestedXp, SkillXpSource source) {
        return recordXpResult(XpResult.rejected(Status.OPERATION_ID_REQUIRED), source);
    }

    /**
     * Legacy convenience entry point retained for binary compatibility. Use a {@link SkillXpEvent}
     * with a stable operation id instead.
     */
    @Deprecated(forRemoval = false)
    public synchronized XpResult addSkillXp(ServerPlayer player, String treeId, long requestedXp, SkillXpSource source,
                                            boolean applyTitleXpBonus) {
        return recordXpResult(XpResult.rejected(Status.OPERATION_ID_REQUIRED), source);
    }

    /** Returns runtime XP counters and transaction state for administrators; player data is not exposed. */
    public SkillXpDiagnostics.Snapshot xpHealth(MinecraftServer server) {
        SkillXpTransactionData.Summary transactions = null;
        try {
            transactions = SkillXpTransactionData.get(server).summary();
        } catch (RuntimeException ignored) {
            // The event counters remain useful during partial startup or a damaged SavedData load.
        }
        return xpDiagnostics.snapshot(transactions);
    }

    private synchronized XpResult addSkillXp(ServerPlayer player, String treeId, long requestedXp, SkillXpSource source,
                                              boolean applyTitleXpBonus, String operationId) {
        if (player == null || source == null || requestedXp <= 0L) return XpResult.rejected(Status.INVALID_REQUEST);
        Optional<SkillTreeConfig.TreeDefinition> target = config.tree(treeId);
        if (target.isEmpty()) return XpResult.rejected(Status.UNKNOWN_TREE);
        SkillTreeConfig.TreeDefinition tree = target.get();
        if (!tree.sources().contains(source)) return XpResult.rejected(Status.SOURCE_NOT_ALLOWED);
        long tick = player.level().getServer().getTickCount();
        RateLimitKey key = new RateLimitKey(player.getUUID(), tree.id(), source);
        Long priorSourceTick = latestSourceTick.get(key);
        if (source.rateLimited() && priorSourceTick != null
                && tick - priorSourceTick < config.settings().minIntervalTicks()) {
            return XpResult.rejected(Status.RATE_LIMITED);
        }
        latestSourceTick.put(key, tick);

        SkillTreeData data = SkillTreeData.get(player);
        SkillTreeData.Progress before = data.progress(player.getUUID(), tree.id());
        int totalBefore = totalLevel(data, player.getUUID());
        long epochDay = LocalDate.now(ModMindEntry.configuredZone()).toEpochDay();
        long dailyXp = before.dailyEpochDay() == epochDay ? before.dailyXp() : 0L;
        long capacity = Math.max(0L, config.settings().maxDailyXp() - dailyXp);
        if (capacity == 0L) return XpResult.rejected(Status.DAILY_LIMIT_REACHED);
        long skilled = applyPassiveXpBonus(tree, before, requestedXp, source);
        if (mcmmoModule.enabled()) skilled = multiplyXp(skilled, config.settings().xpMultiplier());
        long boosted = applyTitleXpBonus ? applyTitleBonus(player, skilled) : skilled;
        long accepted = Math.min(boosted, capacity);
        if (accepted <= 0L) return XpResult.rejected(Status.DAILY_LIMIT_REACHED);

        int level = Math.min(before.level(), config.settings().maxLevel());
        long currentXp = before.currentXp();
        long overflowXp = before.overflowXp();
        int levelsGained = 0;
        if (level >= config.settings().maxLevel()) {
            overflowXp = saturatedAdd(overflowXp, accepted);
        } else {
            long remaining = accepted;
            while (remaining > 0L && level < config.settings().maxLevel()) {
                long needed = xpRequired(tree, level);
                long toLevel = Math.max(0L, needed - currentXp);
                if (remaining < toLevel) {
                    currentXp = saturatedAdd(currentXp, remaining);
                    remaining = 0L;
                } else {
                    remaining -= toLevel;
                    currentXp = 0L;
                    level++;
                    levelsGained++;
                }
            }
            if (level >= config.settings().maxLevel() && remaining > 0L) overflowXp = saturatedAdd(overflowXp, remaining);
        }
        int earnedPoints = mcmmoModule.enabled() ? 0 : level / config.settings().pointsEveryLevels();
        int alreadyAccounted = before.availablePoints() + before.attributePoints() + before.skillPoints()
                + before.rewardPoints() + before.masteryPoints();
        int newPoints = Math.max(0, earnedPoints - alreadyAccounted);
        Set<String> unlocked = autoUnlockedSkills(tree, before.unlockedSkills(), level);
        SkillTreeData.Progress after = new SkillTreeData.Progress(level, currentXp, saturatedAdd(before.totalXp(), accepted),
                before.availablePoints() + newPoints, before.attributePoints(), before.skillPoints(), before.rewardPoints(),
                before.masteryPoints(), unlocked, overflowXp, saturatedAdd(dailyXp, accepted), epochDay,
                before.ultimateCooldownUntilEpochMillis());
        SkillXpTransactionData journal = null;
        String transactionId = "";
        if (operationId != null && !operationId.isBlank()) {
            try {
                journal = SkillXpTransactionData.get(player.level().getServer());
                Optional<SkillXpTransactionData.Entry> prepared = journal.prepare(player.getUUID(), operationId,
                        tree.id(), source, before, after, System.currentTimeMillis());
                if (prepared.isEmpty()) return XpResult.rejected(Status.DUPLICATE_OPERATION);
                transactionId = prepared.get().transactionId();
                // The PREPARED target must reach durable storage before this method can change
                // progress. Later checkpoints may batch the progress and COMMITTED state because
                // startup can deterministically finish this target from the saved evidence.
                journal.flush(player.level().getServer());
                xpTransactionFlushPending = true;
            } catch (RuntimeException failure) {
                audit("xp_prepare_failed", operationId, failure.getClass().getSimpleName());
                return XpResult.rejected(Status.PERSISTENCE_UNAVAILABLE);
            }
        }
        boolean commitTransitioned = false;
        try {
            data.replace(player.getUUID(), tree.id(), after);
            if (journal != null) {
                journal.commit(transactionId, after, System.currentTimeMillis());
                commitTransitioned = true;
                xpTransactionFlushPending = true;
                // Preserve the legacy bounded ledger as a compatibility index. The transaction
                // journal remains authoritative for new operations.
                try { SkillLedgerData.get(player.level().getServer()).claim(player.getUUID(),
                        "xp:" + player.getUUID() + ":" + operationId); } catch (RuntimeException ignored) { }
            }
            runCommittedXpSideEffects(player, data, tree, source, before, after, levelsGained, totalBefore, operationId);
            return new XpResult(Status.GRANTED, accepted, levelsGained, after,
                    Math.max(0L, config.settings().maxDailyXp() - after.dailyXp()));
        } catch (RuntimeException | Error failure) {
            if (commitTransitioned) {
                // The terminal state and target snapshot are already updated in memory. Do not
                // restore the before-image: a later checkpoint must persist this exact pair.
                xpTransactionFlushPending = true;
                OperationalErrorReporter.global().error(OperationalErrorReporter.Context
                        .forModule(ModuleId.SKILLS, "xp_transaction_commit")
                        .withPlayer(player.getUUID()).withWorld(player.level().dimension().toString())
                        .withState("COMMIT_PENDING_FLUSH")
                        .withParameters(Map.of("skill", tree.id(), "operationId", operationId))
                        .withRecoveryAction("live_progress_retained;retry_next_checkpoint"), failure);
                return XpResult.rejected(Status.PERSISTENCE_UNAVAILABLE);
            }
            // PREPARED was persisted before the mutation. Do not guess whether a later write
            // reached disk or restore a conflicting live image; recovery will complete the saved
            // target exactly once on the next start if this checkpoint cannot finish.
            xpTransactionFlushPending = true;
            OperationalErrorReporter.global().error(OperationalErrorReporter.Context
                    .forModule(ModuleId.SKILLS, "xp_transaction")
                    .withPlayer(player.getUUID()).withWorld(player.level().dimension().toString())
                    .withState("PREPARED").withParameters(Map.of("skill", tree.id(), "operationId", operationId))
                    .withRecoveryAction("prepared_target_retained_for_checkpoint_or_startup_recovery"), failure);
            return XpResult.rejected(Status.PERSISTENCE_UNAVAILABLE);
        }
    }

    /** Presentation and short-lived effects run after the XP transaction is already durable. */
    private void runCommittedXpSideEffects(ServerPlayer player, SkillTreeData data, SkillTreeConfig.TreeDefinition tree,
                                           SkillXpSource source, SkillTreeData.Progress before,
                                           SkillTreeData.Progress after, int levelsGained, int totalBefore,
                                           String operationId) {
        try {
            refreshAttributes(player);
            SkillTreeData.Progress effectProgress = triggerUltimate(player, data, tree, source, after);
            if (levelsGained > 0) {
                sendLevelNotices(player, tree, before, effectProgress);
                queueMilestoneAnnouncement(player, data, tree, before.level(), effectProgress.level(), totalBefore,
                        totalLevel(data, player.getUUID()));
            }
        } catch (RuntimeException exception) {
            OperationalErrorReporter.global().warn(OperationalErrorReporter.Context
                    .forModule(ModuleId.SKILLS, "xp_committed_side_effect")
                    .withPlayer(player.getUUID()).withWorld(player.level().dimension().toString())
                    .withState("COMMITTED").withParameters(Map.of("skill", tree.id(), "operationId", operationId))
                    .withRecoveryAction("xp_kept;presentation_or_effect_skipped"), exception);
        }
    }

    public synchronized PointResult investAttribute(ServerPlayer player, String treeId) {
        Optional<SkillTreeConfig.TreeDefinition> target = config.tree(treeId);
        if (player == null || target.isEmpty()) return PointResult.rejected(target.isEmpty() ? Status.UNKNOWN_TREE : Status.INVALID_REQUEST);
        SkillTreeData data = SkillTreeData.get(player);
        SkillTreeData.Progress progress = data.progress(player.getUUID(), target.get().id());
        int maxPoints = (int) Math.floor((config.settings().pointAttributeCap() + 0.000_000_1D)
                / config.settings().pointAttributeBonus());
        if (progress.availablePoints() < 1) return PointResult.rejected(Status.NO_POINTS);
        if (progress.attributePoints() >= maxPoints) return PointResult.rejected(Status.ATTRIBUTE_CAP_REACHED);
        SkillTreeData.Progress updated = new SkillTreeData.Progress(progress.level(), progress.currentXp(), progress.totalXp(),
                progress.availablePoints() - 1, progress.attributePoints() + 1, progress.skillPoints(), progress.rewardPoints(),
                progress.masteryPoints(), progress.unlockedSkills(),
                progress.overflowXp(), progress.dailyXp(), progress.dailyEpochDay(), progress.ultimateCooldownUntilEpochMillis());
        data.replace(player.getUUID(), target.get().id(), updated);
        refreshAttributes(player);
        return new PointResult(Status.GRANTED, updated);
    }

    /** Grants bonus professional points from quests, bosses or achievements. */
    public synchronized PointResult grantSkillPoints(ServerPlayer player, String treeId, int amount) {
        Optional<SkillTreeConfig.TreeDefinition> target = config.tree(treeId);
        if (player == null || target.isEmpty() || amount <= 0 || amount > 1000) {
            return PointResult.rejected(target.isEmpty() ? Status.UNKNOWN_TREE : Status.INVALID_REQUEST);
        }
        SkillTreeData data = SkillTreeData.get(player);
        SkillTreeData.Progress progress = data.progress(player.getUUID(), target.get().id());
        int updatedAvailable = progress.availablePoints() > Integer.MAX_VALUE - amount
                ? Integer.MAX_VALUE : progress.availablePoints() + amount;
        SkillTreeData.Progress updated = copy(progress, updatedAvailable, progress.attributePoints(), progress.skillPoints(),
                progress.unlockedSkills(), progress.skillLevels(), progress.activeCooldownUntilEpochMillis(), progress.skillResetCooldownUntilEpochMillis());
        data.replace(player.getUUID(), target.get().id(), updated);
        return new PointResult(Status.GRANTED, updated);
    }

    /**
     * Server-side only passive drop settlement. It deliberately regenerates the block's normal
     * loot with the original player/tool context, then spawns it once; spawned items never route
     * back through a block-break event, so a passive cannot recursively award itself.
     */
    public synchronized boolean settleBlockPassive(ServerPlayer player, ServerLevel world, BlockState state,
                                                   net.minecraft.core.BlockPos pos, BlockEntity blockEntity) {
        if (player == null || world == null || state == null || player.getAbilities().instabuild || hasSilkTouch(player)) return false;
        String treeId = state.is(BlockTags.LOGS) ? "woodcutting"
                : state.is(BlockTags.CROPS) ? "herbalism"
                : state.is(BlockTags.MINEABLE_WITH_SHOVEL) ? "excavation"
                : state.is(BlockTags.MINEABLE_WITH_PICKAXE) ? "mining" : "";
        if (treeId.isBlank()) return false;
        SkillTreeData.Progress progress = progress(player, treeId);
        int level = skillLevel(progress, "passive");
        String operationId = "block:" + player.getUUID() + ":" + pos.asLong() + ":" + world.getGameTime();
        SkillTreeConfig.TreeDefinition tree = config.tree(treeId).orElse(null);
        if (level <= 0 || tree == null) return false;
        if (player.getRandom().nextDouble() >= passiveChance(tree, level)) {
            audit("passive_drop", operationId, "not_triggered");
            return false;
        }
        if (!claimSideEffectOperation(player, operationId)) {
            audit("passive_drop", operationId, "duplicate_operation");
            return false;
        }
        for (net.minecraft.world.item.ItemStack drop : Block.getDrops(state, world, pos, blockEntity, player, player.getMainHandItem())) {
            if (!drop.isEmpty()) Block.popResource(world, pos, drop.copy());
        }
        countPassive(treeId, "extra_drop");
        dispatchFeedback(player, SkillFeedbackEvent.passive(player.getUUID(), treeId, operationId,
                "extra_drop"));
        audit("passive_drop", operationId, "granted");
        return true;
    }

    /** Bounded same-log chain breaking; the guard prevents nested AFTER events from restarting it. */
    public synchronized int settleLumberjackChain(ServerPlayer player, ServerLevel world, BlockPos origin, BlockState source) {
        String activeSkill = mcmmoModule.enabled() ? "woodcutting" : "lumberjack";
        if (player == null || world == null || source == null || !source.is(BlockTags.LOGS)
                || activeRemainingSeconds(player, activeSkill) <= 0L || !lumberChainsInProgress.add(player.getUUID())) return 0;
        String operationId = "lumberjack:" + player.getUUID() + ":" + origin.asLong() + ":" + world.getGameTime();
        try {
            if (!claimSideEffectOperation(player, operationId)) return 0;
            int level = skillLevel(progress(player, activeSkill), "active");
            int cap = 16 + (Math.max(1, level) - 1) * 80 / 9;
            int broken = 0;
            Set<BlockPos> visited = new HashSet<>();
            ArrayDeque<BlockPos> pending = new ArrayDeque<>();
            pending.add(origin);
            visited.add(origin);
            while (!pending.isEmpty() && broken < cap) {
                BlockPos current = pending.removeFirst();
                for (Direction direction : Direction.values()) {
                    BlockPos next = current.relative(direction);
                    if (!visited.add(next) || next.distSqr(origin) > 18D * 18D) continue;
                    BlockState candidate = world.getBlockState(next);
                    if (!candidate.is(source.getBlock()) || !candidate.is(BlockTags.LOGS)) continue;
                    if (world.destroyBlock(next, true, player)) {
                        broken++;
                        pending.addLast(next);
                    }
                    if (broken >= cap) break;
                }
            }
            return broken;
        } finally {
            lumberChainsInProgress.remove(player.getUUID());
        }
    }

    /** Server-side combat passive: applies bounded defensive/offensive effects once per operation. */
    public synchronized void settleCombatPassive(ServerPlayer player, net.minecraft.world.entity.LivingEntity target,
                                                  String operationId) {
        if (player == null || target == null || operationId == null) return;
        String meleeSkill = mcmmoModule.enabled() ? "swords" : "warrior";
        String rangedSkill = mcmmoModule.enabled() ? "archery" : "hunter";
        String defenseSkill = mcmmoModule.enabled() ? "acrobatics" : "guardian";
        int melee = skillLevel(progress(player, meleeSkill), "passive");
        int axes = skillLevel(progress(player, "axes"), "passive");
        int ranged = skillLevel(progress(player, rangedSkill), "passive");
        int defense = skillLevel(progress(player, defenseSkill), "passive");
        if (melee <= 0 && axes <= 0 && ranged <= 0 && defense <= 0) return;
        boolean meleeTriggered = melee > 0 && player.getRandom().nextDouble() < Math.min(0.20D, melee * 0.02D);
        boolean axesTriggered = axes > 0 && player.getRandom().nextDouble() < Math.min(0.20D, axes * 0.02D);
        boolean rangedTriggered = ranged > 0 && player.getRandom().nextDouble() < Math.min(0.25D, ranged * 0.025D);
        boolean defenseTriggered = defense > 0 && player.getRandom().nextDouble() < Math.min(0.30D, defense * 0.03D);
        if (!meleeTriggered && !axesTriggered && !rangedTriggered && !defenseTriggered) return;
        if (!claimSideEffectOperation(player, operationId)) return;
        if (meleeTriggered) {
            player.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 40, 0, false, true, true), player);
            countPassive(meleeSkill, "critical_rhythm");
            dispatchFeedback(player, SkillFeedbackEvent.passive(player.getUUID(), meleeSkill, operationId,
                    "critical_rhythm"));
        }
        if (axesTriggered) {
            player.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 40, 0, false, true, true), player);
            countPassive("axes", "skull_splitter");
            dispatchFeedback(player, SkillFeedbackEvent.passive(player.getUUID(), "axes", operationId,
                    "skull_splitter"));
        }
        if (rangedTriggered) {
            target.addEffect(new MobEffectInstance(MobEffects.GLOWING, 100, 0, false, false, true), player);
            countPassive(rangedSkill, "loot_intuition");
            dispatchFeedback(player, SkillFeedbackEvent.passive(player.getUUID(), rangedSkill, operationId,
                    "loot_intuition"));
        }
        if (defenseTriggered) {
            player.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 40, 0, false, true, true), player);
            countPassive(defenseSkill, "steadfast_counter");
            dispatchFeedback(player, SkillFeedbackEvent.passive(player.getUUID(), defenseSkill, operationId,
                    "steadfast_counter"));
        }
    }

    /** Server-side crafting passive. It never mutates recipe inputs; it only grants a bounded haste window. */
    public synchronized void settleCraftPassive(ServerPlayer player, long craftedDelta, String operationId) {
        if (player == null || craftedDelta <= 0L || operationId == null) return;
        String repairSkill = mcmmoModule.enabled() ? "repair" : "smithing";
        int smithing = skillLevel(progress(player, repairSkill), "passive");
        int alchemy = skillLevel(progress(player, "alchemy"), "passive");
        if (smithing <= 0 && alchemy <= 0) return;
        boolean smithingTriggered = smithing > 0 && player.getRandom().nextDouble() < Math.min(0.30D, smithing * 0.03D);
        boolean alchemyTriggered = alchemy > 0 && player.getRandom().nextDouble() < Math.min(0.25D, alchemy * 0.025D);
        if (!smithingTriggered && !alchemyTriggered) return;
        if (!claimSideEffectOperation(player, operationId)) return;
        if (smithingTriggered) {
            player.addEffect(new MobEffectInstance(MobEffects.HASTE, 60, 0, false, true, true), player);
            countPassive(repairSkill, "material_saving");
            dispatchFeedback(player, SkillFeedbackEvent.passive(player.getUUID(), repairSkill, operationId,
                    "material_saving"));
        }
        if (alchemyTriggered) {
            player.addEffect(new MobEffectInstance(MobEffects.LUCK, 60, 0, false, true, true), player);
            countPassive("alchemy", "recipe_mastery");
            dispatchFeedback(player, SkillFeedbackEvent.passive(player.getUUID(), "alchemy", operationId,
                    "recipe_mastery"));
        }
    }

    /**
     * Called from the crafting-result slot after vanilla has accepted the output.  This is the
     * authoritative, event-driven replacement for the old five-second scan of every registered
     * item statistic.  Unsupported machine/automation menus deliberately grant no skill XP until
     * they expose an explicit server-side adapter, which is safer than sampling unbounded state.
     */
    public synchronized void recordCrafted(ServerPlayer player, int craftedCount) {
        if (player == null || craftedCount <= 0 || player.getAbilities().instabuild) {
            return;
        }
        long boundedCount = Math.min(1_000L, craftedCount);
        long sequence = craftOperationSequences.merge(player.getUUID(), 1L, Long::sum);
        String operationId = "craft:" + player.getUUID() + ":" + player.level().getGameTime() + ":" + sequence;
        long craftedXp = Math.min(1_000L, boundedCount * 5L);
        String repairSkill = mcmmoModule.enabled() ? "repair" : "smithing";
        grantSkillXp(player, SkillXpEvent.of(player.getUUID(), repairSkill, SkillXpSource.CRAFT, craftedXp,
                operationId + ":repair", "craft", operationId));
        grantSkillXp(player, SkillXpEvent.of(player.getUUID(), "alchemy", SkillXpSource.CRAFT, craftedXp,
                operationId + ":alchemy", "craft", operationId));
        settleCraftPassive(player, boundedCount, operationId);
        audit("craft_recorded", operationId, "count=" + boundedCount);
    }

    /** Server-side exploration/farming support effects with bounded frequency. */
    public synchronized void settleSurvivalPassive(ServerPlayer player, String operationId) {
        if (player == null || operationId == null) return;
        String farmingSkill = mcmmoModule.enabled() ? "herbalism" : "farmer";
        String explorationSkill = mcmmoModule.enabled() ? "acrobatics" : "exploration";
        int farmer = skillLevel(progress(player, farmingSkill), "passive");
        int exploration = skillLevel(progress(player, explorationSkill), "passive");
        if (farmer <= 0 && exploration <= 0) return;
        boolean farmerTriggered = farmer > 0 && player.getRandom().nextDouble() < Math.min(0.30D, farmer * 0.03D);
        boolean explorationTriggered = exploration > 0 && player.getRandom().nextDouble() < Math.min(0.25D, exploration * 0.025D);
        if (!farmerTriggered && !explorationTriggered) return;
        if (!claimSideEffectOperation(player, operationId)) return;
        if (farmerTriggered) {
            player.addEffect(new MobEffectInstance(MobEffects.SATURATION, 20, 0, false, true, true), player);
            countPassive(farmingSkill, "natural_gift");
            dispatchFeedback(player, SkillFeedbackEvent.passive(player.getUUID(), farmingSkill, operationId,
                    "natural_gift"));
        }
        if (explorationTriggered) {
            player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 40, 0, false, true, true), player);
            countPassive(explorationSkill, "adventure_intuition");
            dispatchFeedback(player, SkillFeedbackEvent.passive(player.getUUID(), explorationSkill, operationId,
                    "adventure_intuition"));
        }
    }

    public synchronized Map<String, Long> passiveTriggerCounts() { return Map.copyOf(passiveTriggerCounts); }
    private void countPassive(String tree, String effect) { passiveTriggerCounts.merge(tree + "." + effect, 1L, Long::sum); }
    private void audit(String action, String operationId, String outcome) {
        AsyncAuditLogWriter.global().submit(ModuleId.SKILLS, "skill_" + action,
                Path.of("logs", "omnitools-skills-audit.log"),
                System.currentTimeMillis() + " action=" + action + " operationId=" + operationId + " outcome=" + outcome + System.lineSeparator());
    }

    private static double passiveChance(SkillTreeConfig.TreeDefinition tree, int level) {
        SkillTreeConfig.SkillDefinition passive = tree.skills().stream()
                .filter(skill -> skill.kind() == SkillTreeConfig.SkillKind.PASSIVE).findFirst().orElse(null);
        if (passive == null) return 0D;
        return tunedDouble(passive.tuning().minValue(), passive.tuning().maxValue(), level);
    }

    private static boolean hasSilkTouch(ServerPlayer player) {
        try {
            Holder<Enchantment> silkTouch = player.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(Enchantments.SILK_TOUCH);
            return player.getMainHandItem().getEnchantments().getLevel(silkTouch) > 0;
        } catch (RuntimeException ignored) {
            // Fail closed: an unavailable registry must not create an accidental duplicate drop.
            return true;
        }
    }

    private static int tunedInt(int levelOne, int levelTen, int level) {
        return (int) Math.round(levelOne + (levelTen - levelOne) * ((Math.max(1, Math.min(10, level)) - 1) / 9.0D));
    }
    private static double tunedDouble(double levelOne, double levelTen, int level) {
        return levelOne + (levelTen - levelOne) * ((Math.max(1, Math.min(10, level)) - 1) / 9.0D);
    }

    public synchronized PointResult unlockSkill(ServerPlayer player, String treeId, String skillId) {
        Optional<SkillTreeConfig.TreeDefinition> target = config.tree(treeId);
        if (player == null || target.isEmpty()) return PointResult.rejected(target.isEmpty() ? Status.UNKNOWN_TREE : Status.INVALID_REQUEST);
        SkillTreeConfig.SkillDefinition skill = target.get().skills().stream()
                .filter(entry -> entry.id().equals(normalized(skillId))).findFirst().orElse(null);
        if (skill == null) return PointResult.rejected(Status.UNKNOWN_SKILL);
        SkillTreeData data = SkillTreeData.get(player);
        SkillTreeData.Progress progress = data.progress(player.getUUID(), target.get().id());
        if (progress.unlockedSkills().contains(skill.id())) return PointResult.rejected(Status.ALREADY_UNLOCKED);
        if (progress.level() < skill.unlockLevel()) return PointResult.rejected(Status.LEVEL_REQUIRED);
        if (progress.availablePoints() < skill.pointCost()) return PointResult.rejected(Status.NO_POINTS);
        Set<String> unlocked = new HashSet<>(progress.unlockedSkills());
        unlocked.add(skill.id());
        SkillTreeData.Progress updated = new SkillTreeData.Progress(progress.level(), progress.currentXp(), progress.totalXp(),
                progress.availablePoints() - skill.pointCost(), progress.attributePoints(), progress.skillPoints() + skill.pointCost(),
                progress.rewardPoints(), progress.masteryPoints(), unlocked, progress.overflowXp(), progress.dailyXp(), progress.dailyEpochDay(),
                progress.ultimateCooldownUntilEpochMillis());
        data.replace(player.getUUID(), target.get().id(), updated);
        return new PointResult(Status.GRANTED, updated);
    }

    /** Exchanges one point for the configured, tree-independent currency reward using a stable event key. */
    public synchronized PointResult claimUniformReward(ServerPlayer player, String treeId) {
        Optional<SkillTreeConfig.TreeDefinition> target = config.tree(treeId);
        if (player == null || target.isEmpty()) return PointResult.rejected(target.isEmpty() ? Status.UNKNOWN_TREE : Status.INVALID_REQUEST);
        long currency = config.settings().pointRewardCurrency();
        if (currency <= 0L) return PointResult.rejected(Status.REWARD_DISABLED);
        SkillTreeData data = SkillTreeData.get(player);
        SkillTreeData.Progress progress = data.progress(player.getUUID(), target.get().id());
        if (progress.availablePoints() < 1) return PointResult.rejected(Status.NO_POINTS);
        int ordinal = progress.attributePoints() + progress.skillPoints() + progress.rewardPoints() + progress.masteryPoints();
        String eventId = "skill_point:" + player.getUUID() + ":" + target.get().id() + ":" + ordinal;
        CheckinData.CurrencyRewardResult result = CheckinData.get(player).applyRewardCurrency(player.getUUID(), eventId,
                "uniform_reward", currency, player.getGameProfile().name());
        if (result == CheckinData.CurrencyRewardResult.OVERFLOW) return PointResult.rejected(Status.CURRENCY_OVERFLOW);
        SkillTreeData.Progress updated = new SkillTreeData.Progress(progress.level(), progress.currentXp(), progress.totalXp(),
                progress.availablePoints() - 1, progress.attributePoints(), progress.skillPoints(), progress.rewardPoints() + 1,
                progress.masteryPoints(), progress.unlockedSkills(), progress.overflowXp(), progress.dailyXp(), progress.dailyEpochDay(),
                progress.ultimateCooldownUntilEpochMillis());
        data.replace(player.getUUID(), target.get().id(), updated);
        return new PointResult(Status.GRANTED, updated);
    }

    /** Stores one skill point for a future mastery exchange without increasing combat attributes. */
    public synchronized PointResult reserveMastery(ServerPlayer player, String treeId) {
        Optional<SkillTreeConfig.TreeDefinition> target = config.tree(treeId);
        if (player == null || target.isEmpty()) return PointResult.rejected(target.isEmpty() ? Status.UNKNOWN_TREE : Status.INVALID_REQUEST);
        SkillTreeData data = SkillTreeData.get(player);
        SkillTreeData.Progress progress = data.progress(player.getUUID(), target.get().id());
        if (progress.availablePoints() < 1) return PointResult.rejected(Status.NO_POINTS);
        SkillTreeData.Progress updated = new SkillTreeData.Progress(progress.level(), progress.currentXp(), progress.totalXp(),
                progress.availablePoints() - 1, progress.attributePoints(), progress.skillPoints(), progress.rewardPoints(),
                progress.masteryPoints() + 1, progress.unlockedSkills(), progress.overflowXp(), progress.dailyXp(), progress.dailyEpochDay(),
                progress.ultimateCooldownUntilEpochMillis());
        data.replace(player.getUUID(), target.get().id(), updated);
        return new PointResult(Status.GRANTED, updated);
    }

    public long xpRequired(SkillTreeConfig.TreeDefinition tree, int currentLevel) {
        int level = Math.max(0, currentLevel);
        SkillTreeConfig.Settings settings = config.settings();
        double curve = settings.xpBase() + (double) level * settings.xpLinear()
                + (double) level * (double) level * settings.xpQuadratic();
        double result = curve * tree.multiplierForLevel(Math.max(1, level + 1));
        return result >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(1L, (long) Math.ceil(result));
    }

    public double attributeBonus(SkillTreeData.Progress progress) {
        if (progress == null) return 0.0D;
        if (mcmmoModule.enabled()) return 0.0D;
        double levelPart = config.settings().baseAttributeCap() * Math.min(progress.level(), config.settings().maxLevel())
                / config.settings().maxLevel();
        double pointsPart = Math.min(config.settings().pointAttributeCap(),
                progress.attributePoints() * config.settings().pointAttributeBonus());
        return Math.min(config.settings().baseAttributeCap() + config.settings().pointAttributeCap(), levelPart + pointsPart);
    }

    /**
     * Bounded survival source: checks a player's displacement every five seconds. The regular
     * source cooldown and daily cap still apply, so passive movement cannot bypass XP limits.
     */
    public synchronized void tick(MinecraftServer server) {
        if (server == null) return;
        flushPendingXpTransactions(server, false);
        flushMilestoneAnnouncements(server);
        if (server.getTickCount() % 20 == 0) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                healActiveField(player);
            }
        }
        if (server.getTickCount() % 100 != 0) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            LastPosition previous = lastPositions.put(player.getUUID(), new LastPosition(player.getX(), player.getY(), player.getZ()));
            if (previous == null) continue;
            double dx = player.getX() - previous.x();
            double dy = player.getY() - previous.y();
            double dz = player.getZ() - previous.z();
            if (dx * dx + dy * dy + dz * dz >= 64.0D) {
                String operation = "move:" + player.getUUID() + ":" + server.getTickCount();
                String movementSkill = mcmmoModule.enabled() ? "acrobatics" : "exploration";
                grantSkillXp(player, SkillXpEvent.of(player.getUUID(), movementSkill, SkillXpSource.SURVIVAL,
                        10L, operation + ":" + movementSkill, "movement", operation));
                settleSurvivalPassive(player, operation);
            }
        }
    }

    /** Healing field is intentionally server-only and small: no client side world mutation or instant burst heal. */
    private void healActiveField(ServerPlayer healer) {
        // Support/Healing is a professional-tree ability. The mcMMO compatibility set currently
        // exposes Alchemy instead, so never reinterpret an Alchemy cooldown as a healing field.
        if (mcmmoModule.enabled()) return;
        SkillTreeData.Progress progress = progress(healer, "healing");
        int level = skillLevel(progress, "active");
        if (level <= 0 || activeRemainingSeconds(healer, "healing") <= 0L) return;
        double radius = 4D + (level - 1) * (3D / 9D);
        for (ServerPlayer target : healer.level().getEntitiesOfClass(ServerPlayer.class, healer.getBoundingBox().inflate(radius))) {
            if (target.getHealth() < target.getMaxHealth()) target.heal(0.5F + level * 0.1F);
            if (target.getHealth() / target.getMaxHealth() <= 0.35F) {
                target.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 40, 0, false, false, true), healer);
            }
        }
    }

    public synchronized void forget(ServerPlayer player) {
        if (player != null) {
            lastPositions.remove(player.getUUID());
            craftOperationSequences.remove(player.getUUID());
        }
    }

    /** Group-commits dirty XP journal/progress SavedData at most once per second on the main thread. */
    public synchronized boolean flushPendingXpTransactions(MinecraftServer server, boolean force) {
        if (server == null || !xpTransactionFlushPending) return true;
        long tick = server.getTickCount();
        if (!force && tick - lastXpTransactionFlushTick < 20L) return true;
        try {
            SkillXpTransactionData.get(server).flush(server);
            xpTransactionFlushPending = false;
            lastXpTransactionFlushTick = tick;
            return true;
        } catch (RuntimeException exception) {
            OperationalErrorReporter.global().warn(OperationalErrorReporter.Context
                    .forModule(ModuleId.SKILLS, "xp_transaction_flush")
                    .withState("PENDING")
                    .withParameters(Map.of("pending", "true"))
                    .withRecoveryAction("retain_transactions_and_retry_next_checkpoint"), exception);
            return false;
        }
    }

    public void refreshAttributes(ServerPlayer player) {
        if (player == null) return;
        for (SkillAttribute attribute : SkillAttribute.values()) {
            AttributeInstance instance = player.getAttribute(attribute.holder());
            if (instance == null) continue;
            for (AttributeModifier modifier : java.util.List.copyOf(instance.getModifiers())) {
                Identifier id = modifier.id();
                if (ModMindEntry.MOD_ID.equals(id.getNamespace()) && id.getPath().startsWith("skill_tree/")) {
                    instance.removeModifier(id);
                }
            }
        }
        SkillTreeData data = SkillTreeData.get(player);
        for (SkillTreeConfig.TreeDefinition tree : config.trees()) {
            AttributeInstance instance = player.getAttribute(tree.attribute().holder());
            if (instance == null) continue;
            Identifier modifierId = modifierId(tree.id());
            instance.removeModifier(modifierId);
            double bonus = attributeBonus(data.progress(player.getUUID(), tree.id()));
            if (bonus > 0.0D) instance.addOrUpdateTransientModifier(new AttributeModifier(modifierId, bonus,
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
        if (player.getHealth() > player.getMaxHealth()) player.setHealth(player.getMaxHealth());
    }

    public void refreshAll(MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) refreshAttributes(player);
    }

    public void removeAll(MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (SkillTreeConfig.TreeDefinition tree : config.trees()) {
                AttributeInstance instance = player.getAttribute(tree.attribute().holder());
                if (instance != null) instance.removeModifier(modifierId(tree.id()));
            }
        }
    }

    private long applyTitleBonus(ServerPlayer player, long raw) {
        TitleConfig titles = ModMindEntry.titleConfig();
        if (!titles.effectsEnabled(player.getUUID())) return raw;
        double bonus = 0.0D;
        for (TitleEffectConfig.EffectDefinition effect : titles.selectedTitle(player.getUUID())
                .map(title -> titles.effectsFor(title, ModMindEntry.titleEffectConfig())).orElse(java.util.List.of())) {
            if (effect.type() == TitleEffectConfig.EffectType.SKILL_XP) bonus += effect.amount();
        }
        bonus = Math.min(config.settings().maxTitleXpBonus(), Math.max(0.0D, bonus));
        double result = raw * (1.0D + bonus);
        return result >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(1L, Math.round(result));
    }

    /** Efficiency and yield specializations apply only to normal gameplay sources, never packages or commands. */
    private long applyPassiveXpBonus(SkillTreeConfig.TreeDefinition tree, SkillTreeData.Progress progress,
                                     long raw, SkillXpSource source) {
        if (mcmmoModule.enabled()) return raw;
        if (!source.rateLimited() || progress == null || tree.skills().size() < 2) return raw;
        if (tree.skills().size() == 2) {
            SkillTreeConfig.SkillDefinition passive = tree.skills().stream().filter(s -> s.kind() == SkillTreeConfig.SkillKind.PASSIVE).findFirst().orElse(null);
            int level = passive == null ? 0 : skillLevel(progress, passive.id());
            if (level <= 0) return raw;
            double result = raw * (1.0D + Math.min(0.40D, level * 0.03D));
            return result >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(1L, Math.round(result));
        }
        double bonus = 0.0D;
        if (progress.unlockedSkills().contains(tree.skills().get(1).id())) bonus += 0.10D;
        if (progress.unlockedSkills().contains(tree.skills().get(2).id())) bonus += 0.15D;
        if (bonus <= 0.0D) return raw;
        double result = raw * (1.0D + bonus);
        return result >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(1L, Math.round(result));
    }

    /** Free foundation specializations become active as soon as their level condition is reached. */
    static Set<String> autoUnlockedSkills(SkillTreeConfig.TreeDefinition tree, Set<String> current, int level) {
        Set<String> unlocked = new HashSet<>(current == null ? Set.of() : current);
        for (SkillTreeConfig.SkillDefinition skill : tree.skills()) {
            if (skill.pointCost() == 0 && level >= skill.unlockLevel()) unlocked.add(skill.id());
        }
        return Set.copyOf(unlocked);
    }

    /**
     * Terminal specializations are short, source-triggered status effects. They intentionally do
     * not enter the tree's 50% persistent attribute calculation and use wall-clock cooldowns so a
     * server restart cannot reset them.
     */
    private SkillTreeData.Progress triggerUltimate(ServerPlayer player, SkillTreeData data,
                                                   SkillTreeConfig.TreeDefinition tree, SkillXpSource source,
                                                   SkillTreeData.Progress progress) {
        if (!source.rateLimited() || tree.skills().size() != 4) return progress;
        SkillTreeConfig.SkillDefinition ultimate = tree.skills().getLast();
        if (!progress.unlockedSkills().contains(ultimate.id())) return progress;
        long now = System.currentTimeMillis();
        if (now < progress.ultimateCooldownUntilEpochMillis()) return progress;
        player.addEffect(new MobEffectInstance(ultimateEffect(tree.id()), 20 * 10, 0, false, true, true), player);
        SkillTreeData.Progress updated = new SkillTreeData.Progress(progress.level(), progress.currentXp(), progress.totalXp(),
                progress.availablePoints(), progress.attributePoints(), progress.skillPoints(), progress.rewardPoints(),
                progress.masteryPoints(), progress.unlockedSkills(), progress.overflowXp(), progress.dailyXp(), progress.dailyEpochDay(),
                now + 60_000L);
        data.replace(player.getUUID(), tree.id(), updated);
        player.sendSystemMessage(Component.literal("[技能树] " + tree.display() + "终极专精已触发：持续 10 秒，冷却 60 秒。")
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        return updated;
    }

    private static Holder<MobEffect> ultimateEffect(String treeId) {
        return switch (treeId) {
            case "gathering", "crafting" -> MobEffects.HASTE;
            case "combat" -> MobEffects.STRENGTH;
            case "defense" -> MobEffects.RESISTANCE;
            case "hunting" -> MobEffects.LUCK;
            case "survival" -> MobEffects.REGENERATION;
            default -> MobEffects.SPEED;
        };
    }

    private static Holder<MobEffect> activeEffect(String treeId) {
        return switch (LegacySkillAdapter.canonical(treeId)) {
            case "swords", "axes", "archery" -> MobEffects.STRENGTH;
            case "acrobatics" -> MobEffects.RESISTANCE;
            case "healing", "herbalism" -> MobEffects.SATURATION;
            case "exploration", "fishing" -> MobEffects.SPEED;
            case "alchemy" -> MobEffects.LUCK;
            case "repair" -> MobEffects.HASTE;
            case "woodcutting", "mining", "excavation" -> MobEffects.HASTE;
            default -> MobEffects.HASTE;
        };
    }

    private int totalLevel(SkillTreeData data, UUID playerId) {
        int total = 0;
        for (SkillTreeConfig.TreeDefinition tree : config.trees()) {
            total = Math.addExact(total, Math.min(config.settings().maxLevel(), data.progress(playerId, tree.id()).level()));
        }
        return total;
    }

    private void sendLevelNotices(ServerPlayer player, SkillTreeConfig.TreeDefinition tree,
                                  SkillTreeData.Progress before, SkillTreeData.Progress after) {
        for (int displayedLevel = before.level() + 1; displayedLevel <= after.level(); displayedLevel++) {
            long displayedXp = displayedLevel == after.level() ? after.currentXp() : 0L;
            long required = displayedLevel >= config.settings().maxLevel() ? 0L : xpRequired(tree, displayedLevel);
            SkillTreeData.Progress displayedProgress = new SkillTreeData.Progress(displayedLevel, displayedXp, after.totalXp(),
                    after.availablePoints(), after.attributePoints(), after.skillPoints(), after.rewardPoints(), after.masteryPoints(),
                    after.unlockedSkills(), after.overflowXp(), after.dailyXp(), after.dailyEpochDay(),
                    after.ultimateCooldownUntilEpochMillis());
            MutableComponent message = Component.literal("[技能树] " + tree.display() + "技能提升至 Lv." + displayedLevel)
                    .withStyle(ChatFormatting.AQUA)
                    .append(Component.literal("\n经验：" + displayedXp + " / " + required).withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("\n" + tree.display() + "属性加成：+" + percent(attributeBonus(displayedProgress)))
                            .withStyle(ChatFormatting.GOLD));
            String milestone = switch (displayedLevel) {
                case 100 -> "阶段达成";
                case 500, 1000, 1500, 2000 -> milestoneText(displayedLevel);
                default -> "";
            };
            if (!milestone.isBlank()) message = message.append(Component.literal("\n" + milestone).withStyle(ChatFormatting.GREEN));
            player.sendSystemMessage(message);
        }
    }

    private static String milestoneText(int level) {
        return switch (level) {
            case 500 -> "阶段达成：获得 1 个技能点";
            case 1000 -> "中期里程碑：获得 1 个技能点";
            case 1500 -> "高阶技能强化资格：获得 1 个技能点";
            case 2000 -> "技能树满级：精通已开启，获得 1 个技能点";
            default -> "";
        };
    }

    private void queueMilestoneAnnouncement(ServerPlayer player, SkillTreeData data, SkillTreeConfig.TreeDefinition tree,
                                            int treeBefore, int treeAfter, int totalBefore, int totalAfter) {
        SkillTreeConfig.AnnouncementSettings settings = config.settings().announcements();
        if (!settings.enabled()) return;
        int treeMilestone = highestNewHundred(treeBefore, treeAfter, settings.minimumLevel());
        int totalMilestone = highestNewHundred(totalBefore, totalAfter, settings.minimumLevel());
        if (treeMilestone == 0 && totalMilestone == 0) return;
        SkillTreeData.AnnouncementState previous = data.announcementState(player.getUUID());
        String pendingTree = previous.pendingTreeId();
        int pendingTreeLevel = previous.pendingTreeLevel();
        if (treeMilestone > pendingTreeLevel) {
            pendingTree = tree.id();
            pendingTreeLevel = treeMilestone;
        }
        SkillTreeData.AnnouncementState queued = new SkillTreeData.AnnouncementState(previous.lastBroadcastAt(),
                pendingTree, pendingTreeLevel, Math.max(previous.pendingTotalLevel(), totalMilestone));
        data.replaceAnnouncementState(player.getUUID(), queued);
        flushMilestoneAnnouncement(player, data, settings);
    }

    private void flushMilestoneAnnouncements(MinecraftServer server) {
        SkillTreeConfig.AnnouncementSettings settings = config.settings().announcements();
        if (!settings.enabled()) return;
        SkillTreeData data = SkillTreeData.get(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            flushMilestoneAnnouncement(player, data, settings);
        }
    }

    private void flushMilestoneAnnouncement(ServerPlayer player, SkillTreeData data,
                                             SkillTreeConfig.AnnouncementSettings settings) {
        SkillTreeData.AnnouncementState state = data.announcementState(player.getUUID());
        if (!state.hasPending()) return;
        long now = System.currentTimeMillis();
        long cooldownMillis = settings.cooldownSeconds() * 1_000L;
        if (now - state.lastBroadcastAt() < cooldownMillis) return;
        String treeDisplay = config.tree(state.pendingTreeId()).map(SkillTreeConfig.TreeDefinition::display)
                .orElse(state.pendingTreeId());
        String text;
        if (state.pendingTreeLevel() > 0 && state.pendingTotalLevel() > 0) {
            text = "[技能里程碑] " + player.getGameProfile().name() + " 的【" + treeDisplay + "技能】达到 Lv."
                    + state.pendingTreeLevel() + "，总技能等级达到 Lv." + state.pendingTotalLevel() + "！";
        } else if (state.pendingTreeLevel() > 0) {
            text = "[技能里程碑] " + player.getGameProfile().name() + " 的【" + treeDisplay + "技能】达到 Lv."
                    + state.pendingTreeLevel() + "！";
        } else {
            text = "[总等级里程碑] " + player.getGameProfile().name() + " 的技能树总等级达到 Lv."
                    + state.pendingTotalLevel() + "！";
        }
        ChatFormatting color;
        try {
            color = ChatFormatting.valueOf(settings.color().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            color = ChatFormatting.GOLD;
        }
        Component message = Component.literal(text).withStyle(color);
        if (settings.channel().equals("action_bar")) {
            for (ServerPlayer recipient : player.level().getServer().getPlayerList().getPlayers()) {
                recipient.displayClientMessage(message, true);
            }
        } else {
            player.level().getServer().getPlayerList().broadcastSystemMessage(message, false);
        }
        data.replaceAnnouncementState(player.getUUID(), new SkillTreeData.AnnouncementState(now, "", 0, 0));
    }

    private static int highestNewHundred(int before, int after, int minimumLevel) {
        int prior = Math.max(0, before) / 100;
        int current = Math.max(0, after) / 100;
        int milestone = current > prior ? current * 100 : 0;
        return milestone >= minimumLevel ? milestone : 0;
    }

    private static String percent(double value) { return String.format(java.util.Locale.ROOT, "%.1f%%", value * 100.0D); }

    private static Identifier modifierId(String treeId) { return Identifier.fromNamespaceAndPath(ModMindEntry.MOD_ID, "skill_tree/" + treeId); }
    private static String normalized(String id) { return id == null ? "" : id.trim().toLowerCase(java.util.Locale.ROOT); }
    private static long saturatedAdd(long left, long right) { return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right; }
    private static long multiplyXp(long value, double multiplier) {
        if (value <= 0L || !Double.isFinite(multiplier) || multiplier <= 0.0D) return 0L;
        double result = value * multiplier;
        return result >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(1L, Math.round(result));
    }

    public enum Status { GRANTED, INVALID_REQUEST, OPERATION_ID_REQUIRED, UNKNOWN_TREE, UNKNOWN_SKILL, SOURCE_NOT_ALLOWED, RATE_LIMITED, DAILY_LIMIT_REACHED, NO_POINTS, ATTRIBUTE_CAP_REACHED, LEVEL_REQUIRED, ALREADY_UNLOCKED, REWARD_DISABLED, CURRENCY_OVERFLOW, SKILL_CAP_REACHED, ACTIVE_COOLDOWN, RESET_COOLDOWN, RESET_RESOURCE_REQUIRED, BLOCKED_BY_COLLISION, DUPLICATE_OPERATION, PERSISTENCE_UNAVAILABLE, TRANSACTION_FAILED, ENGINE_MANAGED }
    public record XpResult(Status status, long acceptedXp, int levelsGained, SkillTreeData.Progress progress, long dailyRemaining) {
        static XpResult rejected(Status status) { return new XpResult(status, 0L, 0, SkillTreeData.Progress.empty(), 0L); }
        public boolean granted() { return status == Status.GRANTED; }
    }
    public record PointResult(Status status, SkillTreeData.Progress progress) {
        static PointResult rejected(Status status) { return new PointResult(status, SkillTreeData.Progress.empty()); }
        public boolean granted() { return status == Status.GRANTED; }
    }
    private record RateLimitKey(UUID playerId, String treeId, SkillXpSource source) { }
    private record LastPosition(double x, double y, double z) { }
}
