package dev.modmind.omnitools.skills;

import com.google.gson.JsonParseException;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkillTreeConfigTest {
    @Test
    void xpCurveUsesConfiguredFormulaAndStageMultiplier() {
        SkillTreeConfig.Settings settings = new SkillTreeConfig.Settings(2000, 500, 250_000L, 4,
                0.30D, 0.20D, 0.05D, 0.50D, 100L, 25L, 0.015D);
        SkillTreeConfig.TreeDefinition tree = tree();
        SkillTreeService service = new SkillTreeService(new SkillTreeConfig(1, settings, List.of(tree)));

        assertEquals(100L, service.xpRequired(tree, 0));
        assertEquals(20_438L, service.xpRequired(tree, 500));
    }

    @Test
    void treeRequiresExactlyFourSkillDefinitions() {
        assertThrows(JsonParseException.class, () -> new SkillTreeConfig.TreeDefinition("test", "测试", "minecraft:stone",
                Items.STONE, SkillAttribute.ATTACK_DAMAGE, Set.of(SkillXpSource.COMMAND),
                List.of(new SkillTreeConfig.LevelMultiplier(1, 1.0D)), List.of()));
    }

    private static SkillTreeConfig.TreeDefinition tree() {
        return new SkillTreeConfig.TreeDefinition("test", "测试", "minecraft:stone", Items.STONE,
                SkillAttribute.ATTACK_DAMAGE, Set.of(SkillXpSource.COMMAND),
                List.of(new SkillTreeConfig.LevelMultiplier(1, 1.0D), new SkillTreeConfig.LevelMultiplier(501, 1.25D)),
                List.of(skill("first", 1, 0), skill("second", 250, 1), skill("third", 750, 1), skill("fourth", 1500, 2)));
    }

    private static SkillTreeConfig.SkillDefinition skill(String id, int level, int cost) {
        return new SkillTreeConfig.SkillDefinition(id, id, "test", level, cost);
    }
}
