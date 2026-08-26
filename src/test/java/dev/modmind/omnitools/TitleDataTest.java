package dev.modmind.omnitools;

import dev.modmind.omnitools.entitlement.TimedEntitlement;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TitleDataTest {
    private static final UUID PLAYER_ID = UUID.fromString("c0ffee00-0000-0000-0000-000000000001");

    @Test
    void oldUnlockedDataMigratesToPermanentEntitlements() {
        CompoundTag root = new CompoundTag();
        CompoundTag players = new CompoundTag();
        CompoundTag player = new CompoundTag();
        ListTag unlocked = new ListTag();
        unlocked.add(StringTag.valueOf("vip"));
        player.put("unlocked", unlocked);
        player.putString("selected", "vip");
        players.put(PLAYER_ID.toString(), player);
        root.put("players", players);

        TitleData data = TitleData.fromTag(root);
        TitleData.PlayerRecord record = data.read(PLAYER_ID);

        assertTrue(record.unlocked().contains("vip"));
        assertTrue(record.entitlement("vip").orElseThrow().isPermanent());
        assertEquals("vip", record.selected());
        assertTrue(TitleData.toTag(data).getCompoundOrEmpty("players").getCompoundOrEmpty(PLAYER_ID.toString())
                .getCompoundOrEmpty("titles").contains("vip"));
    }

    @Test
    void temporaryTitleConsumesOnlyWhenSelectedAndExpiresAtomically() {
        TitleData data = new TitleData();
        TimedEntitlement.Grant grant = new TimedEntitlement.Grant(TimedEntitlement.Mode.ACTIVE_DAYS,
                2L, TimedEntitlement.RenewalPolicy.EXTEND);
        data.grantEntitlement(PLAYER_ID, "Player", "vip", grant, 1L);

        assertEquals(TitleData.TickResult.NONE, data.consumeSelectedActiveTick(PLAYER_ID));
        assertTrue(data.select(PLAYER_ID, "Player", "vip"));
        assertTrue(data.consumeSelectedActiveTick(PLAYER_ID).consumed());
        TitleData.TickResult expired = data.consumeSelectedActiveTick(PLAYER_ID);

        assertTrue(expired.expired());
        assertEquals("", data.read(PLAYER_ID).selected());
        assertFalse(data.read(PLAYER_ID).unlocked().contains("vip"));
        assertTrue(data.read(PLAYER_ID).entitlement("vip").isEmpty());
    }

    @Test
    void temporaryTitleSurvivesSavedDataRoundTrip() {
        TitleData data = new TitleData();
        TimedEntitlement.Grant grant = new TimedEntitlement.Grant(TimedEntitlement.Mode.ACTIVE_DAYS,
                123L, TimedEntitlement.RenewalPolicy.MAX);
        data.grantEntitlement(PLAYER_ID, "Player", "vip", grant, 42L);
        data.select(PLAYER_ID, "Player", "vip");

        TitleData restored = TitleData.fromTag(TitleData.toTag(data));
        TimedEntitlement entitlement = restored.read(PLAYER_ID).entitlement("vip").orElseThrow();

        assertEquals(TimedEntitlement.Mode.ACTIVE_DAYS, entitlement.mode());
        assertEquals(123L, entitlement.remainingActiveTicks());
        assertEquals(123L, entitlement.totalGrantedTicks());
        assertEquals(42L, entitlement.grantedAt());
        assertEquals(TimedEntitlement.RenewalPolicy.MAX, entitlement.renewalPolicy());
        assertEquals("vip", restored.read(PLAYER_ID).selected());
    }

    @Test
    void rewardEventIdPreventsSameTimedRewardFromExtendingTwice() {
        TitleData data = new TitleData();
        TimedEntitlement.Grant grant = new TimedEntitlement.Grant(TimedEntitlement.Mode.ACTIVE_DAYS,
                10L, TimedEntitlement.RenewalPolicy.EXTEND);

        assertEquals(TitleData.GrantResult.GRANTED,
                data.grantReward(PLAYER_ID, "Player", "vip", "event-1", "reward-1", grant));
        assertEquals(TitleData.GrantResult.ALREADY_OWNED,
                data.grantReward(PLAYER_ID, "Player", "vip", "event-1", "reward-1", grant));
        assertEquals(10L, data.read(PLAYER_ID).entitlement("vip").orElseThrow().remainingActiveTicks());
        assertEquals(TitleData.GrantResult.RENEWED,
                data.grantReward(PLAYER_ID, "Player", "vip", "event-2", "reward-1", grant));
        assertEquals(20L, data.read(PLAYER_ID).entitlement("vip").orElseThrow().remainingActiveTicks());
    }
}
