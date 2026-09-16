package dev.modmind.omnitools.skills;

import com.google.gson.JsonParseException;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkillTreeConfigTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        Bootstrap.bootStrap();
    }

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

    @Test
    void fixedProgressionRulesRejectConfigurationDrift() {
        assertThrows(JsonParseException.class, () -> new SkillTreeConfig.Settings(1999, 500, 250_000L, 4,
                0.30D, 0.20D, 0.05D, 0.50D, 100L, 25L, 0.015D));
        assertThrows(JsonParseException.class, () -> new SkillTreeConfig.Settings(2000, 500, 250_000L, 4,
                0.31D, 0.20D, 0.05D, 0.50D, 100L, 25L, 0.015D));
    }

    @Test
    void professionalDefaultsMigrateToMcmmoWithoutDroppingConfiguredTrees() {
        SkillTreeConfig.Settings professional = new SkillTreeConfig.Settings(100, 10, 250_000L, 4,
                0.30D, 0.20D, 0.05D, 0.50D, 100L, 25L, 0.015D);
        List<String> ids = List.of("miner", "lumberjack", "farmer", "hunter", "warrior",
                "guardian", "healing", "smithing", "alchemy", "exploration");
        List<SkillTreeConfig.TreeDefinition> trees = ids.stream()
                .map(thisId -> treeWithId(thisId))
                .toList();

        SkillTreeConfig migrated = SkillTreeConfig.migrateProfessionalToMcmmo(
                new SkillTreeConfig(1, professional, trees));

        assertEquals(SkillEngine.MCMOO, migrated.settings().engine());
        assertEquals(ids, migrated.trees().stream().map(SkillTreeConfig.TreeDefinition::id).toList());
        assertEquals("alchemy", LegacySkillAdapter.canonical("healing"));
        assertEquals("acrobatics", LegacySkillAdapter.canonical("survival"));
    }

    @Test
    void mcmmoConfigurationKeepsLegacyAliasTreesForSafeRollback() {
        SkillTreeConfig.Settings mcmmo = new SkillTreeConfig.Settings(1000, 10, 250_000L, 4,
                0.30D, 0.20D, 0.05D, 0.50D, 100L, 25L, 0.015D);
        SkillTreeConfig config = new SkillTreeConfig(2, mcmmo,
                List.of(treeWithId("mining"), treeWithId("miner")));
        assertEquals("miner", config.tree("miner").orElseThrow().id());
        assertEquals("mining", config.tree("mining").orElseThrow().id());
        assertEquals(2, config.trees().size());
    }

    @Test
    void canonicalSkillIdsResolveLegacyConfiguredTrees() {
        SkillTreeConfig.Settings mcmmo = new SkillTreeConfig.Settings(1000, 10, 250_000L, 4,
                0.30D, 0.20D, 0.05D, 0.50D, 100L, 25L, 0.015D);
        SkillTreeConfig config = new SkillTreeConfig(2, mcmmo, List.of(
                treeWithId("miner"), treeWithId("lumberjack"), treeWithId("farmer"),
                treeWithId("hunter"), treeWithId("warrior"), treeWithId("guardian"),
                treeWithId("smithing"), treeWithId("healing"), treeWithId("exploration")));

        assertEquals("miner", config.tree("mining").orElseThrow().id());
        assertEquals("lumberjack", config.tree("woodcutting").orElseThrow().id());
        assertEquals("farmer", config.tree("herbalism").orElseThrow().id());
        assertEquals("hunter", config.tree("archery").orElseThrow().id());
        assertEquals("warrior", config.tree("swords").orElseThrow().id());
        assertEquals("guardian", config.tree("acrobatics").orElseThrow().id());
        assertEquals("smithing", config.tree("repair").orElseThrow().id());
        assertEquals("healing", config.tree("alchemy").orElseThrow().id());
    }

    @Test
    void tuningBoundsSupportConfiguredActiveCooldownAndPassiveChanceCurves() {
        SkillTreeConfig.Tuning active = new SkillTreeConfig.Tuning(30, 120, 1800, 600, 0.0D, 0.0D);
        SkillTreeConfig.Tuning passive = new SkillTreeConfig.Tuning(0, 0, 0, 0, 0.05D, 0.40D);

        assertEquals(30, active.minDurationSeconds());
        assertEquals(600, active.minCooldownSeconds());
        assertEquals(0.40D, passive.maxValue());
        assertThrows(JsonParseException.class, () -> new SkillTreeConfig.Tuning(120, 30, 600, 1800, 0.4D, 0.05D));
    }

    @Test
    void hudSettingsHaveSafeBoundsAndDefaults() {
        SkillTreeConfig.HudSettings defaults = new SkillTreeConfig.HudSettings(true, true, 60, 3, true, true, true, 3);
        assertEquals(60, defaults.durationTicks());
        assertEquals(3, defaults.updateIntervalTicks());
        assertThrows(JsonParseException.class,
                () -> new SkillTreeConfig.HudSettings(true, true, 0, 3, true, true, true, 3));
        assertThrows(JsonParseException.class,
                () -> new SkillTreeConfig.HudSettings(true, true, 60, 21, true, true, true, 3));
        assertThrows(JsonParseException.class,
                () -> new SkillTreeConfig.HudSettings(true, true, 60, 3, true, true, true, 17));
    }

    @Test
    void treeRequiresFixedFourSkillStages() {
        assertThrows(JsonParseException.class, () -> new SkillTreeConfig.TreeDefinition("test", "测试", "minecraft:stone",
                Items.STONE, SkillAttribute.ATTACK_DAMAGE, Set.of(SkillXpSource.COMMAND),
                List.of(new SkillTreeConfig.LevelMultiplier(1, 1.0D)),
                List.of(skill("first", 1, 0), skill("second", 200, 1), skill("third", 750, 1), skill("fourth", 1500, 2))));
    }

    @Test
    void freeFoundationSpecializationUnlocksAtItsRequiredLevel() {
        SkillTreeConfig.TreeDefinition tree = tree();

        assertEquals(Set.of(), SkillTreeService.autoUnlockedSkills(tree, Set.of(), 0));
        assertEquals(Set.of("first"), SkillTreeService.autoUnlockedSkills(tree, Set.of(), 1));
        assertEquals(Set.of("first"), SkillTreeService.autoUnlockedSkills(tree, Set.of(), 2000));
    }

    @Test
    void legacyDefaultDescriptionsAreUpgradedWithoutOverwritingCustomText() {
        SkillTreeConfig.TreeDefinition builtIn = new SkillTreeConfig.TreeDefinition("combat", "战斗", "minecraft:iron_sword",
                Items.IRON_SWORD, SkillAttribute.ATTACK_DAMAGE, Set.of(SkillXpSource.ENTITY_KILL),
                List.of(new SkillTreeConfig.LevelMultiplier(1, 1.0D)), List.of(
                new SkillTreeConfig.SkillDefinition("foundation", "战斗本能", "基础技能，达到等级后可解锁。", 1, 0),
                skill("efficiency", 250, 1), skill("yield", 750, 1), skill("ultimate", 1500, 2)));
        SkillTreeConfig original = new SkillTreeConfig(1, settings(), List.of(builtIn));

        SkillTreeConfig migrated = SkillTreeConfig.migrateDefaultDescriptions(original);

        assertNotSame(original, migrated);
        assertEquals("自动解锁。战斗等级带来的常驻攻击伤害加成开始生效。",
                migrated.trees().getFirst().skills().getFirst().description());

        SkillTreeConfig.TreeDefinition customized = new SkillTreeConfig.TreeDefinition("combat", "战斗", "minecraft:iron_sword",
                Items.IRON_SWORD, SkillAttribute.ATTACK_DAMAGE, Set.of(SkillXpSource.ENTITY_KILL),
                List.of(new SkillTreeConfig.LevelMultiplier(1, 1.0D)), List.of(
                new SkillTreeConfig.SkillDefinition("foundation", "战斗本能", "服务器自定义说明", 1, 0),
                skill("efficiency", 250, 1), skill("yield", 750, 1), skill("ultimate", 1500, 2)));
        SkillTreeConfig customConfig = new SkillTreeConfig(1, settings(), List.of(customized));

        assertSame(customConfig, SkillTreeConfig.migrateDefaultDescriptions(customConfig));
    }

    private static SkillTreeConfig.TreeDefinition tree() {
        return treeWithId("test");
    }

    private static SkillTreeConfig.TreeDefinition treeWithId(String id) {
        return new SkillTreeConfig.TreeDefinition(id, "测试", "minecraft:stone", Items.STONE,
                SkillAttribute.ATTACK_DAMAGE, Set.of(SkillXpSource.COMMAND),
                List.of(new SkillTreeConfig.LevelMultiplier(1, 1.0D), new SkillTreeConfig.LevelMultiplier(501, 1.25D)),
                List.of(skill("first", 1, 0), skill("second", 250, 1), skill("third", 750, 1), skill("fourth", 1500, 2)));
    }

    private static SkillTreeConfig.Settings settings() {
        return new SkillTreeConfig.Settings(2000, 500, 250_000L, 4,
                0.30D, 0.20D, 0.05D, 0.50D, 100L, 25L, 0.015D);
    }

    private static SkillTreeConfig.SkillDefinition skill(String id, int level, int cost) {
        return new SkillTreeConfig.SkillDefinition(id, id, "test", level, cost);
    }
}
