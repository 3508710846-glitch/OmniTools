package dev.modmind.omnitools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import dev.modmind.omnitools.config.ConfigPaths;
import dev.modmind.omnitools.config.ConfigFieldReporter;
import dev.modmind.omnitools.config.ModuleId;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/** Server-side settings for the player cloud storage feature. */
public final class CloudStorageConfig {
    public static final String FILE_NAME = "omnitools-cloud-storage.json";
    public static final int MIN_PAGES = 1;
    /** Maximum capacity that administrators may grant through the current configuration. */
    public static final int MAX_PAGES = 20;
    /** Retains pages from older or manually repaired data even when the configured limit is lower. */
    public static final int MAX_STORED_PAGES = 64;
    public static final int CURRENT_FORMAT_VERSION = 2;
    private static final BigDecimal DEFAULT_PRICE_MULTIPLIER = new BigDecimal("1.1");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = ConfigPaths.moduleConfig(ModuleId.CLOUD_STORAGE);

    private final long expansionCost;
    private final BigDecimal priceMultiplier;
    private final ExpansionRoundingMode roundingMode;
    private final int maxPages;

    private CloudStorageConfig(long expansionCost, BigDecimal priceMultiplier,
                               ExpansionRoundingMode roundingMode, int maxPages) {
        if (expansionCost < 0L || maxPages < MIN_PAGES || maxPages > MAX_PAGES
                || priceMultiplier == null || priceMultiplier.compareTo(BigDecimal.ONE) < 0
                || priceMultiplier.compareTo(BigDecimal.TEN) > 0 || roundingMode == null) {
            throw new IllegalArgumentException("Cloud storage expansion configuration is invalid");
        }
        this.expansionCost = expansionCost;
        this.priceMultiplier = priceMultiplier;
        this.roundingMode = roundingMode;
        this.maxPages = maxPages;
        if (maxPages > MIN_PAGES) expansionCostForPage(maxPages);
    }

