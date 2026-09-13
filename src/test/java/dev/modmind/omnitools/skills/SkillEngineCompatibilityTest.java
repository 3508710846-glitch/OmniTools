package dev.modmind.omnitools.skills;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillEngineCompatibilityTest {
    @Test
    void canonicalAliasesPreserveLegacySkillNames() {
        assertEquals("mining", LegacySkillAdapter.canonical("miner"));
        assertEquals("woodcutting", LegacySkillAdapter.canonical("lumberjack"));
        assertEquals("repair", LegacySkillAdapter.canonical("smithing"));
        assertTrue(LegacySkillAdapter.isLegacyAlias("warrior"));
        assertFalse(LegacySkillAdapter.isLegacyAlias("mining"));
    }

    @Test
    void abilityMilestonesAreBoundedAndDeterministic() {
        McmmoSkillModule module = new McmmoSkillModule(SkillTreeConfig.empty());
        assertEquals(0, module.abilityTier(99));
        assertEquals(1, module.abilityTier(100));
        assertEquals(3, module.abilityLevel(250));
        assertEquals(5, module.abilityLevel(500));
        assertEquals(10, module.abilityLevel(10_000));
        assertTrue(module.abilityUnlocked(100));
    }

    @Test
    void operationLedgerDeduplicatesAndBoundsEntries() {
        SkillLedger ledger = new SkillLedger();
        assertTrue(ledger.claim("op-1"));
        assertFalse(ledger.claim("op-1"));
        for (int index = 0; index < SkillLedger.MAX_OPERATION_IDS + 10; index++) {
            ledger.claim("op-" + index);
        }
        assertTrue(ledger.snapshot().size() <= SkillLedger.MAX_OPERATION_IDS);
    }
}
