package dev.modmind.omnitools.cdk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.modmind.omnitools.config.ConfigPaths;
import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.config.CommonConfig;
import dev.modmind.omnitools.config.ConfigFieldReporter;
import dev.modmind.omnitools.reward.RewardDefinition;
import net.minecraft.core.HolderLookup;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Immutable CDK snapshot. Raw redemption codes are discarded after their SHA-256 hashes are built. */
public record CdkConfig(Security security, List<Campaign> campaigns, Map<String, Campaign> campaignsByCodeHash) {
    public static final int CURRENT_FORMAT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public CdkConfig {
        security = security == null ? Security.defaults() : security;
        campaigns = List.copyOf(campaigns == null ? List.of() : campaigns);
        Map<String, Campaign> byHash = new HashMap<>();
        for (Campaign campaign : campaigns) {
            if (byHash.put(campaign.codeHash(), campaign) != null) {
                throw new IllegalArgumentException("Duplicate CDK code hash");
            }
        }
        campaignsByCodeHash = Map.copyOf(byHash);
    }

    public static CdkConfig empty() {
        return new CdkConfig(Security.defaults(), List.of(), Map.of());
    }

    public static CdkConfig load(HolderLookup.Provider registries) {
        return load(registries, CommonConfig.empty());
    }

    public static CdkConfig load(HolderLookup.Provider registries, CommonConfig common) {
        Path file = configFile();
        if (!Files.exists(file)) {
            CdkConfig defaults = empty();
            write(defaults);
            return defaults;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = GSON.fromJson(reader, JsonElement.class);
            if (root == null || !root.isJsonObject()) {
                throw new JsonParseException("CDK configuration root must be an object");
            }
            return parse(root.getAsJsonObject(), registries, common);
        } catch (IOException | RuntimeException exception) {
            // Do not include the configuration body or a raw code in diagnostics.
            throw new IllegalStateException("Invalid CDK configuration", exception);
        }
    }

    public static Path path() {
        return configFile();
    }

    public Optional<Campaign> find(String rawCode) {
        if (rawCode == null || rawCode.isBlank() || rawCode.length() > security.maxCodeLength()) {
            return Optional.empty();
        }
        return Optional.ofNullable(campaignsByCodeHash.get(hashCode(normalizeCode(rawCode))));
    }

    private static CdkConfig parse(JsonObject root, HolderLookup.Provider registries, CommonConfig common) {
        ConfigFieldReporter.warnUnknown(root, "cdk", Set.of("format_version", "security", "campaigns"));
        int version = integer(root, "format_version", CURRENT_FORMAT_VERSION);
        if (version != CURRENT_FORMAT_VERSION) {
            throw new JsonParseException("Unsupported CDK format_version: " + version);
        }
        Security security = Security.parse(requiredObject(root, "security"));
        JsonElement campaignsElement = root.get("campaigns");
        if (campaignsElement == null || !campaignsElement.isJsonArray()) {
            throw new JsonParseException("campaigns must be an array");
        }
        List<Campaign> campaigns = new ArrayList<>();
        Map<String, Boolean> ids = new HashMap<>();
        Map<String, Boolean> codes = new HashMap<>();
        JsonArray array = campaignsElement.getAsJsonArray();
        for (int index = 0; index < array.size(); index++) {
            if (!array.get(index).isJsonObject()) {
                throw new JsonParseException("campaigns[" + index + "] must be an object");
            }
            Campaign campaign = Campaign.parse(array.get(index).getAsJsonObject(), "campaigns[" + index + "]",
                    security, registries, common);
            if (ids.put(campaign.id(), Boolean.TRUE) != null || codes.put(campaign.codeHash(), Boolean.TRUE) != null) {
                throw new JsonParseException("campaigns contains duplicate campaign id or code");
            }
            campaigns.add(campaign);
        }
        return new CdkConfig(security, campaigns, Map.of());
    }

    private static void write(CdkConfig config) {
        try {
            Path file = configFile();
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("format_version", CURRENT_FORMAT_VERSION);
            JsonObject security = new JsonObject();
            security.addProperty("max_code_length", config.security.maxCodeLength());
            security.addProperty("cooldown_ticks", config.security.cooldownTicks());
            security.addProperty("max_failed_attempts", config.security.maxFailedAttempts());
            security.addProperty("lockout_seconds", config.security.lockoutSeconds());
            root.add("security", security);
            root.add("campaigns", new JsonArray());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create CDK configuration", exception);
        }
    }

    private static Path configFile() {
        return ConfigPaths.moduleConfig(ModuleId.CDK);
    }

    private static JsonObject requiredObject(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonObject()) {
            throw new JsonParseException(key + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static String requiredString(JsonObject object, String key, String context) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw new JsonParseException(context + "." + key + " must be a non-empty string");
        }
        return value.getAsString().trim();
    }