    public static CloudStorageConfig load() {
        if (!Files.exists(FILE)) {
            CloudStorageConfig defaults = defaultConfig();
            writeDefault(defaults);
            return defaults;
        }

        try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            JsonElement root = GSON.fromJson(reader, JsonElement.class);
            if (root == null || !root.isJsonObject()) {
                throw new JsonParseException("Root value must be an object");
            }
            return parse(root.getAsJsonObject());
        } catch (IOException | JsonParseException exception) {
            System.err.println("[omnitools] Could not load " + FILE + ": " + exception.getMessage()
                    + ". The configuration snapshot will not be replaced.");
            throw new IllegalStateException("Invalid cloud storage configuration", exception);
        }
    }

    public static CloudStorageConfig defaultConfig() {
        return new CloudStorageConfig(100L, DEFAULT_PRICE_MULTIPLIER, ExpansionRoundingMode.CEILING, MAX_PAGES);
    }

    public long expansionCost() {
        return expansionCost;
    }

    public BigDecimal priceMultiplier() {
        return priceMultiplier;
    }

    public ExpansionRoundingMode roundingMode() {
        return roundingMode;
    }

    /** Calculates a page's price from the original base price, never from a previously rounded value. */
    public long expansionCostForPage(int targetPage) {
        return calculateExpansionCost(expansionCost, priceMultiplier, roundingMode, targetPage);
    }

    public long expansionCostForNextPage(int unlockedPages) {
        return expansionCostForPage(unlockedPages + 1);
    }

    public int maxPages() {
        return maxPages;
    }

    public static Path path() {
        return FILE;
    }

    private static CloudStorageConfig parse(JsonObject root) {
        ConfigFieldReporter.warnUnknown(root, "cloud_storage", Set.of("format_version", "expansionCost", "maxPages",
                "priceMultiplier", "roundingMode"));
        long version = integer(root, "format_version", CURRENT_FORMAT_VERSION);
        if (version < 1 || version > CURRENT_FORMAT_VERSION) {
            throw new JsonParseException("Unsupported cloud storage format_version: " + version);
        }
        long expansionCost = nonNegativeLong(root, "expansionCost");
        BigDecimal priceMultiplier = decimal(root, "priceMultiplier", DEFAULT_PRICE_MULTIPLIER);
        ExpansionRoundingMode roundingMode = ExpansionRoundingMode.parse(string(root, "roundingMode", "CEILING"));
        long configuredMaxPages = nonNegativeLong(root, "maxPages");
        if (configuredMaxPages < MIN_PAGES || configuredMaxPages > MAX_PAGES) {
            throw new JsonParseException("maxPages must be an integer between " + MIN_PAGES + " and " + MAX_PAGES);
        }
        try {
            return new CloudStorageConfig(expansionCost, priceMultiplier, roundingMode, (int) configuredMaxPages);
        } catch (IllegalArgumentException exception) {
            throw new JsonParseException(exception.getMessage());
        }
    }

    private static long nonNegativeLong(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException(key + " must be a non-negative integer");
        }
        try {
            long value = Long.parseLong(element.getAsString());
            if (value < 0L) {
                throw new JsonParseException(key + " must be a non-negative integer");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new JsonParseException(key + " must be a non-negative integer");
        }
    }

    private static int integer(JsonObject object, String key, int fallback) {
        JsonElement element = object.get(key);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException(key + " must be an integer");
        }
        try {
            return Integer.parseInt(element.getAsString());
        } catch (NumberFormatException exception) {
            throw new JsonParseException(key + " must be an integer");
        }
    }

    private static BigDecimal decimal(JsonObject object, String key, BigDecimal fallback) {
        JsonElement element = object.get(key);
        if (element == null) return fallback;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException(key + " must be a decimal number");
        }
        try {
            return new BigDecimal(element.getAsString());
        } catch (NumberFormatException exception) {
            throw new JsonParseException(key + " must be a decimal number");
        }
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        if (element == null) return fallback;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new JsonParseException(key + " must be a string");
        }
        return element.getAsString();
    }

    static long calculateExpansionCost(long basePrice, BigDecimal multiplier, ExpansionRoundingMode rounding,
                                       int targetPage) {
        if (basePrice < 0L || multiplier == null || multiplier.compareTo(BigDecimal.ONE) < 0
                || rounding == null || targetPage < 2 || targetPage > MAX_PAGES) {
            throw new IllegalArgumentException("Cloud storage expansion price input is invalid");
        }
        BigDecimal raw = BigDecimal.valueOf(basePrice).multiply(multiplier.pow(targetPage - 2));
        BigDecimal rounded = raw.setScale(0, rounding.javaMode());
        if (rounded.compareTo(BigDecimal.ONE) < 0) return 1L;
        if (rounded.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("Cloud storage expansion price exceeds the supported currency range");
        }
        return rounded.longValueExact();
    }

    private static void writeDefault(CloudStorageConfig defaults) {
        try {
            Files.createDirectories(FILE.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("format_version", CURRENT_FORMAT_VERSION);
            root.addProperty("expansionCost", defaults.expansionCost());
            root.addProperty("priceMultiplier", defaults.priceMultiplier());
            root.addProperty("roundingMode", defaults.roundingMode().serializedName());
            root.addProperty("maxPages", defaults.maxPages());
            try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            System.out.println("[omnitools] Created default cloud storage config at " + FILE);
        } catch (IOException exception) {
            System.err.println("[omnitools] Could not create default cloud storage config at " + FILE + ": "
                    + exception.getMessage());
        }
    }

    public enum ExpansionRoundingMode {
        CEILING(RoundingMode.CEILING),
        FLOOR(RoundingMode.FLOOR),
        HALF_UP(RoundingMode.HALF_UP);

        private final RoundingMode javaMode;

        ExpansionRoundingMode(RoundingMode javaMode) {
            this.javaMode = javaMode;
        }

        RoundingMode javaMode() {
            return javaMode;
        }

        String serializedName() {
            return name();
        }

        static ExpansionRoundingMode parse(String value) {
            try {
                return valueOf(value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new JsonParseException("roundingMode must be CEILING, FLOOR, or HALF_UP");
            }
        }
    }
}
