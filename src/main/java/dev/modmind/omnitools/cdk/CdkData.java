package dev.modmind.omnitools.cdk;

import dev.modmind.omnitools.ModMindEntry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** World-persistent campaign usage counters, UUID claims, fingerprints, and minimal audit timestamps. */
public final class CdkData extends SavedData {
    private static final String DATA_ID = ModMindEntry.MOD_ID + "_cdk";
    private static final String CAMPAIGNS_KEY = "campaigns";
    private final Map<String, CampaignRecord> campaigns = new HashMap<>();

    public static final SavedDataType<CdkData> TYPE = new SavedDataType<>(DATA_ID, CdkData::new,
            CompoundTag.CODEC.xmap(CdkData::fromTag, CdkData::toTag), DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    public static CdkData get(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("The overworld is not available while loading CDK data");
        }
        return overworld.getDataStorage().computeIfAbsent(TYPE);
    }

    public synchronized void validateConfiguration(CdkConfig config) {
        Map<String, CdkConfig.Campaign> configured = new HashMap<>();
        for (CdkConfig.Campaign campaign : config.campaigns()) {
            configured.put(campaign.id(), campaign);
            CampaignRecord record = campaigns.get(campaign.id());
            if (record != null && record.uses > 0L && !record.fingerprint.equals(campaign.fingerprint())) {
                throw new IllegalArgumentException("CDK campaign " + campaign.id()
                        + " has redemption history and cannot be modified; create a new id");
            }
        }
        for (Map.Entry<String, CampaignRecord> entry : campaigns.entrySet()) {
            if (entry.getValue().uses > 0L && !configured.containsKey(entry.getKey())) {
                throw new IllegalArgumentException("Redeemed CDK campaign " + entry.getKey()
                        + " cannot be removed; keep it unchanged or create a new campaign id");
            }
        }
    }

    public synchronized RedeemResult reserve(CdkConfig.Campaign campaign, UUID playerId) {
        CampaignRecord record = campaigns.computeIfAbsent(campaign.id(), ignored -> new CampaignRecord());
        if (!record.fingerprint.isBlank() && !record.fingerprint.equals(campaign.fingerprint())) {
            return RedeemResult.CONFIG_CHANGED;
        }
        if (record.claimedPlayers.contains(playerId)) {
            return RedeemResult.ALREADY_REDEEMED;
        }
        if (campaign.maxUses() > 0L && record.uses >= campaign.maxUses()) {
            return RedeemResult.EXHAUSTED;
        }
        record.fingerprint = campaign.fingerprint();
        record.claimedPlayers.add(playerId);
        record.claimedAt.put(playerId, System.currentTimeMillis());
        record.uses++;
        setDirty();
        return RedeemResult.RESERVED;
    }

    public synchronized boolean hasRedeemed(String campaignId, UUID playerId) {
        CampaignRecord record = campaigns.get(campaignId);
        return record != null && record.claimedPlayers.contains(playerId);
    }

    public synchronized CampaignAudit audit(String campaignId) {
        CampaignRecord record = campaigns.get(campaignId);
        return record == null ? new CampaignAudit(campaignId, 0L, 0, "")
                : new CampaignAudit(campaignId, record.uses, record.claimedPlayers.size(), record.fingerprint);
    }

    private static CdkData fromTag(CompoundTag root) {
        CdkData data = new CdkData();
        CompoundTag campaigns = root.getCompoundOrEmpty(CAMPAIGNS_KEY);
        for (String id : campaigns.keySet()) {
            CompoundTag source = campaigns.getCompoundOrEmpty(id);
            CampaignRecord record = new CampaignRecord();
            record.fingerprint = source.getStringOr("fingerprint", "");
            record.uses = Math.max(0L, source.getLongOr("uses", 0L));
            CompoundTag claims = source.getCompoundOrEmpty("claims");
            for (String playerText : claims.keySet()) {
                try {
                    UUID playerId = UUID.fromString(playerText);
                    record.claimedPlayers.add(playerId);
                    record.claimedAt.put(playerId, Math.max(0L, claims.getLongOr(playerText, 0L)));
                } catch (IllegalArgumentException ignored) {
                    // Skip malformed legacy keys without discarding the rest of the campaign audit.
                }
            }
            data.campaigns.put(id, record);
        }
        return data;
    }

    private static CompoundTag toTag(CdkData data) {
        CompoundTag root = new CompoundTag();
        CompoundTag campaigns = new CompoundTag();
        data.campaigns.forEach((id, record) -> {
            CompoundTag tag = new CompoundTag();
            tag.putString("fingerprint", record.fingerprint);
            tag.putLong("uses", record.uses);
            CompoundTag claims = new CompoundTag();
            record.claimedPlayers.forEach(player -> claims.putLong(player.toString(), record.claimedAt.getOrDefault(player, 0L)));
            tag.put("claims", claims);
            campaigns.put(id, tag);
        });
        root.put(CAMPAIGNS_KEY, campaigns);
        return root;
    }

    public enum RedeemResult {
        RESERVED,
        ALREADY_REDEEMED,
        EXHAUSTED,
        CONFIG_CHANGED
    }

    public record CampaignAudit(String campaignId, long uses, int uniquePlayers, String fingerprint) {
    }

    private static final class CampaignRecord {
        private String fingerprint = "";
        private long uses;
        private final Set<UUID> claimedPlayers = new HashSet<>();
        private final Map<UUID, Long> claimedAt = new HashMap<>();
    }
}
