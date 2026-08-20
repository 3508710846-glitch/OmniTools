package dev.modmind.qiandao;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Delivers rewards after a server-authoritative sign-in has succeeded. */
public final class CheckinRewardService {
    private final CheckinRewardConfig config;

    private CheckinRewardService(CheckinRewardConfig config) {
        this.config = config;
    }

    public static CheckinRewardService load() {
        return new CheckinRewardService(CheckinRewardConfig.load());
    }

    public void grant(ServerPlayer player, int streakDays) {
        List<CheckinRewardConfig.RewardEntry> rewards = new ArrayList<>(config.dailyRewards());
        List<CheckinRewardConfig.RewardEntry> streakRewards = config.streakRewards(streakDays);
        rewards.addAll(streakRewards);
        if (rewards.isEmpty()) {
            return;
        }

        int delivered = 0;
        for (CheckinRewardConfig.RewardEntry reward : rewards) {
            if (reward.isItem()) {
                delivered += grantItem(player, reward);
            } else if (runCommand(player, reward.command())) {
                delivered++;
            }
        }
        if (delivered > 0) {
            player.sendSystemMessage(Component.translatable(
                    streakRewards.isEmpty() ? "message.qiandao.reward.daily" : "message.qiandao.reward.streak",
                    streakDays));
        }
    }

    private static int grantItem(ServerPlayer player, CheckinRewardConfig.RewardEntry reward) {
        Identifier itemId = Identifier.tryParse(reward.itemId());
        if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) {
            System.err.println("[qiandao] Skipped unknown reward item: " + reward.itemId());
            return 0;
        }

        Item item = BuiltInRegistries.ITEM.getValue(itemId);
        int remaining = reward.count();
        int given = 0;
        while (remaining > 0) {
            int stackCount = Math.min(remaining, item.getDefaultInstance().getMaxStackSize());
            ItemStack stack = new ItemStack(item, stackCount);
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
            remaining -= stackCount;
            given++;
        }
        return given;
    }

    private static boolean runCommand(ServerPlayer player, String configuredCommand) {
        String command = configuredCommand.trim();
        if (command.isEmpty()) {
            return false;
        }
        command = command.replace("{player}", player.getGameProfile().name());
        boolean[] succeeded = {false};
        CommandSourceStack source = player.createCommandSourceStack()
                .withPermission(PermissionSet.ALL_PERMISSIONS)
                .withCallback((success, returnValue) -> succeeded[0] = success)
                .withSuppressedOutput();
        try {
            player.level().getServer().getCommands().performPrefixedCommand(source, command);
            if (succeeded[0]) {
                return true;
            }
            System.err.println("[qiandao] Reward command failed: " + command);
            return false;
        } catch (RuntimeException exception) {
            System.err.println("[qiandao] Failed to run reward command '" + command + "': "
                    + exception.getMessage());
            return false;
        }
    }
}
