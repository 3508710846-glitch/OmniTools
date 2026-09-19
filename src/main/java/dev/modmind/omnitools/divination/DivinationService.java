package dev.modmind.omnitools.divination;

import dev.modmind.omnitools.CheckinData;
import dev.modmind.omnitools.ModMindEntry;
import dev.modmind.omnitools.ServerText;
import dev.modmind.omnitools.skills.SkillXpSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Server-authoritative daily draw, paid reroll, reading, reward and omen adapter. */
public final class DivinationService {
    private volatile DivinationConfig config = DivinationConfig.empty();

    public DivinationConfig config() { return config; }

    public void replace(DivinationConfig replacement) {
        config = replacement == null ? DivinationConfig.empty() : replacement;
    }

    public DivinationData.Reading current(ServerPlayer player) {
        if (player == null) return null;
        return DivinationData.get(player.level().getServer()).current(player.getUUID(), day(player.level().getServer()));
    }

    public List<DivinationData.Reading> history(ServerPlayer player, int limit) {
        if (player == null) return List.of();
        return DivinationData.get(player.level().getServer()).history(player.getUUID(), limit);
    }

    /** Retained for command and extension compatibility. Themes are now assigned by the sign pool. */
    public DrawOutcome draw(ServerPlayer player, DivinationConfig.Theme ignoredTheme) {
        return draw(player, DivinationData.DrawMode.FREE);
    }

    public DrawOutcome draw(ServerPlayer player) {
        return draw(player, DivinationData.DrawMode.FREE);
    }

    public DrawOutcome reroll(ServerPlayer player) {
        return draw(player, DivinationData.DrawMode.REROLL);
    }

    private DrawOutcome draw(ServerPlayer player, DivinationData.DrawMode mode) {
        if (player == null || !config.settings().enabled()) {
            return new DrawOutcome(DivinationData.DrawStatus.INVALID_REQUEST, null, false, false, 0L);
        }
        MinecraftServer server = player.level().getServer();
        DivinationData.Reading current = current(player);
        long cooldownMillis = config.settings().drawCooldownSeconds() * 1_000L;
        if (current != null && cooldownMillis > 0L && System.currentTimeMillis() - current.drawnAtMillis() < cooldownMillis) {
            return new DrawOutcome(DivinationData.DrawStatus.COOLDOWN, current, false, mode == DivinationData.DrawMode.REROLL, 0L);
        }
        long cost = mode == DivinationData.DrawMode.REROLL ? config.settings().rerollCost() : 0L;
        if (mode == DivinationData.DrawMode.REROLL) {
            if (!config.settings().rerollsEnabled() || current == null || !current.resolved()) {
                return new DrawOutcome(DivinationData.DrawStatus.NO_READING, current, false, true, cost);
            }
            if (CheckinData.get(server).getBalance(player.getUUID()) < cost) {
                return new DrawOutcome(DivinationData.DrawStatus.INSUFFICIENT_CURRENCY, current, false, true, cost);
            }
        }
        DivinationConfig.SignDefinition sign = chooseSign(player);
        if (sign == null) return new DrawOutcome(DivinationData.DrawStatus.INVALID_REQUEST, null, false, false, 0L);
        long currentDay = day(server);
        String operationId = "divination:" + (mode == DivinationData.DrawMode.REROLL ? "reroll:" : "draw:")
                + player.getUUID() + ":" + UUID.randomUUID();
        DivinationData.Reading reading = new DivinationData.Reading(currentDay, sign.id(), sign.rank(),
                resolveTheme(player, sign.theme()), System.currentTimeMillis(), false, false,
                0, 0, true, sign.skillXpModifier(), 0.0D, false, sign.currencyReward(), "");
        if (mode == DivinationData.DrawMode.REROLL) {
            DivinationData data = DivinationData.get(server);
            DivinationData.RerollPreparation prepared = data.prepareReroll(player.getUUID(), player.getGameProfile().name(),
                    currentDay, operationId, config.settings(), reading);
            if (!prepared.prepared()) {
                return new DrawOutcome(prepared.status(), prepared.reading(), false, true, cost);
            }
            // The replacement snapshot reaches disk before the wallet changes.  Startup can then
            // deterministically commit it iff the CheckinData debit marker is present.
            try {
                data.flush(server);
            } catch (RuntimeException exception) {
                data.rollbackPreparedReroll(player.getUUID(), operationId, "prepare_flush_failed");
                return new DrawOutcome(DivinationData.DrawStatus.INVALID_REQUEST, current, false, true, cost);
            }
            CheckinData.DivinationRerollChargeResult charge = CheckinData.get(server).chargeDivinationReroll(
                    player.getUUID(), operationId, cost, player.getGameProfile().name());
            if (charge == CheckinData.DivinationRerollChargeResult.INSUFFICIENT_CURRENCY) {
                data.rollbackPreparedReroll(player.getUUID(), operationId, "insufficient_currency");
                data.flush(server);
                return new DrawOutcome(DivinationData.DrawStatus.INSUFFICIENT_CURRENCY, current, false, true, cost);
            }
            // This flush persists the debit while the divination operation remains PREPARED.
            try {
                data.flush(server);
            } catch (RuntimeException exception) {
                // The debit may already be durable even when the synchronous save reports a
                // failure. Leave PREPARED evidence intact for deterministic startup recovery.
                return new DrawOutcome(DivinationData.DrawStatus.INVALID_REQUEST, current, false, true, cost);
            }
            DivinationData.DrawResult committed = data.commitPreparedReroll(player.getUUID(),
                    player.getGameProfile().name(), operationId);
            if (!committed.changed()) {
                return new DrawOutcome(committed.status(), committed.reading(), false, true, cost);
            }
            try {
                data.flush(server);
            } catch (RuntimeException exception) {
                return new DrawOutcome(DivinationData.DrawStatus.INVALID_REQUEST, committed.reading(), false, true, cost);
            }
            return new DrawOutcome(committed.status(), committed.reading(), true, true, cost);
        }
        DivinationData.DrawResult result = DivinationData.get(server).draw(player.getUUID(), player.getGameProfile().name(),
                currentDay, operationId, config.settings(), reading, mode);
        if (!result.changed()) return new DrawOutcome(result.status(), result.reading(), false, false, cost);
        return new DrawOutcome(result.status(), result.reading(), true, mode == DivinationData.DrawMode.REROLL, cost);
    }

