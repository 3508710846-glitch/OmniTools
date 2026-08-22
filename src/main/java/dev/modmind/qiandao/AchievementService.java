package dev.modmind.qiandao;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import dev.modmind.qiandao.config.ModuleId;

/** Server-side achievement progression, unlock checks, and one-time reward claims. */
public final class AchievementService {
    public static final int CHECK_INTERVAL_TICKS = 10;

    private AchievementConfig config;
    private int revision;

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
        }
        checkAll(server);
    }

    public void checkAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            check(player);
        }
    }

    /** Checks all configured achievements for a player and permanently records newly met targets. */
    public int check(ServerPlayer player) {
        return checkInternal(player, true);
    }

    private int checkInternal(ServerPlayer player, boolean announce) {
        AchievementConfig snapshot = config();
        AchievementData data = AchievementData.get(player);
        int newlyUnlocked = 0;
        for (AchievementConfig.AchievementDefinition achievement : snapshot.achievements()) {
            if (data.isUnlocked(player.getUUID(), achievement.id()) || !achievement.complete(player)) {
                continue;
            }
            if (data.unlock(player.getUUID(), achievement.id())) {
                newlyUnlocked++;
                if (announce) {
                    player.displayClientMessage(Component.translatable(
                            "message.qiandao.achievement.unlocked", achievement.display()), true);
                }
            }
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
        if (!data.isUnlocked(player.getUUID(), achievement.id()) && !achievement.complete(player)) {
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
        return new ClaimResult(ClaimStatus.CLAIMED, balance, grantedTitles);
    }

    public State state(ServerPlayer player, AchievementConfig.AchievementDefinition achievement) {
        AchievementData data = AchievementData.get(player);
        if (data.isClaimed(player.getUUID(), achievement.id())) {
            return State.CLAIMED;
        }
        if (data.isUnlocked(player.getUUID(), achievement.id()) || achievement.complete(player)) {
            return State.CLAIMABLE;
        }
        return State.IN_PROGRESS;
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

    /** Hashes only server-owned state used by an open menu, avoiding a full item rebuild every tick. */
    public int stateHash(ServerPlayer player) {
        int hash = revision();
        for (AchievementConfig.AchievementDefinition achievement : config().achievements()) {
            hash = 31 * hash + achievement.id().hashCode();
            hash = 31 * hash + state(player, achievement).ordinal();
            for (AchievementConfig.Requirement requirement : achievement.requirements()) {
                hash = 31 * hash + Long.hashCode(requirement.current(player));
            }
        }
        return hash;
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
}
