package dev.modmind.omnitools.packages;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackageSkillXpGrantTest {
    @Test
    void playerChoiceCanOnlyResolveToItsSnapshottedOptions() {
        PackageSkillXpGrant grant = new PackageSkillXpGrant("choose_training", 2000L,
                PackageSkillXp.Mode.PLAYER_CHOICE, List.of(
                new PackageSkillXpGrant.TreeOption("combat", "Combat", "minecraft:iron_sword"),
                new PackageSkillXpGrant.TreeOption("crafting", "Crafting", "minecraft:crafting_table")), "");

        assertTrue(grant.requiresPlayerChoice());
        PackageSkillXpGrant selected = grant.withResolvedTree("crafting");
        assertEquals("crafting", selected.resolvedTreeId());
        assertFalse(selected.requiresPlayerChoice());
        assertEquals("crafting", selected.asRewardDefinition().skillTreeId());
        assertThrows(IllegalArgumentException.class, () -> grant.withResolvedTree("survival"));
    }

    @Test
    void fixedGrantIsResolvedAtSnapshotCreation() {
        PackageSkillXpGrant grant = new PackageSkillXpGrant("combat_training", 1000L,
                PackageSkillXp.Mode.FIXED, List.of(
                new PackageSkillXpGrant.TreeOption("combat", "Combat", "minecraft:iron_sword")), "");

        assertEquals("combat", grant.resolvedTreeId());
        assertFalse(grant.unresolvedRandom());
    }

    @Test
    void snapshotCarriesExplicitTitleBonusPolicyIntoRewardDefinition() {
        PackageSkillXpGrant grant = new PackageSkillXpGrant("activity_training", 1000L,
                PackageSkillXp.Mode.FIXED, List.of(
                new PackageSkillXpGrant.TreeOption("combat", "Combat", "minecraft:iron_sword")), "", true);

        assertTrue(grant.applyTitleXpBonus());
        assertTrue(grant.asRewardDefinition().applyTitleXpBonus());
    }

    @Test
    void instanceKeepsSkillXpSnapshotWhenItsStatusChanges() {
        PackageSkillXpGrant grant = new PackageSkillXpGrant("random_training", 1000L,
                PackageSkillXp.Mode.RANDOM, List.of(
                new PackageSkillXpGrant.TreeOption("combat", "Combat", "minecraft:iron_sword"),
                new PackageSkillXpGrant.TreeOption("crafting", "Crafting", "minecraft:crafting_table")), "combat");
        PackageInstance instance = new PackageInstance(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "training", 1, "Training", List.of(), "minecraft:experience_bottle", PackageDefinition.Mode.ALL,
                List.of(), List.of(), List.of(grant), "test", "", PackageInstance.Status.PENDING, 1L, -1);

        assertEquals("combat", instance.withStatus(PackageInstance.Status.OPENING)
                .skillXpGrants().getFirst().resolvedTreeId());
    }
}
