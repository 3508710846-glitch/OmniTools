package dev.modmind.omnitools.cdk;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CdkDataTest {
    @Test
    void reservationEnforcesOneClaimPerUuidAndTheGlobalLimit() {
        CdkData data = new CdkData();
        CdkConfig.Campaign campaign = campaign("fingerprint-a", 1L);
        UUID first = UUID.randomUUID();

        assertEquals(CdkData.RedeemResult.RESERVED, data.reserve(campaign, first));
        assertEquals(CdkData.RedeemResult.ALREADY_REDEEMED, data.reserve(campaign, first));
        assertEquals(CdkData.RedeemResult.EXHAUSTED, data.reserve(campaign, UUID.randomUUID()));
        assertEquals(1L, data.audit("welcome").uses());
    }

    @Test
    void redeemedCampaignDefinitionsCannotChangeOrDisappear() {
        CdkData data = new CdkData();
        CdkConfig.Campaign original = campaign("fingerprint-a", 0L);
        data.reserve(original, UUID.randomUUID());
        CdkConfig same = new CdkConfig(CdkConfig.Security.defaults(), List.of(original), Map.of());
        data.validateConfiguration(same);

        CdkConfig changed = new CdkConfig(CdkConfig.Security.defaults(), List.of(campaign("fingerprint-b", 0L)), Map.of());
        assertThrows(IllegalArgumentException.class, () -> data.validateConfiguration(changed));
        assertThrows(IllegalArgumentException.class, () -> data.validateConfiguration(CdkConfig.empty()));
    }

    private static CdkConfig.Campaign campaign(String fingerprint, long maxUses) {
        return new CdkConfig.Campaign("welcome", CdkConfig.hashCode("WELCOME"), Instant.EPOCH,
                Instant.parse("2030-01-01T00:00:00Z"), maxUses,
                List.of(), fingerprint);
    }
}
