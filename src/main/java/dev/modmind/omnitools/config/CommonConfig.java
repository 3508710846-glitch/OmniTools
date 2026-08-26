package dev.modmind.omnitools.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.core.HolderLookup;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.Set;

/**
 * Shared, data-only configuration referenced by module files.
 *
 * <p>Templates are expanded before a module parser sees them. They are deliberately kept as
 * JSON values so each module retains its existing strict parser and safety bounds.</p>
 */
public final class CommonConfig {
    public static final int CURRENT_FORMAT_VERSION = 1;
    public static final int MAX_REFERENCE_DEPTH = 4;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]{1,64}");

    private final Map<String, JsonObject> rewardTemplates;
    private final Map<String, JsonObject> conditionTemplates;
    private final Map<String, String> texts;

    public CommonConfig(Map<String, JsonObject> rewardTemplates,
                        Map<String, JsonObject> conditionTemplates,
                        Map<String, String> texts) {
        this.rewardTemplates = copyObjects(rewardTemplates);
        this.conditionTemplates = copyObjects(conditionTemplates);
        this.texts = Map.copyOf(texts == null ? Map.of() : texts);
    }

    public static CommonConfig empty() {
        return new CommonConfig(Map.of(), Map.of(), Map.of());
    }

    public static CommonConfig load(HolderLookup.Provider registries) {
        try {
            Files.createDirectories(ConfigPaths.commonDir());
            JsonObject rewards = readOrCreate(ConfigPaths.commonRewards(), defaultRewards());
            JsonObject conditions = readOrCreate(ConfigPaths.commonConditions(), defaultConditions());
            JsonObject texts = readOrCreate(ConfigPaths.commonTexts(), defaultTexts());
            return new CommonConfig(parseTemplates(rewards, "rewards"),
                    parseTemplates(conditions, "conditions"), parseTexts(texts));
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Invalid common configuration", exception);
        }
    }

    public Map<String, JsonObject> rewardTemplates() {
        return rewardTemplates;
    }

    public Map<String, JsonObject> conditionTemplates() {
        return conditionTemplates;
    }

    public Map<String, String> texts() {
        return texts;
    }

    /** Expands reward template references recursively while preserving per-entry overrides. */
    public JsonElement expandRewards(JsonElement element, String context) {
        return expandValue(element, rewardTemplates, context, 0, "reward");
    }

    /** Expands condition template references recursively while preserving per-node overrides. */
    public JsonElement expandCondition(JsonElement element, String context) {
        return expandValue(element, conditionTemplates, context, 0, "condition");
    }

    private static JsonElement expandValue(JsonElement element, Map<String, JsonObject> templates,
                                           String context, int depth, String kind) {
        if (element == null || element.isJsonNull()) {
            return element;
        }
        if (depth > MAX_REFERENCE_DEPTH) {
            throw new JsonParseException(context + " exceeds common " + kind + " template depth");
        }
        if (element.isJsonArray()) {
            JsonArray result = new JsonArray();
            int index = 0;
            for (JsonElement child : element.getAsJsonArray()) {
                result.add(expandValue(child, templates, context + "[" + index++ + "]", depth, kind));
            }
            return result;
        }
        if (!element.isJsonObject()) {
            return element.deepCopy();
        }
        JsonObject source = element.getAsJsonObject();
        JsonObject result = new JsonObject();
        JsonElement reference = source.get("template");
        if (reference == null) {
            reference = source.get("$ref");
        }
        if (reference != null) {
            if (!reference.isJsonPrimitive() || !reference.getAsJsonPrimitive().isString()) {
                throw new JsonParseException(context + ".template must be a string");
            }
            String id = reference.getAsString().trim().toLowerCase(java.util.Locale.ROOT);
            if (!ID.matcher(id).matches() || !templates.containsKey(id)) {
                throw new JsonParseException(context + " references unknown common " + kind + " template " + id);
            }
            result = expandValue(templates.get(id), templates, context + ".template(" + id + ")",
                    depth + 1, kind).getAsJsonObject();
        }
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            if (entry.getKey().equals("template") || entry.getKey().equals("$ref")) {
                continue;
            }
            result.add(entry.getKey(), expandValue(entry.getValue(), templates,
                    context + "." + entry.getKey(), depth, kind));
        }
        return result;
    }

    private static Map<String, JsonObject> parseTemplates(JsonObject root, String kind) {
        ConfigFieldReporter.warnUnknown(root, "common." + kind, Set.of("format_version", "templates"));
        int version = integer(root, "format_version", CURRENT_FORMAT_VERSION);
        if (version != CURRENT_FORMAT_VERSION) {
            throw new JsonParseException("common." + kind + ".format_version must be " + CURRENT_FORMAT_VERSION);
        }
        JsonElement value = root.get("templates");
        if (value == null) {
            return Map.of();
        }
        if (!value.isJsonObject()) {
            throw new JsonParseException("common." + kind + ".templates must be an object");
        }
        Map<String, JsonObject> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            String id = entry.getKey().trim().toLowerCase(java.util.Locale.ROOT);
            if (!ID.matcher(id).matches() || !entry.getValue().isJsonObject()) {
                throw new JsonParseException("common." + kind + " contains an invalid template id or value: " + id);
            }
            if (result.put(id, entry.getValue().getAsJsonObject().deepCopy()) != null) {
                throw new JsonParseException("common." + kind + " contains duplicate template " + id);
            }
        }
        return result;
    }

    private static Map<String, String> parseTexts(JsonObject root) {
        ConfigFieldReporter.warnUnknown(root, "common.texts", Set.of("format_version", "texts"));
        int version = integer(root, "format_version", CURRENT_FORMAT_VERSION);
        if (version != CURRENT_FORMAT_VERSION) {
            throw new JsonParseException("common.texts.format_version must be " + CURRENT_FORMAT_VERSION);
        }
        JsonElement value = root.get("texts");
        if (value == null) {
            return Map.of();
        }
        if (!value.isJsonObject()) {
            throw new JsonParseException("common.texts.texts must be an object");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            if (!ID.matcher(entry.getKey()).matches() || !entry.getValue().isJsonPrimitive()
                    || !entry.getValue().getAsJsonPrimitive().isString()) {
                throw new JsonParseException("common.texts contains an invalid text entry: " + entry.getKey());
            }
            result.put(entry.getKey(), entry.getValue().getAsString());
        }
        return result;
    }

    private static JsonObject readOrCreate(Path path, JsonObject defaults) throws IOException {
        if (!Files.exists(path)) {
            write(path, defaults);
            return defaults;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement root = GSON.fromJson(reader, JsonElement.class);
            if (root == null || !root.isJsonObject()) {
                throw new JsonParseException(path.getFileName() + " must contain an object");
            }
            return root.getAsJsonObject();
        }
    }

    private static void write(Path path, JsonObject value) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(value, writer);
        }
    }

    private static Map<String, JsonObject> copyObjects(Map<String, JsonObject> source) {
        Map<String, JsonObject> copy = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> copy.put(key, value.deepCopy()));
        }
        return Map.copyOf(copy);
    }

    private static int integer(JsonObject root, String key, int fallback) {
        JsonElement value = root.get(key);
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

    private static JsonObject defaultRewards() {
        JsonObject root = new JsonObject();
        root.addProperty("format_version", CURRENT_FORMAT_VERSION);
        root.add("templates", new JsonObject());
        return root;
    }

    private static JsonObject defaultConditions() {
        JsonObject root = new JsonObject();
        root.addProperty("format_version", CURRENT_FORMAT_VERSION);
        root.add("templates", new JsonObject());
        return root;
    }

    private static JsonObject defaultTexts() {
        JsonObject root = new JsonObject();
        root.addProperty("format_version", CURRENT_FORMAT_VERSION);
        root.add("texts", new JsonObject());
        return root;
    }
}
