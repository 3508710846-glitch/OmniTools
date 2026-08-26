package dev.modmind.omnitools.reward;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.modmind.omnitools.config.ItemStackConfigParser;
import dev.modmind.omnitools.entitlement.TimedEntitlement;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable, validated definition of one idempotent reward effect. */
public record RewardDefinition(String id, RewardType type, long amount, ItemStack itemStack,
                               String titleId, TimedEntitlement.Grant titleGrant, String command) {
    public static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_.-]{1,64}");
    public static final int MAX_ITEM_COUNT = 64;
    public static final int MAX_EVENT_ITEM_COUNT = 2_304;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");
    private static final Pattern TEXT_PLACEHOLDER = Pattern.compile("%[^%\\r\\n]+%");
    private static final Set<String> ALLOWED_PLACEHOLDERS = Set.of(
            "player_name", "player_uuid", "player_x", "player_y", "player_z", "player_world");

    public RewardDefinition {
        id = normalizeId(id);
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid reward id: " + id);
        }
        if (type == null) {
            throw new IllegalArgumentException("Reward type is required");
        }
        if (amount < 0L) {
            throw new IllegalArgumentException("Reward amount must be non-negative");
        }
        itemStack = itemStack == null ? ItemStack.EMPTY : itemStack.copy();
        titleId = titleId == null ? "" : titleId.trim().toLowerCase(Locale.ROOT);
        titleGrant = titleGrant == null ? TimedEntitlement.permanentGrant() : titleGrant;
        command = command == null ? "" : command;
    }

    /** Retained for existing callers that create a permanent title or non-title reward directly. */
    public RewardDefinition(String id, RewardType type, long amount, ItemStack itemStack, String titleId,
                            String command) {
        this(id, type, amount, itemStack, titleId, TimedEntitlement.permanentGrant(), command);
    }

    public ItemStack createItemStack() {
        return itemStack.copy();
    }

    public static List<RewardDefinition> parseArray(JsonElement element, String context,
                                                     HolderLookup.Provider registries) {
        if (element == null || !element.isJsonArray()) {
            throw new JsonParseException(context + " must be an array");
        }
        JsonArray array = element.getAsJsonArray();
        List<RewardDefinition> definitions = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int totalItems = 0;
        for (int index = 0; index < array.size(); index++) {
            JsonElement entry = array.get(index);
            if (!entry.isJsonObject()) {
                throw new JsonParseException(context + "[" + index + "] must be an object");
            }
            RewardDefinition definition = parse(entry.getAsJsonObject(), context + "[" + index + "]", registries);
            if (!ids.add(definition.id())) {
                throw new JsonParseException(context + " has duplicate reward id " + definition.id());
            }
            if (definition.type() == RewardType.ITEM) {
                totalItems += definition.createItemStack().getCount();
                if (totalItems > MAX_EVENT_ITEM_COUNT) {
                    throw new JsonParseException(context + " exceeds " + MAX_EVENT_ITEM_COUNT + " item rewards");
                }
            }
            definitions.add(definition);
        }
        return List.copyOf(definitions);
    }

    public static RewardDefinition currency(String id, long amount) {
        return new RewardDefinition(id, RewardType.CURRENCY, amount, ItemStack.EMPTY, "", "");
    }

    public static RewardDefinition title(String id, String titleId) {
        return new RewardDefinition(id, RewardType.TITLE, 0L, ItemStack.EMPTY, titleId,
                TimedEntitlement.permanentGrant(), "");
    }

    private static RewardDefinition parse(JsonObject object, String context, HolderLookup.Provider registries) {
        String id = requiredString(object, "id", context);
        RewardType type = RewardType.parse(requiredString(object, "type", context));
        return switch (type) {
            case CURRENCY -> {
                rejectTitleTimingFields(object, context);
                yield currency(id, nonNegativeLong(object, "amount", context));
            }
            case ITEM -> {
                rejectTitleTimingFields(object, context);
                yield parseItem(id, object, context, registries);
            }
            case TITLE -> parseTitle(id, object, context);
            case COMMAND -> {
                rejectTitleTimingFields(object, context);
                yield parseCommand(id, object, context);
            }
        };
    }

    private static RewardDefinition parseTitle(String id, JsonObject object, String context) {
        String titleId = requiredId(object, "title", context);
        return new RewardDefinition(id, RewardType.TITLE, 0L, ItemStack.EMPTY, titleId,
                parseTitleGrant(object, context), "");
    }

    static TimedEntitlement.Grant parseTitleGrant(JsonObject object, String context) {
        JsonElement duration = object.get("duration");
        if (duration == null) {
            if (object.has("renewal")) {
                throw new JsonParseException(context + ".renewal requires duration.mode active_days");
            }
            return TimedEntitlement.permanentGrant();
        }
        if (!duration.isJsonObject()) {
            throw new JsonParseException(context + ".duration must be an object");
        }
        JsonObject durationObject = duration.getAsJsonObject();
        TimedEntitlement.Mode mode;
        try {
            mode = TimedEntitlement.Mode.parse(requiredString(durationObject, "mode", context + ".duration"));
        } catch (IllegalArgumentException exception) {
            throw new JsonParseException(context + ".duration.mode must be permanent or active_days", exception);
        }
        if (mode == TimedEntitlement.Mode.PERMANENT) {
            if (durationObject.has("days")) {
                throw new JsonParseException(context + ".duration.days is not valid for permanent titles");
            }
            try {
                TimedEntitlement.RenewalPolicy.parse(optionalString(object, "renewal"));
            } catch (IllegalArgumentException exception) {
                throw new JsonParseException(context + " has an invalid title renewal", exception);
            }
            return TimedEntitlement.permanentGrant();
        }
        long days = positiveLong(durationObject, "days", context + ".duration");
        try {
            return TimedEntitlement.Grant.activeDays(days, TimedEntitlement.RenewalPolicy.parse(
                    optionalString(object, "renewal")));
        } catch (IllegalArgumentException exception) {
            throw new JsonParseException(context + " has invalid title duration or renewal", exception);
        }
    }

    private static void rejectTitleTimingFields(JsonObject object, String context) {
        if (object.has("duration") || object.has("renewal")) {
            throw new JsonParseException(context + " duration and renewal are only valid for title rewards");
        }
    }

    private static RewardDefinition parseItem(String id, JsonObject object, String context,
                                              HolderLookup.Provider registries) {
        try {
            ItemStack parsed = ItemStackConfigParser.parse(object, registries, context, MAX_ITEM_COUNT);
            ItemStackConfigParser.validateRewardSnapshot(parsed, registries, context, MAX_ITEM_COUNT);
            return new RewardDefinition(id, RewardType.ITEM, 0L, parsed, "", "");
        } catch (CommandSyntaxException exception) {
            throw new JsonParseException(context + " has invalid item or components: " + exception.getMessage());
        }
    }

    private static RewardDefinition parseCommand(String id, JsonObject object, String context) {
        String runAs = requiredString(object, "run_as", context).toLowerCase(Locale.ROOT);
        if (!runAs.equals("console")) {
            throw new JsonParseException(context + ".run_as must be console");
        }
        String command = requiredString(object, "command", context);
        validateCommandText(command, context);
        return new RewardDefinition(id, RewardType.COMMAND, 0L, ItemStack.EMPTY, "", command);
    }

    public static void validateCommandText(String command, String context) {
        if (command == null || command.isBlank()) {
            throw new JsonParseException(context + ".command must be a non-empty string");
        }
        if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
            throw new JsonParseException(context + ".command must not contain a line break");
        }
        if (TEXT_PLACEHOLDER.matcher(command).find()) {
            throw new JsonParseException(context + ".command must not use text placeholders; use {player_*} values only");
        }
        Matcher matcher = PLACEHOLDER.matcher(command);
        while (matcher.find()) {
            if (!ALLOWED_PLACEHOLDERS.contains(matcher.group(1))) {
                throw new JsonParseException(context + ".command uses an unknown placeholder {"
                        + matcher.group(1) + "}");
            }
        }
        String withoutPlaceholders = matcherReplace(command);
        if (withoutPlaceholders.contains("{") || withoutPlaceholders.contains("}")) {
            throw new JsonParseException(context + ".command contains an invalid placeholder");
        }
    }

    private static String matcherReplace(String command) {
        return PLACEHOLDER.matcher(command).replaceAll("");
    }

    private static String requiredId(JsonObject object, String key, String context) {
        String id = normalizeId(requiredString(object, key, context));
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new JsonParseException(context + "." + key + " must match " + ID_PATTERN.pattern());
        }
        return id;
    }

    private static String requiredString(JsonObject object, String key, String context) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                || element.getAsString().isBlank()) {
            throw new JsonParseException(context + "." + key + " must be a non-empty string");
        }
        return element.getAsString().trim();
    }

    private static long nonNegativeLong(JsonObject object, String key, String context) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException(context + "." + key + " must be a non-negative integer");
        }
        try {
            long value = Long.parseLong(element.getAsString());
            if (value < 0L) {
                throw new JsonParseException(context + "." + key + " must be a non-negative integer");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new JsonParseException(context + "." + key + " must be a non-negative integer");
        }
    }

    private static long positiveLong(JsonObject object, String key, String context) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException(context + "." + key + " must be a positive integer");
        }
        try {
            long value = Long.parseLong(element.getAsString());
            if (value < 1L) {
                throw new JsonParseException(context + "." + key + " must be a positive integer");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new JsonParseException(context + "." + key + " must be a positive integer");
        }
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

    private static String normalizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
