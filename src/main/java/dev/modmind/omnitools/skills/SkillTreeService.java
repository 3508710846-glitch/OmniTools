package dev.modmind.omnitools.skills;

import dev.modmind.omnitools.ModMindEntry;
import dev.modmind.omnitools.CheckinData;
import dev.modmind.omnitools.TitleConfig;
import dev.modmind.omnitools.TitleEffectConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Holder;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.stats.Stats;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
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

    public int totalLevel(ServerPlayer player) {
        if (player == null) return 0;
        return totalLevel(SkillTreeData.get(player), player.getUUID());
    }

    public long ultimateCooldownRemainingSeconds(SkillTreeData.Progress progress) {
        if (progress == null) return 0L;
        long remaining = progress.ultimateCooldownUntilEpochMillis() - System.currentTimeMillis();
        return remaining <= 0L ? 0L : (remaining + 999L) / 1_000L;
    }

    /** The only supported XP mutation path for behavior events, rewards, commands and future modules. */
    public synchronized XpResult addSkillXp(ServerPlayer player, String treeId, long requestedXp, SkillXpSource source) {
        return addSkillXp(player, treeId, requestedXp, source, true);
    }

    /** Applies a source that may opt out of title XP bonuses, such as a configured paid package. */
    public synchronized XpResult addSkillXp(ServerPlayer player, String treeId, long requestedXp, SkillXpSource source,
                                            boolean applyTitleXpBonus) {
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
        int earnedPoints = level / config.settings().pointsEveryLevels();
        int alreadyAccounted = before.availablePoints() + before.attributePoints() + before.skillPoints()
                + before.rewardPoints() + before.masteryPoints();
        int newPoints = Math.max(0, earnedPoints - alreadyAccounted);
        Set<String> unlocked = autoUnlockedSkills(tree, before.unlockedSkills(), level);
        SkillTreeData.Progress after = new SkillTreeData.Progress(level, currentXp, saturatedAdd(before.totalXp(), accepted),
                before.availablePoints() + newPoints, before.attributePoints(), before.skillPoints(), before.rewardPoints(),
                before.masteryPoints(), unlocked, overflowXp, saturatedAdd(dailyXp, accepted), epochDay,
                before.ultimateCooldownUntilEpochMillis());
        data.replace(player.getUUID(), tree.id(), after);
        refreshAttributes(player);
        after = triggerUltimate(player, data, tree, source, after);
        if (levelsGained > 0) {
            sendLevelNotices(player, tree, before, after);
            queueMilestoneAnnouncement(player, data, tree, before.level(), after.level(), totalBefore,
                    totalLevel(data, player.getUUID()));
        }
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
                progress.availablePoints() - 1, progress.attributePoints() + 1, progress.skillPoints(), progress.rewardPoints(),
                progress.masteryPoints(), progress.unlockedSkills(),
                progress.overflowXp(), progress.dailyXp(), progress.dailyEpochDay(), progress.ultimateCooldownUntilEpochMillis());
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
        flushMilestoneAnnouncements(server);
        if (server.getTickCount() % 100 != 0) return;
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

    /** Efficiency and yield specializations apply only to normal gameplay sources, never packages or commands. */
    private long applyPassiveXpBonus(SkillTreeConfig.TreeDefinition tree, SkillTreeData.Progress progress,
                                    long raw, SkillXpSource source) {
        if (!source.rateLimited() || progress == null || tree.skills().size() < 3) return raw;
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
        if (!source.rateLimited() || tree.skills().size() != SkillTreeConfig.REQUIRED_SKILL_COUNT) return progress;
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

    private static long craftedTotal(ServerPlayer player) {
        long total = 0L;
        for (net.minecraft.world.item.Item item : BuiltInRegistries.ITEM) {
            int value = player.getStats().getValue(Stats.ITEM_CRAFTED.get(item));
            total = saturatedAdd(total, Math.max(0, value));
        }
        return total;
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

    public enum Status { GRANTED, INVALID_REQUEST, UNKNOWN_TREE, UNKNOWN_SKILL, SOURCE_NOT_ALLOWED, RATE_LIMITED, DAILY_LIMIT_REACHED, NO_POINTS, ATTRIBUTE_CAP_REACHED, LEVEL_REQUIRED, ALREADY_UNLOCKED, REWARD_DISABLED, CURRENCY_OVERFLOW }
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
