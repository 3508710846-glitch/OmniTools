package dev.modmind.omnitools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import dev.modmind.omnitools.config.ConfigPaths;
import dev.modmind.omnitools.config.ConfigFieldReporter;
import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.entitlement.TimedEntitlement;
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

    private final Map<String, TitleDefinition> titles;
    private final NameplateMode nameplateMode;
    private final TeamConflictPolicy teamConflictPolicy;

    private TitleConfig(Map<String, TitleDefinition> titles, NameplateMode nameplateMode,
                        TeamConflictPolicy teamConflictPolicy) {
        this.titles = new LinkedHashMap<>(titles);
        this.nameplateMode = nameplateMode;
        this.teamConflictPolicy = teamConflictPolicy;
    }

    public static TitleConfig load() {
        Path file = path();
        if (!Files.exists(file)) {
            TitleConfig defaults = defaults();
            writeDefinitions(defaults);
            return defaults;
        }

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = GSON.fromJson(reader, JsonElement.class);
            if (root == null || !root.isJsonObject()) {
                throw new JsonParseException("Root value must be an object");
            }
            return parse(root.getAsJsonObject());
        } catch (IOException | JsonParseException | IllegalArgumentException exception) {
            System.err.println("[omnitools] Could not load " + file + ": " + exception.getMessage()
                    + ". The configuration snapshot will not be replaced.");
            throw new IllegalStateException("Invalid title configuration", exception);
        }
    }

    public static TitleConfig empty() {
        return new TitleConfig(Map.of(), NameplateMode.SCOREBOARD_TEAM, TeamConflictPolicy.OMNITOOLS_PRIORITY);
    }

    public static Path path() {
        return ConfigPaths.moduleConfig(ModuleId.TITLES);
    }

    public synchronized Collection<TitleDefinition> definitions() {
        return List.copyOf(titles.values());
    }

    public synchronized Optional<TitleDefinition> definition(String id) {
        return Optional.ofNullable(titles.get(normalizeId(id)));
    }

    public synchronized boolean hasInlineEffects() {
        return titles.values().stream().anyMatch(TitleDefinition::inlineEffectsConfigured);
    }

    /** Resolves the effects for a title, preferring its v2 embedded snapshot over v1 ids. */
    public synchronized List<TitleEffectConfig.EffectDefinition> effectsFor(TitleDefinition title,
                                                                              TitleEffectConfig legacyEffects) {
        if (title == null) {
            return List.of();
        }
        if (title.inlineEffectsConfigured()) {
            return title.embeddedEffects();
        }
        if (legacyEffects == null || title.effects().isEmpty()) {
            return List.of();
        }
        return title.effects().stream()
                .map(legacyEffects::definition)
                .flatMap(Optional::stream)
                .toList();
    }

    public NameplateMode nameplateMode() {
        return nameplateMode;
    }

    public TeamConflictPolicy teamConflictPolicy() {
        return teamConflictPolicy;
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

    public synchronized Optional<TimedEntitlement> entitlement(UUID playerId, String titleId) {
        TitleData.PlayerRecord titlesForPlayer = state(playerId, "");
        return titlesForPlayer == null ? Optional.empty() : titlesForPlayer.entitlement(titleId);
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
        return grant(playerId, playerName, titleId, TimedEntitlement.permanentGrant());
    }

    public synchronized GrantResult grant(UUID playerId, String playerName, String titleId,
                                           TimedEntitlement.Grant entitlementGrant) {
        String normalizedId = normalizeId(titleId);
        if (!titles.containsKey(normalizedId)) {
            return GrantResult.UNKNOWN_TITLE;
        }
        if (data() == null) {
            return GrantResult.ALREADY_OWNED;
        }
        return switch (data().grantEntitlement(playerId, playerName, normalizedId, entitlementGrant,
                System.currentTimeMillis())) {
            case GRANTED -> GrantResult.GRANTED;
            case RENEWED -> GrantResult.RENEWED;
            case ALREADY_OWNED -> GrantResult.ALREADY_OWNED;
        };
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

    static TitleConfig parse(JsonObject root) {
        ConfigFieldReporter.warnUnknown(root, "titles",
                Set.of("format_version", "nameplate_mode", "team_conflict_policy", "titles"));
        int version = integer(root, "format_version", 1);
        if (version < 1 || version > 2) {
            throw new JsonParseException("Unsupported title format_version: " + version);
        }
        NameplateMode nameplateMode = NameplateMode.parse(optionalString(root, "nameplate_mode"));
        TeamConflictPolicy teamConflictPolicy = TeamConflictPolicy.parse(
                optionalString(root, "team_conflict_policy"));
        JsonArray titlesArray = requiredArray(root, "titles");
        Map<String, TitleDefinition> titleDefinitions = new LinkedHashMap<>();
        for (int index = 0; index < titlesArray.size(); index++) {
            JsonElement element = titlesArray.get(index);
            if (!element.isJsonObject()) {
                throw new JsonParseException("Title entry " + index + " must be an object");
            }
            JsonObject titleObject = element.getAsJsonObject();
            ConfigFieldReporter.warnUnknown(titleObject, "titles[" + index + "]",
                    Set.of("id", "display", "rarity", "effects", "tooltip"));
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
            List<String> effects = version == 1 ? parseEffectIds(titleObject, id) : List.of();
            List<TitleEffectConfig.EffectDefinition> embeddedEffects = version == 2
                    ? parseEmbeddedEffects(titleObject, id) : List.of();
            List<String> tooltip = parseStringArray(titleObject, "tooltip", id);
            titleDefinitions.put(id, new TitleDefinition(id, display, rarity, effects, tooltip,
                    embeddedEffects, version == 2));
        }

        return new TitleConfig(titleDefinitions, nameplateMode, teamConflictPolicy);
    }

    private static TitleConfig defaults() {
        Map<String, TitleDefinition> definitions = new LinkedHashMap<>();
        definitions.put("geologist", new TitleDefinition("geologist", "\u00a77[\u00a7r\u5730\u8d28\u5b66\u5bb6\u00a77] \u00a7r", TitleRarity.COMMON,
                List.of(), List.of(), List.of(TitleEffectConfig.builtIn("health_2")), true));
        definitions.put("architect", new TitleDefinition("architect", "\u00a7b[\u00a7r\u5efa\u7b51\u5e08\u00a7b] \u00a7r", TitleRarity.RARE,
                List.of(), List.of(), List.of(TitleEffectConfig.builtIn("speed_1")), true));
        definitions.put("legend", new TitleDefinition("legend", "\u00a76[\u00a7r\u4f20\u8bf4\u00a76] \u00a7r", TitleRarity.LEGENDARY,
                List.of(), List.of(), List.of(TitleEffectConfig.builtIn("resistance_1"),
                        TitleEffectConfig.builtIn("night_vision")), true));
        return new TitleConfig(definitions, NameplateMode.SCOREBOARD_TEAM, TeamConflictPolicy.OMNITOOLS_PRIORITY);
    }

    private static TitleData data() {
        return TitleData.current();
    }

    private static TitleData.PlayerRecord state(UUID playerId, String playerName) {
        TitleData data = data();
        return data == null ? null : data.record(playerId, playerName);
    }

    private static void writeDefinitions(TitleConfig config) {
        Path file = path();
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("format_version", config.titles.values().stream()
                    .anyMatch(TitleDefinition::inlineEffectsConfigured) ? 2 : 1);
            root.addProperty("nameplate_mode", config.nameplateMode.serializedName());
            root.addProperty("team_conflict_policy", config.teamConflictPolicy.serializedName());
            JsonArray definitions = new JsonArray();
            for (TitleDefinition title : config.titles.values()) {
                JsonObject titleObject = new JsonObject();
                titleObject.addProperty("id", title.id());
                titleObject.addProperty("display", title.display());
                titleObject.addProperty("rarity", title.rarity().serializedName());
                JsonArray effects = new JsonArray();
                if (title.inlineEffectsConfigured()) {
                    title.embeddedEffects().forEach(effect -> effects.add(TitleEffectConfig.toJson(effect)));
                } else {
                    title.effects().forEach(effects::add);
                }
                titleObject.add("effects", effects);
                JsonArray tooltip = new JsonArray();
                title.tooltip().forEach(tooltip::add);
                titleObject.add("tooltip", tooltip);
                definitions.add(titleObject);
            }
            root.add("titles", definitions);
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (IOException exception) {
            System.err.println("[omnitools] Could not save " + file + ": " + exception.getMessage());
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

    private static List<TitleEffectConfig.EffectDefinition> parseEmbeddedEffects(JsonObject object, String titleId) {
        JsonElement element = object.get("effects");
        if (element == null || !element.isJsonArray()) {
            throw new JsonParseException("effects for " + titleId + " must be an array in format_version 2");
        }
        List<TitleEffectConfig.EffectDefinition> effects = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (int index = 0; index < element.getAsJsonArray().size(); index++) {
            JsonElement value = element.getAsJsonArray().get(index);
            if (!value.isJsonObject()) {
                throw new JsonParseException("effects for " + titleId + " must contain objects in format_version 2");
            }
            TitleEffectConfig.EffectDefinition effect = TitleEffectConfig.parseInlineDefinition(
                    value.getAsJsonObject(), "titles." + titleId + ".effects[" + index + "]");
            if (!ids.add(effect.id())) {
                throw new JsonParseException("Effect id " + effect.id()
                        + " is configured more than once on title " + titleId);
            }
            effects.add(effect);
        }
        return List.copyOf(effects);
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

    private static String normalizeId(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

    public record TitleDefinition(String id, String display, TitleRarity rarity, List<String> effects,
                                  List<String> tooltip, List<TitleEffectConfig.EffectDefinition> embeddedEffects,
                                  boolean inlineEffectsConfigured) {
        public TitleDefinition(String id, String display, TitleRarity rarity, List<String> effects,
                               List<String> tooltip) {
            this(id, display, rarity, effects, tooltip, List.of(), false);
        }

        public TitleDefinition {
            effects = effects == null ? List.of() : List.copyOf(effects);
            tooltip = tooltip == null ? List.of() : List.copyOf(tooltip);
            embeddedEffects = embeddedEffects == null ? List.of() : List.copyOf(embeddedEffects);
            if (inlineEffectsConfigured && !effects.isEmpty()) {
                throw new IllegalArgumentException("A title cannot mix legacy effect ids with embedded effects: " + id);
            }
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
        RENEWED,
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

    /** Controls the server-side mechanism used to render title prefixes above players. */
    public enum NameplateMode {
        SCOREBOARD_TEAM("scoreboard_team"),
        DISABLED("disabled");

        private final String serializedName;

        NameplateMode(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }

        public static NameplateMode parse(String value) {
            if (value == null || value.isBlank()) {
                return SCOREBOARD_TEAM;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "scoreboard_team" -> SCOREBOARD_TEAM;
                case "disabled" -> DISABLED;
                default -> throw new IllegalArgumentException("Unknown title nameplate_mode: " + value);
            };
        }
    }

    /** Defines how OmniTools behaves when a player is already assigned to an external team. */
    public enum TeamConflictPolicy {
        OMNITOOLS_PRIORITY("omnitools_priority"),
        PRESERVE_EXTERNAL_TEAM("preserve_external_team");

        private final String serializedName;

        TeamConflictPolicy(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }

        public static TeamConflictPolicy parse(String value) {
            if (value == null || value.isBlank()) {
                return OMNITOOLS_PRIORITY;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "omnitools_priority" -> OMNITOOLS_PRIORITY;
                case "preserve_external_team" -> PRESERVE_EXTERNAL_TEAM;
                default -> throw new IllegalArgumentException("Unknown title team_conflict_policy: " + value);
            };
        }
    }

}
