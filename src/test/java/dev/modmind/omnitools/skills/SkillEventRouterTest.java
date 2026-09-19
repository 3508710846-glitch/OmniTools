package dev.modmind.omnitools.skills;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillEventRouterTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void routesLogsShovelBlocksAndMatureCropsToExactlyOneSkill() {
        assertEquals("woodcutting", SkillEventRouter.routeBlock(true, false, true, false).orElseThrow().skillId());
        assertEquals("excavation", SkillEventRouter.routeBlock(false, false, true, true).orElseThrow().skillId());
        assertEquals("mining", SkillEventRouter.routeBlock(false, false, true, false).orElseThrow().skillId());
        assertEquals("mining", SkillEventRouter.routeBlock(false, false, true, false, true).orElseThrow().skillId());
        assertFalse(SkillEventRouter.routeBlock(false, false, true, false, false).isPresent());
        assertFalse(SkillEventRouter.routeBlock(false, true, false, false).isPresent());
        assertEquals("herbalism", SkillEventRouter.routeBlock(false, true, true, false).orElseThrow().skillId());
    }

    @Test
    void routesCombatByWeaponTypeAndBuildsStableDistinctOperationIds() {
        assertEquals("archery", SkillEventRouter.entityKill("crossbow").skillId());
        assertEquals("axes", SkillEventRouter.entityKill("diamond_axe").skillId());
        assertEquals("swords", SkillEventRouter.entityKill("iron_sword").skillId());

        String first = SkillEventRouter.blockOperation(PLAYER, new BlockPos(1, 64, 1), 20L, "mining");
        String same = SkillEventRouter.blockOperation(PLAYER, new BlockPos(1, 64, 1), 20L, "mining");
        String different = SkillEventRouter.entityOperation(PLAYER, TARGET, 20L, "swords");
        assertEquals(first, same);
        assertNotEquals(first, different);
        assertTrue(first.endsWith(":mining"));
    }
}