    public InterpretOutcome interpret(ServerPlayer player, boolean deep, DivinationConfig.Theme replacementTheme) {
        if (player == null || !config.settings().enabled()) {
            return new InterpretOutcome(DivinationData.InterpretStatus.INVALID_REQUEST, null, false, 0L);
        }
        MinecraftServer server = player.level().getServer();
        String operationId = "divination:interpret:" + player.getUUID() + ":" + UUID.randomUUID();
        DivinationData data = DivinationData.get(server);
        DivinationData.InterpretResult result = data.interpret(player.getUUID(),
                player.getGameProfile().name(), day(server), operationId, false, config.settings(), null);
        DivinationData.Reading reading = result.reading();
        if (reading == null) return new InterpretOutcome(result.status(), null, false, 0L);
        if (result.changed()) {
            // A resolved sign is the durable promise of its idempotent currency reward.  Persist
            // that promise before the wallet mutation, so startup/player retry can repair it.
            try {
                data.flush(server);
            } catch (RuntimeException exception) {
                return new InterpretOutcome(DivinationData.InterpretStatus.INVALID_REQUEST, reading, false, 0L);
            }
        } else if (result.status() != DivinationData.InterpretStatus.ALREADY_INTERPRETED) {
            return new InterpretOutcome(result.status(), reading, false, 0L);
        }
        long rewarded = 0L;
        boolean creditedNow = false;
        if (reading.currencyReward() > 0L) {
            CheckinData.CurrencyRewardResult grant = CheckinData.get(server).applyRewardCurrency(player.getUUID(),
                    reading.rewardOperationId(), "divination_sign", reading.currencyReward(), player.getGameProfile().name());
            if (grant == CheckinData.CurrencyRewardResult.APPLIED || grant == CheckinData.CurrencyRewardResult.ALREADY_APPLIED) {
                rewarded = reading.currencyReward();
                creditedNow = grant == CheckinData.CurrencyRewardResult.APPLIED;
            }
            try {
                data.flush(server);
            } catch (RuntimeException exception) {
                return new InterpretOutcome(DivinationData.InterpretStatus.INVALID_REQUEST, reading, false, 0L);
            }
        }
        if (result.changed() && config.settings().broadcastExtremes() && reading.rank().extreme()) {
            server.getPlayerList().broadcastSystemMessage(ServerText.translatable(
                    "message.omnitools.divination.broadcast", player.getName(), ServerText.translatable("gui.omnitools.divination.rank." + reading.rank().serializedName())), false);
        }
        return new InterpretOutcome(result.status(), reading, result.changed() || creditedNow, rewarded);
    }

