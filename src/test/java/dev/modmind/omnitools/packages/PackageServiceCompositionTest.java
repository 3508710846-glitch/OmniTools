package dev.modmind.omnitools.packages;

import dev.modmind.omnitools.reward.RewardGrantService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;

class PackageServiceCompositionTest {
    @Test
    void composesPackageAndRewardServicesWithoutRecursiveConstruction() {
        AtomicReference<RewardGrantService> rewards = new AtomicReference<>();
        PackageService packages = assertDoesNotThrow(() -> new PackageService(rewards::get));
        RewardGrantService rewardService = assertDoesNotThrow(() -> new RewardGrantService(packages));

        rewards.set(rewardService);
        assertSame(rewardService, rewards.get());
    }
}
