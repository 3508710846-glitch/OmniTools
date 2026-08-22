package dev.modmind.qiandao;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import dev.modmind.qiandao.config.ConfigPaths;
import dev.modmind.qiandao.config.ModuleId;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Server-side settings for the player cloud storage feature. */
public final class CloudStorageConfig {
    public static final String FILE_NAME = "qiandao-cloud-storage.json";
    public static final int MIN_PAGES = 1;
    public static final int MAX_PAGES = 2;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = ConfigPaths.moduleConfig(ModuleId.CLOUD_STORAGE);

    private final long expansionCost;
    private final int maxPages;

    private CloudStorageConfig(long expansionCost, int maxPages) {
        this.expansionCost = expansionCost;
        this.maxPages = maxPages;
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
            System.err.println("[qiandao] Could not load " + FILE + ": " + exception.getMessage()
                    + ". The configuration snapshot will not be replaced.");
            throw new IllegalStateException("Invalid cloud storage configuration", exception);
        }
    }

    public static CloudStorageConfig defaultConfig() {
        return new CloudStorageConfig(100L, MAX_PAGES);
    }

    public long expansionCost() {
        return expansionCost;
    }

    public int maxPages() {
        return maxPages;
    }

    public static Path path() {
        return FILE;
    }

    private static CloudStorageConfig parse(JsonObject root) {
        long expansionCost = nonNegativeLong(root, "expansionCost");
        long configuredMaxPages = nonNegativeLong(root, "maxPages");
        if (configuredMaxPages < MIN_PAGES || configuredMaxPages > MAX_PAGES) {
            throw new JsonParseException("maxPages must be an integer between " + MIN_PAGES + " and " + MAX_PAGES);
        }
        return new CloudStorageConfig(expansionCost, (int) configuredMaxPages);
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

    private static void writeDefault(CloudStorageConfig defaults) {
        try {
            Files.createDirectories(FILE.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("format_version", 1);
            root.addProperty("expansionCost", defaults.expansionCost());
            root.addProperty("maxPages", defaults.maxPages());
            try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            System.out.println("[qiandao] Created default cloud storage config at " + FILE);
        } catch (IOException exception) {
            System.err.println("[qiandao] Could not create default cloud storage config at " + FILE + ": "
                    + exception.getMessage());
        }
    }
}