    /**
     * Applies the sign's additive modifier only to normal, rate-limited skill sources.
     * Title bonuses remain calculated by the skill module; rewards, packages and commands bypass the omen.
     */
    public long applySkillXpOmen(ServerPlayer player, String skillId, SkillXpSource source, long input) {
        if (player == null || source == null || !source.rateLimited() || input <= 0L || !config.settings().enabled()) return input;
        DivinationConfig.Theme theme = themeForSkill(skillId);
        if (theme == null) return input;
        DivinationData.Reading reading = current(player);
        if (reading == null || reading.theme() != theme) return input;
        double modifier = reading.activeBonus(config.settings().omenBuffCap());
        if (modifier == 0.0D) return input;
        double scaled = input * (1.0D + modifier);
        if (!Double.isFinite(scaled) || scaled >= Long.MAX_VALUE) return Long.MAX_VALUE;
        return Math.max(1L, Math.round(scaled));
    }

    /** Compatibility no-op: the compact system exposes a guidance text instead of a mandatory relief task. */
    public DivinationData.ReliefResult recordSuccessfulSkillAction(ServerPlayer player, String skillId,
                                                                     SkillXpSource source, String operationId) {
        return DivinationData.ReliefResult.none();
    }

    public DivinationData.RecoveryReport reconcileStartup(MinecraftServer server) {
        if (server == null) return new DivinationData.RecoveryReport(0, 0);
        DivinationData data = DivinationData.get(server);
        DivinationData.RecoveryReport report = data.reconcileStartup(server);
        int rewards = data.reconcileCurrencyRewards(server);
        if (report.committed() > 0 || report.rolledBack() > 0 || rewards > 0) data.flush(server);
        return report;
    }

    public static DivinationConfig.Theme themeForSkill(String skillId) {
        String normalized = skillId == null ? "" : skillId.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "mining", "excavation", "woodcutting", "herbalism", "miner", "lumberjack", "farmer" -> DivinationConfig.Theme.MINING;
            case "swords", "axes", "archery", "warrior", "hunter" -> DivinationConfig.Theme.COMBAT;
            case "acrobatics", "fishing", "guardian", "survival" -> DivinationConfig.Theme.EXPLORATION;
            default -> null;
        };
    }

    private DivinationConfig.SignDefinition chooseSign(ServerPlayer player) {
        List<DivinationConfig.SignDefinition> signs = config.signs();
        long totalWeight = signs.stream().mapToLong(DivinationConfig.SignDefinition::weight).sum();
        if (totalWeight <= 0L) return null;
        long target = Math.floorMod(player.getRandom().nextLong(), totalWeight);
        long cursor = 0L;
        for (DivinationConfig.SignDefinition sign : signs) {
            cursor += sign.weight();
            if (target < cursor) return sign;
        }
        return signs.getLast();
    }

    private static DivinationConfig.Theme resolveTheme(ServerPlayer player, DivinationConfig.Theme configured) {
        if (configured != null && configured != DivinationConfig.Theme.RANDOM) return configured;
        DivinationConfig.Theme[] themes = {
                DivinationConfig.Theme.MINING, DivinationConfig.Theme.COMBAT, DivinationConfig.Theme.EXPLORATION
        };
        return themes[player.getRandom().nextInt(themes.length)];
    }

    private static long day(MinecraftServer server) {
        return LocalDate.now(ModMindEntry.configuredZone()).toEpochDay();
    }

    public record DrawOutcome(DivinationData.DrawStatus status, DivinationData.Reading reading,
                              boolean changed, boolean paid, long cost) { }
    public record InterpretOutcome(DivinationData.InterpretStatus status, DivinationData.Reading reading,
                                   boolean changed, long currencyReward) { }
}