    private static String optionalString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null) {
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new JsonParseException(key + " must be a string");
        }
        return value.getAsString().trim();
    }

    private static int integer(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        if (value == null) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException(key + " must be an integer");
        }
        try {
            return Integer.parseInt(value.getAsString());
        } catch (NumberFormatException exception) {
            throw new JsonParseException(key + " must be an integer");
        }
    }

    private static long nonNegativeLong(JsonObject object, String key, String context) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException(context + "." + key + " must be a non-negative integer");
        }
        try {
            long parsed = Long.parseLong(value.getAsString());
            if (parsed < 0L) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new JsonParseException(context + "." + key + " must be a non-negative integer");
        }
    }

    static String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    static String hashCode(String code) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                result.append(String.format(Locale.ROOT, "%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public record Security(int maxCodeLength, int cooldownTicks, int maxFailedAttempts, int lockoutSeconds) {
        public Security {
            if (maxCodeLength < 4 || maxCodeLength > 256 || cooldownTicks < 0 || cooldownTicks > 72_000
                    || maxFailedAttempts < 1 || maxFailedAttempts > 100 || lockoutSeconds < 1
                    || lockoutSeconds > 86_400) {
                throw new IllegalArgumentException("CDK security bounds are invalid");
            }
        }

        static Security defaults() {
            return new Security(64, 20, 5, 60);
        }

        static Security parse(JsonObject object) {
            Security defaults = defaults();
            try {
                return new Security(integer(object, "max_code_length", defaults.maxCodeLength),
                        integer(object, "cooldown_ticks", defaults.cooldownTicks),
                        integer(object, "max_failed_attempts", defaults.maxFailedAttempts),
                        integer(object, "lockout_seconds", defaults.lockoutSeconds));
            } catch (IllegalArgumentException exception) {
                throw new JsonParseException("CDK security values are out of range", exception);
            }
        }
    }

    public record Campaign(String id, String codeHash, Instant startsAt, Instant expiresAt, long maxUses,
                           List<RewardDefinition> rewards, String fingerprint) {
        public Campaign {
            id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
            if (!RewardDefinition.ID_PATTERN.matcher(id).matches()) {
                throw new IllegalArgumentException("Invalid CDK campaign id");
            }
            if (codeHash == null || !codeHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid CDK code hash");
            }
            if (startsAt != null && expiresAt != null && !startsAt.isBefore(expiresAt)) {
                throw new IllegalArgumentException("CDK campaign start must precede expiry");
            }
            if (maxUses < 0L || maxUses > 10_000_000L || rewards == null) {
                throw new IllegalArgumentException("Invalid CDK campaign limits or rewards");
            }
            rewards = List.copyOf(rewards);
            fingerprint = fingerprint == null ? "" : fingerprint;
        }

        static Campaign parse(JsonObject object, String context, Security security, HolderLookup.Provider registries,
                              CommonConfig common) {
            ConfigFieldReporter.warnUnknown(object, context,
                    Set.of("id", "code", "starts_at", "expires_at", "max_uses", "rewards"));
            String id = requiredString(object, "id", context).toLowerCase(Locale.ROOT);
            String code = requiredString(object, "code", context);
            if (code.length() > security.maxCodeLength()) {
                throw new JsonParseException(context + ".code exceeds security.max_code_length");
            }
            Instant starts = parseInstant(optionalString(object, "starts_at"), context + ".starts_at");
            Instant expires = parseInstant(optionalString(object, "expires_at"), context + ".expires_at");
            long maxUses = nonNegativeLong(object, "max_uses", context);
            JsonElement expandedRewards = (common == null ? CommonConfig.empty() : common)
                    .expandRewards(object.get("rewards"), context + ".rewards");
            List<RewardDefinition> rewards = RewardDefinition.parseArray(expandedRewards, context + ".rewards",
                    registries, CommonConfig.empty());
            if (rewards.isEmpty()) {
                throw new JsonParseException(context + ".rewards must not be empty");
            }
            String codeHash = CdkConfig.hashCode(normalizeCode(code));
            String fingerprint = CdkConfig.hashCode(id + "|" + codeHash + "|" + (starts == null ? "" : starts)
                    + "|" + (expires == null ? "" : expires) + "|" + maxUses + "|" + expandedRewards);
            try {
                return new Campaign(id, codeHash, starts, expires, maxUses, rewards, fingerprint);
            } catch (IllegalArgumentException exception) {
                throw new JsonParseException(context + " is invalid", exception);
            }
        }

        public boolean availableAt(Instant now) {
            return (startsAt == null || !now.isBefore(startsAt)) && (expiresAt == null || now.isBefore(expiresAt));
        }

        private static Instant parseInstant(String value, String context) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException exception) {
                throw new JsonParseException(context + " must be an ISO-8601 UTC instant", exception);
            }
        }
    }
}
