package dev.modmind.omnitools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import dev.modmind.omnitools.config.ConfigPaths;
import dev.modmind.omnitools.config.ModuleId;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Administrator-editable title definitions. Player state lives in {@link TitleData}. */
public final class TitleConfig {
    public static final String FILE_NAME = "omnitools-titles.json";
    private static final int MAX_TITLE_DISPLAY_LENGTH = 128;
    private static final int MAX_TOOLTIP_LINE_LENGTH = 256;
    private static final Pattern TITLE_ID = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = ConfigPaths.moduleConfig(ModuleId.TITLES);

    private final Map<String, TitleDefinition> titles;

    private TitleConfig(Map<String, TitleDefinition> titles) {
        this.titles = new LinkedHashMap<>(titles);
    }

    public static TitleConfig load() {
        if (!Files.exists(FILE)) {
            TitleConfig defaults = defaults();
            writeDefinitions(defaults);
            return defaults;
        }

        try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            JsonElement root = GSON.fromJson(reader, JsonElement.class);
            if (root == null || !root.isJsonObject()) {
                throw new JsonParseException("Root value must be an object");
            }
            return parse(root.getAsJsonObject());
        } catch (IOException | JsonParseException | IllegalArgumentException exception) {
            System.err.println("[omnitools] Could not load " + FILE + ": " + exception.getMessage()
                    + ". The configuration snapshot will not be replaced.");
            throw new IllegalStateException("Invalid title configuration", exception);
        }
    }

    public static TitleConfig empty() {
        return new TitleConfig(Map.of());
    }

    public static Path path() {
        return FILE;
    }

    public synchronized Collection<TitleDefinition> definitions() {
        return List.copyOf(titles.values());
    }

    public synchronized Optional<TitleDefinition> definition(String id) {
        return Optional.ofNullable(titles.get(normalizeId(id)));
    }

    public synchronized List<TitleDefinition> unlockedTitles(UUID playerId) {
        TitleData.PlayerRecord titlesForPlayer = state(playerId, "");
        if (titlesForPlayer == null) {
            return List.of();
        }

        List<TitleDefinition> result = new ArrayList<>();
        for (String titleId : titlesForPlayer.unlocked()) {
            TitleDefinition title = titles.get(titleId);
            if (title != null) {
                result.add(title);
            }
        }
        return List.copyOf(result);
    }

    public synchronized Optional<TitleDefinition> selectedTitle(UUID playerId) {
        TitleData.PlayerRecord titlesForPlayer = state(playerId, "");
        if (titlesForPlayer == null || titlesForPlayer.selected().isEmpty()
                || !titlesForPlayer.unlocked().contains(titlesForPlayer.selected())) {
            return Optional.empty();
        }
        return Optional.ofNullable(titles.get(titlesForPlayer.selected()));
    }

    public synchronized String selectedTitleId(UUID playerId) {
        TitleData.PlayerRecord titlesForPlayer = state(playerId, "");
        return titlesForPlayer == null ? "" : titlesForPlayer.selected();
    }

    public synchronized boolean effectsEnabled(UUID playerId) {
        TitleData.PlayerRecord titlesForPlayer = state(playerId, "");
        return titlesForPlayer == null || titlesForPlayer.effectsEnabled();
    }

    public synchronized boolean toggleEffects(UUID playerId, String playerName) {
        TitleData data = data();
        return data == null || data.toggleEffects(playerId, playerName);
    }

    public synchronized void rememberPlayer(UUID playerId, String playerName) {
        if (data() != null) {
            data().remember(playerId, playerName);
        }
    }

    public synchronized GrantResult grant(UUID playerId, String playerName, String titleId) {
        String normalizedId = normalizeId(titleId);
        if (!titles.containsKey(normalizedId)) {
            return GrantResult.UNKNOWN_TITLE;
        }

        boolean added = data() != null && data().grant(playerId, playerName, normalizedId);
        return added ? GrantResult.GRANTED : GrantResult.ALREADY_OWNED;
    }

    public synchronized RevokeResult revoke(UUID playerId, String playerName, String titleId) {
        String normalizedId = normalizeId(titleId);
        if (!titles.containsKey(normalizedId)) {
            return RevokeResult.UNKNOWN_TITLE;
        }

        boolean removed = data() != null && data().revoke(playerId, playerName, normalizedId);
        return removed ? RevokeResult.REVOKED : RevokeResult.NOT_OWNED;
    }

    public synchronized SelectionResult select(UUID playerId, String playerName, String titleId) {
        String normalizedId = normalizeId(titleId);
        TitleData.PlayerRecord titlesForPlayer = state(playerId, playerName);
        if (titlesForPlayer == null || !titlesForPlayer.unlocked().contains(normalizedId)
                || !titles.containsKey(normalizedId)) {
            return SelectionResult.NOT_OWNED;
        }
        boolean changed = !normalizedId.equals(titlesForPlayer.selected());
        if (data() != null) {
            data().select(playerId, playerName, normalizedId);
        }
        return changed ? SelectionResult.SELECTED : SelectionResult.ALREADY_SELECTED;
    }

    public synchronized boolean clearSelection(UUID playerId, String playerName) {
        return data() != null && data().clearSelection(playerId, playerName);
    }

    private static TitleConfig parse(JsonObject root) {
        JsonArray titlesArray = requiredArray(root, "titles");
        Map<String, TitleDefinition> titleDefinitions = new LinkedHashMap<>();
        for (int index = 0; index < titlesArray.size(); index++) {
            JsonElement element = titlesArray.get(index);
            if (!element.isJsonObject()) {
                throw new JsonParseException("Title entry " + index + " must be an object");
            }
            JsonObject titleObject = element.getAsJsonObject();
            String id = normalizeId(requiredString(titleObject, "id"));
            if (!TITLE_ID.matcher(id).matches()) {
                throw new JsonParseException("Title id " + id + " must match " + TITLE_ID.pattern());
            }
            if (titleDefinitions.containsKey(id)) {
                throw new JsonParseException("Title id " + id + " is configured more than once");
            }
            String display = requiredString(titleObject, "display");
            if (display.length() > MAX_TITLE_DISPLAY_LENGTH || LegacyTitleText.plainText(display).isBlank()) {
                throw new JsonParseException("Title display for " + id + " must contain visible text and be at most "
                        + MAX_TITLE_DISPLAY_LENGTH + " characters");
            }
            TitleRarity rarity = TitleRarity.parse(requiredString(titleObject, "rarity"));
            List<String> effects = parseEffectIds(titleObject, id);
            List<String> tooltip = parseStringArray(titleObject, "tooltip", id);
            titleDefinitions.put(id, new TitleDefinition(id, display, rarity, effects, tooltip));
        }

        return new TitleConfig(titleDefinitions);
    }

    private static TitleConfig defaults() {
        Map<String, TitleDefinition> definitions = new LinkedHashMap<>();
        definitions.put("geologist", new TitleDefinition("geologist", "\u00a77[\u00a7r\u5730\u8d28\u5b66\u5bb6\u00a77] \u00a7r", TitleRarity.COMMON,
                List.of("health_2"), List.of("\u00a77\u4f69\u6234\u6548\u679c\uff1a", "\u00a7c\u2665 \u751f\u547d\u4e0a\u9650 +4")));
        definitions.put("architect", new TitleDefinition("architect", "\u00a7b[\u00a7r\u5efa\u7b51\u5e08\u00a7b] \u00a7r", TitleRarity.RARE,
                List.of("speed_1"), List.of("\u00a77\u4f69\u6234\u6548\u679c\uff1a", "\u00a7a\u2714 \u79fb\u52a8\u901f\u5ea6\u63d0\u5347")));
        definitions.put("legend", new TitleDefinition("legend", "\u00a76[\u00a7r\u4f20\u8bf4\u00a76] \u00a7r", TitleRarity.LEGENDARY,
                List.of("resistance_1", "night_vision"), List.of("\u00a77\u4f69\u6234\u6548\u679c\uff1a", "\u00a7a\u2714 \u6297\u6027\u63d0\u5347 I", "\u00a7a\u2714 \u6c38\u4e45\u591c\u89c6")));
        return new TitleConfig(definitions);
    }

    private static TitleData data() {
        return TitleData.current();
    }

    private static TitleData.PlayerRecord state(UUID playerId, String playerName) {
        TitleData data = data();
        return data == null ? null : data.record(playerId, playerName);
    }

    private static void writeDefinitions(TitleConfig config) {
        try {
            Files.createDirectories(FILE.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("format_version", 1);
            JsonArray definitions = new JsonArray();
            for (TitleDefinition title : config.titles.values()) {
                JsonObject titleObject = new JsonObject();
                titleObject.addProperty("id", title.id());
                titleObject.addProperty("display", title.display());
                titleObject.addProperty("rarity", title.rarity().serializedName());
                JsonArray effects = new JsonArray();
                title.effects().forEach(effects::add);
                titleObject.add("effects", effects);
                JsonArray tooltip = new JsonArray();
                title.tooltip().forEach(tooltip::add);
                titleObject.add("tooltip", tooltip);
                definitions.add(titleObject);
            }
            root.add("titles", definitions);
            try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (IOException exception) {
            System.err.println("[omnitools] Could not save " + FILE + ": " + exception.getMessage());
        }
    }

    private static JsonArray requiredArray(JsonObject object, String key) {
        JsonArray array = optionalArray(object, key);
        if (array == null) {
            throw new JsonParseException(key + " must be an array");
        }
        return array;
    }

    private static JsonArray optionalArray(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    private static JsonObject optionalObject(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static List<String> parseStringArray(JsonObject object, String key, String context) {
        JsonElement element = object.get(key);
        if (element == null) {
            return List.of();
        }
        if (!element.isJsonArray()) {
            throw new JsonParseException(key + " for " + context + " must be an array");
        }
        List<String> values = new ArrayList<>();
        for (JsonElement value : element.getAsJsonArray()) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new JsonParseException(key + " for " + context + " must contain strings");
            }
            String text = value.getAsString();
            if (text.length() > MAX_TOOLTIP_LINE_LENGTH) {
                throw new JsonParseException(key + " for " + context + " contains an overly long string");
            }
            values.add(text);
        }
        return List.copyOf(values);
    }

    private static List<String> parseEffectIds(JsonObject object, String titleId) {
        List<String> values = parseStringArray(object, "effects", titleId);
        Set<String> ids = new LinkedHashSet<>();
        for (String value : values) {
            String id = normalizeId(value);
            if (!TITLE_ID.matcher(id).matches()) {
                throw new JsonParseException("Effect id " + value + " on title " + titleId
                        + " must match " + TITLE_ID.pattern());
            }
            if (!ids.add(id)) {
                throw new JsonParseException("Effect id " + id + " is configured more than once on title " + titleId);
            }
        }
        return List.copyOf(ids);
    }

    private static String requiredString(JsonObject object, String key) {
        String value = optionalString(object, key);
        if (value == null || value.isBlank()) {
            throw new JsonParseException(key + " must be a non-empty string");
        }
        return value;
    }

    private static String optionalString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new JsonParseException(key + " must be a string");
        }
        return element.getAsString();
    }

    private static String normalizeId(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

    public record TitleDefinition(String id, String display, TitleRarity rarity, List<String> effects,
                                  List<String> tooltip) {
        public TitleDefinition {
            effects = effects == null ? List.of() : List.copyOf(effects);
            tooltip = tooltip == null ? List.of() : List.copyOf(tooltip);
        }

        public Component displayComponent() {
            return LegacyTitleText.parse(display);
        }

        public String plainDisplay() {
            return LegacyTitleText.plainText(display);
        }
    }

    public enum GrantResult {
        GRANTED,
        ALREADY_OWNED,
        UNKNOWN_TITLE
    }

    public enum RevokeResult {
        REVOKED,
        NOT_OWNED,
        UNKNOWN_TITLE
    }

    public enum SelectionResult {
        SELECTED,
        ALREADY_SELECTED,
        NOT_OWNED
    }

}
