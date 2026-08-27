package dev.modmind.omnitools.statistics;

import dev.modmind.omnitools.AchievementConfig;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.stats.Stats;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatisticQueryTest {
    @BeforeAll
    static void bootstrapVanillaRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void aggregatesMultipleTargetsWithoutChangingVanillaValues() {
        StatsCounter stats = new StatsCounter();
        stats.setValue(null, Stats.BLOCK_MINED.get(Blocks.STONE), 11);
        stats.setValue(null, Stats.BLOCK_MINED.get(Blocks.DEEPSLATE), 7);
        List<String> targets = List.of("minecraft:stone", "minecraft:deepslate");

        assertEquals(18L, new StatisticQuery(AchievementConfig.RequirementType.BLOCK_MINED, targets,
                StatisticQuery.Aggregation.SUM, "count").value(stats));
        assertEquals(7L, new StatisticQuery(AchievementConfig.RequirementType.BLOCK_MINED, targets,
                StatisticQuery.Aggregation.MIN, "count").value(stats));
        assertEquals(11L, new StatisticQuery(AchievementConfig.RequirementType.BLOCK_MINED, targets,
                StatisticQuery.Aggregation.MAX, "count").value(stats));
    }

    @Test
    void formatsCustomDistanceOnlyForPresentation() {
        StatisticQuery query = new StatisticQuery(AchievementConfig.RequirementType.CUSTOM,
                List.of("minecraft:fall_one_cm"), StatisticQuery.Aggregation.SUM, "blocks");

        assertEquals("2 blocks", query.format(250L));
    }
}
