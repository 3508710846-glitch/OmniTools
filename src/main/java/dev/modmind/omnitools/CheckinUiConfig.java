package dev.modmind.omnitools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

/** Immutable presentation settings for the server-side daily check-in journal. */
public record CheckinUiConfig(Style style, boolean showWeekday, boolean showProgressBar,
                              boolean showActionHints, boolean showRewardPreview, Icons icons, Sounds sounds) {
    public CheckinUiConfig {
        style = style == null ? Style.JOURNAL : style;
        icons = icons == null ? Icons.defaults() : icons;
        sounds = sounds == null ? Sounds.defaults() : sounds;
    }

    public static CheckinUiConfig defaults() {
        return new CheckinUiConfig(Style.JOURNAL, true, true, true, true, Icons.defaults(), Sounds.defaults());
    }

    void validateItems() {
        icons.available();
        icons.signed();
        icons.pastSigned();
        icons.missed();
        icons.future();
        icons.milestone();
        icons.empty();
    }

    static CheckinUiConfig parse(JsonObject root) {
        JsonElement element = root.get("ui");
        if (element == null) {
            return defaults();
        }
        if (!element.isJsonObject()) {
            throw new JsonParseException("ui must be an object");
        }
        JsonObject ui = element.getAsJsonObject();
        Style style = Style.parse(optionalString(ui, "style", "journal", "ui"));
        boolean showWeekday = optionalBoolean(ui, "show_weekday", true, "ui");
        boolean showProgressBar = optionalBoolean(ui, "show_progress_bar", true, "ui");
        boolean showActionHints = optionalBoolean(ui, "show_action_hints", true, "ui");
        boolean showRewardPreview = optionalBoolean(ui, "show_reward_preview", true, "ui");
        Icons icons = Icons.parse(optionalObject(ui, "icons", "ui"));
        Sounds sounds = Sounds.parse(optionalObject(ui, "sounds", "ui"));
        return new CheckinUiConfig(style, showWeekday, showProgressBar, showActionHints, showRewardPreview,
                icons, sounds);
    }

    static void writeDefault(JsonObject root) {
        JsonObject ui = new JsonObject();
        ui.addProperty("style", "journal");
        ui.addProperty("show_weekday", true);
        ui.addProperty("show_progress_bar", true);
        ui.addProperty("show_action_hints", true);
        ui.addProperty("show_reward_preview", true);
        JsonObject icons = new JsonObject();
        icons.addProperty("available", "minecraft:clock");
        icons.addProperty("signed", "minecraft:book");
        icons.addProperty("past_signed", "minecraft:lime_dye");
        icons.addProperty("missed", "minecraft:red_dye");
        icons.addProperty("future", "minecraft:paper");
        icons.addProperty("milestone", "minecraft:chest");
        icons.addProperty("empty", "minecraft:map");
        ui.add("icons", icons);
        JsonObject sounds = new JsonObject();
        sounds.addProperty("open", true);
        sounds.addProperty("click", true);
        sounds.addProperty("success", true);
        sounds.addProperty("failure", true);
        ui.add("sounds", sounds);
        root.add("ui", ui);
    }

    public enum Style {
        JOURNAL("journal");

        private final String serializedName;

        Style(String serializedName) {
            this.serializedName = serializedName;
        }

        public static Style parse(String value) {
            if (JOURNAL.serializedName.equalsIgnoreCase(value)) {
                return JOURNAL;
            }
            throw new JsonParseException("ui.style must be journal");
        }
    }

    public record Icons(String availableId, String signedId, String pastSignedId, String missedId, String futureId,
                        String milestoneId, String emptyId) {
        public Icons {
            availableId = requireItemId(availableId, "available");
            signedId = requireItemId(signedId, "signed");
            pastSignedId = requireItemId(pastSignedId, "past_signed");
            missedId = requireItemId(missedId, "missed");
            futureId = requireItemId(futureId, "future");
            milestoneId = requireItemId(milestoneId, "milestone");
            emptyId = requireItemId(emptyId, "empty");
        }

        public static Icons defaults() {
            return new Icons("minecraft:clock", "minecraft:book", "minecraft:lime_dye", "minecraft:red_dye",
                    "minecraft:paper", "minecraft:chest", "minecraft:map");
        }

        private static Icons parse(JsonObject object) {
            Icons defaults = defaults();
            if (object == null) {
                return defaults;
            }
            return new Icons(itemId(object, "available", defaults.availableId),
                    itemId(object, "signed", defaults.signedId),
                    itemId(object, "past_signed", defaults.pastSignedId),
                    itemId(object, "missed", defaults.missedId),
                    itemId(object, "future", defaults.futureId),
                    itemId(object, "milestone", defaults.milestoneId), itemId(object, "empty", defaults.emptyId));
        }

        public Item available() {
            return resolve(availableId, "available");
        }

        public Item signed() {
            return resolve(signedId, "signed");
        }

        public Item pastSigned() {
            return resolve(pastSignedId, "past_signed");
        }

        public Item missed() {
            return resolve(missedId, "missed");
        }

        public Item future() {
            return resolve(futureId, "future");
        }

        public Item milestone() {
            return resolve(milestoneId, "milestone");
        }

        public Item empty() {
            return resolve(emptyId, "empty");
        }
    }

    public record Sounds(boolean open, boolean click, boolean success, boolean failure) {
        public static Sounds defaults() {
            return new Sounds(true, true, true, true);
        }

        private static Sounds parse(JsonObject object) {
            if (object == null) {
                return defaults();
            }
            return new Sounds(optionalBoolean(object, "open", true, "ui.sounds"),
                    optionalBoolean(object, "click", true, "ui.sounds"),
                    optionalBoolean(object, "success", true, "ui.sounds"),
                    optionalBoolean(object, "failure", true, "ui.sounds"));
        }
    }

    private static String itemId(JsonObject object, String key, String fallback) {
        String value = optionalString(object, key, null, "ui.icons");
        if (value == null) {
            return fallback;
        }
        return requireItemId(value, key);
    }

    private static String requireItemId(String value, String key) {
        Identifier id = Identifier.tryParse(value);
        if (id == null || !value.startsWith("minecraft:")) {
            throw new JsonParseException("ui.icons." + key + " must be a vanilla minecraft item id");
        }
        if (value.equals("minecraft:air")) {
            throw new JsonParseException("ui.icons." + key + " cannot be minecraft:air");
        }
        return value;
    }

    private static Item resolve(String value, String key) {
        Identifier id = Identifier.tryParse(value);
        if (id == null) {
            throw new IllegalStateException("Validated check-in icon id is malformed: " + key);
        }
        return BuiltInRegistries.ITEM.get(id).map(holder -> holder.value()).orElseThrow(() ->
                new IllegalStateException("Validated check-in icon no longer exists: " + value));
    }

    private static JsonObject optionalObject(JsonObject object, String key, String context) {
        JsonElement element = object.get(key);
        if (element == null) {
            return null;
        }
        if (!element.isJsonObject()) {
            throw new JsonParseException(context + "." + key + " must be an object");
        }
        return element.getAsJsonObject();
    }

    private static String optionalString(JsonObject object, String key, String fallback, String context) {
        JsonElement element = object.get(key);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                || element.getAsString().isBlank()) {
            throw new JsonParseException(context + "." + key + " must be a non-empty string");
        }
        return element.getAsString().trim();
    }

    private static boolean optionalBoolean(JsonObject object, String key, boolean fallback, String context) {
        JsonElement element = object.get(key);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new JsonParseException(context + "." + key + " must be boolean");
        }
        return element.getAsBoolean();
    }
}
