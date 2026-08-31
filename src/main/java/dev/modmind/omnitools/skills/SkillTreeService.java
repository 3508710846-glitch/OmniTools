package dev.modmind.omnitools.skills;

import dev.modmind.omnitools.ModMindEntry;
import dev.modmind.omnitools.TitleConfig;
import dev.modmind.omnitools.TitleEffectConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.stats.Stats;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Server-authoritative skill progression and native-attribute synchronization. */
public final class SkillTreeService {
    private volatile SkillTreeConfig config;
    private volatile long revision;
    private final Map<RateLimitKey, Long> latestSourceTick = new HashMap<>();
    private final Map<UUID, LastPosition> lastPositions = new HashMap<>();
    private final Map<UUID, Long> lastCraftedTotals = new HashMap<>();

    public SkillTreeService(SkillTreeConfig config) {
        this.config = config == null ? SkillTreeConfig.empty() : config;
    }

    public void replace(SkillTreeConfig config) {
        this.config = config == null ? SkillTreeConfig.empty() : config;
        revision++;
        latestSourceTick.clear();
        lastPositions.clear();
        lastCraftedTotals.clear();
    }

    public SkillTreeConfig config() { return config; }
    public long revision() { return revision; }

    public SkillTreeData.Progress progress(ServerPlayer player, String treeId) {
        return SkillTreeData.get(player).progress(player.getUUID(), normalized(treeId));
    }

    /** The only supported XP mutation path for behavior events, rewards, commands and future modules. */
    public synchronized XpResult addSkillXp(ServerPlayer player, String treeId, long requestedXp, SkillXpSource source) {
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
        long epochDay = LocalDate.now(ModMindEntry.configuredZone()).toEpochDay();
        long dailyXp = before.dailyEpochDay() == epochDay ? before.dailyXp() : 0L;
        long capacity = Math.max(0L, config.settings().maxDailyXp() - dailyXp);
        if (capacity == 0L) return XpResult.rejected(Status.DAILY_LIMIT_REACHED);
        long boosted = applyTitleBonus(player, requestedXp);
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
        int earnedPoints = level / config.settings().pointsEveryLevels();
        int alreadyAccounted = before.availablePoints() + before.attributePoints() + before.skillPoints();
        int newPoints = Math.max(0, earnedPoints - alreadyAccounted);
        SkillTreeData.Progress after = new SkillTreeData.Progress(level, currentXp, saturatedAdd(before.totalXp(), accepted),
                before.availablePoints() + newPoints, before.attributePoints(), before.skillPoints(), before.unlockedSkills(),
                overflowXp, saturatedAdd(dailyXp, accepted), epochDay);
        data.replace(player.getUUID(), tree.id(), after);
        refreshAttributes(player);
        return new XpResult(Status.GRANTED, accepted, levelsGained, after, Math.max(0L, config.settings().maxDailyXp() - after.dailyXp()));
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
                progress.availablePoints() - 1, progress.attributePoints() + 1, progress.skillPoints(), progress.unlockedSkills(),
                progress.overflowXp(), progress.dailyXp(), progress.dailyEpochDay());
        data.replace(player.getUUID(), target.get().id(), updated);
        refreshAttributes(player);
        return new PointResult(Status.GRANTED, updated);
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
                unlocked, progress.overflowXp(), progress.dailyXp(), progress.dailyEpochDay());
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
        if (server == null || server.getTickCount() % 100 != 0) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            LastPosition previous = lastPositions.put(player.getUUID(), new LastPosition(player.getX(), player.getY(), player.getZ()));
            if (previous == null) continue;
            double dx = player.getX() - previous.x();
            double dy = player.getY() - previous.y();
            double dz = player.getZ() - previous.z();
            if (dx * dx + dy * dy + dz * dz >= 64.0D) addSkillXp(player, "survival", 10L, SkillXpSource.SURVIVAL);
            long craftedTotal = craftedTotal(player);
            Long priorCraftedTotal = lastCraftedTotals.put(player.getUUID(), craftedTotal);
            if (priorCraftedTotal != null && craftedTotal > priorCraftedTotal) {
                addSkillXp(player, "crafting", Math.min(1_000L, (craftedTotal - priorCraftedTotal) * 5L), SkillXpSource.CRAFT);
            }
        }
    }

    public synchronized void forget(ServerPlayer player) {
        if (player != null) {
            lastPositions.remove(player.getUUID());
            lastCraftedTotals.remove(player.getUUID());
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

    private static long craftedTotal(ServerPlayer player) {
        long total = 0L;
        for (net.minecraft.world.item.Item item : BuiltInRegistries.ITEM) {
            int value = player.getStats().getValue(Stats.ITEM_CRAFTED.get(item));
            total = saturatedAdd(total, Math.max(0, value));
        }
        return total;
    }

    private static Identifier modifierId(String treeId) { return Identifier.fromNamespaceAndPath(ModMindEntry.MOD_ID, "skill_tree/" + treeId); }
    private static String normalized(String id) { return id == null ? "" : id.trim().toLowerCase(java.util.Locale.ROOT); }
    private static long saturatedAdd(long left, long right) { return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right; }

    public enum Status { GRANTED, INVALID_REQUEST, UNKNOWN_TREE, UNKNOWN_SKILL, SOURCE_NOT_ALLOWED, RATE_LIMITED, DAILY_LIMIT_REACHED, NO_POINTS, ATTRIBUTE_CAP_REACHED, LEVEL_REQUIRED, ALREADY_UNLOCKED }
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
