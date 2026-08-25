package dev.modmind.omnitools.commandmenu;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.modmind.omnitools.config.ConfigPaths;
import dev.modmind.omnitools.permissions.CommandRole;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

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

/** Loads the command-menu registry and each registered menu page into an immutable snapshot. */
public record CommandMenuConfig(int formatVersion, Map<String, CommandMenuDefinition> menus,
                                boolean allowConsoleCommands) {
    public static final int CURRENT_FORMAT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern MENU_ID = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9_.-]+\\.json");
    private static final int MAX_ITEMS = 54;
    private static final int MAX_ACTIONS = 8;

    public CommandMenuConfig {
        if (formatVersion < 1) {
            throw new JsonParseException("command_menu.format_version must be positive");
        }
        menus = Map.copyOf(menus == null ? Map.of() : menus);
    }

    public static CommandMenuConfig empty() {
        return new CommandMenuConfig(CURRENT_FORMAT_VERSION, Map.of(), false);
    }

    public static CommandMenuConfig load() {
        Path registryPath = ConfigPaths.commandMenuRegistry();
        Path menuDir = ConfigPaths.commandMenuFiles();
        try {
            Files.createDirectories(menuDir);
            if (!Files.exists(registryPath)) {
                writeEmptyRegistry(registryPath);
                return empty();
            }
            JsonObject registry = readObject(registryPath, "command menu registry");
            int version = positiveInt(registry, "format_version", CURRENT_FORMAT_VERSION);
            boolean allowConsole = booleanValue(registry, "allow_console_commands", false);
            JsonArray entries = array(registry, "menus", true);
            Map<String, CommandMenuDefinition> definitions = new LinkedHashMap<>();
            for (int index = 0; index < entries.size(); index++) {
                JsonElement element = entries.get(index);
                if (!element.isJsonObject()) {
                    throw new JsonParseException("menus[" + index + "] must be an object");
                }
                JsonObject entry = element.getAsJsonObject();
                String id = requiredString(entry, "id", "menus[" + index + "]");
                if (!MENU_ID.matcher(id).matches() || definitions.containsKey(id)) {
                    throw new JsonParseException("Invalid or duplicate menu id: " + id);
                }
                String file = requiredString(entry, "file", "menus[" + index + "]");
                validateFileName(file);
                CommandRole role = CommandRole.parse(requiredString(entry, "permission", "menus[" + index + "]"));
                Path pagePath = menuDir.resolve(file).normalize();
                if (!pagePath.getParent().equals(menuDir.toAbsolutePath().normalize())
                        && !pagePath.getParent().equals(menuDir.normalize())) {
                    throw new JsonParseException("Menu file must remain inside the menus directory: " + file);
                }
                if (!Files.exists(pagePath)) {
                    writeEmptyPage(pagePath);
                }
                CommandMenuPageConfig page = parsePage(id, pagePath, allowConsole);
                definitions.put(id, new CommandMenuDefinition(id, file, role, page));
            }
            for (CommandMenuDefinition definition : definitions.values()) {
                validateTargets(definition, definitions);
            }
            return new CommandMenuConfig(version, definitions, allowConsole);
        } catch (IOException | RuntimeException exception) {
            System.err.println("[omnitools] Could not load command menus from " + registryPath + ": "
                    + exception.getMessage() + ". The configuration snapshot will not be replaced.");
            throw new IllegalStateException("Invalid command menu configuration", exception);
        }
    }

    public boolean isEmpty() {
        return menus.isEmpty();
    }

    public CommandMenuDefinition menu(String id) {
        return menus.get(id);
    }

    public static Path registryPath() {
        return ConfigPaths.commandMenuRegistry();
    }

    private static CommandMenuPageConfig parsePage(String id, Path path, boolean allowConsole) throws IOException {
        JsonObject root = readObject(path, "menu " + id);
        int version = positiveInt(root, "format_version", CURRENT_FORMAT_VERSION);
        String title = requiredString(root, "title", "menu " + id);
        int size = integer(root, "size", 27);
        if (size != 27 && size != 54) {
            throw new JsonParseException("menu " + id + " size must be 27 or 54");
        }
        ItemStack filler = new ItemStack(net.minecraft.world.item.Items.AIR);
        String fillerName = null;
        List<String> fillerLore = List.of();
        JsonElement fillerElement = root.get("filler");
        if (fillerElement != null) {
            if (!fillerElement.isJsonObject()) {
                throw new JsonParseException("menu " + id + ".filler must be an object");
            }
            JsonObject fillerObject = fillerElement.getAsJsonObject();
            String itemId = requiredString(fillerObject, "item", "menu " + id + ".filler");
            filler = createStack(itemId, amount(fillerObject, "amount", 1), "menu " + id + ".filler");
            fillerName = optionalString(fillerObject, "name", null, "menu " + id + ".filler");
            fillerLore = parseLore(fillerObject, "menu " + id + ".filler");
        }

        Map<Integer, CommandMenuItem> items = new LinkedHashMap<>();
        JsonArray itemArray = array(root, "items", false);
        for (int index = 0; index < itemArray.size(); index++) {
            JsonElement element = itemArray.get(index);
            if (!element.isJsonObject()) {
                throw new JsonParseException("menu " + id + ".items[" + index + "] must be an object");
            }
            JsonObject itemObject = element.getAsJsonObject();
            String context = "menu " + id + ".items[" + index + "]";
            int slot = integer(itemObject, "slot", -1);
            if (slot < 0 || slot >= size || items.containsKey(slot)) {
                throw new JsonParseException(context + " slot must be unique and within 0-" + (size - 1));
            }
            ItemStack stack = createStack(requiredString(itemObject, "item", context),
                    amount(itemObject, "amount", 1), context);
            String name = optionalString(itemObject, "name", null, context);
            List<String> lore = parseLore(itemObject, context);
            boolean glow = booleanValue(itemObject, "glow", false);
            List<CommandMenuAction> left = parseActions(itemObject, "left_click", context, allowConsole);
            List<CommandMenuAction> right = parseActions(itemObject, "right_click", context, allowConsole);
            items.put(slot, new CommandMenuItem(slot, stack, name, lore, glow, left, right));
        }
        return new CommandMenuPageConfig(id, title, size, filler, fillerName, fillerLore, items);
    }

    private static List<CommandMenuAction> parseActions(JsonObject object, String key, String context,
                                                         boolean allowConsole) {
        JsonArray array = array(object, key, false);
        if (array.size() > MAX_ACTIONS) {
            throw new JsonParseException(context + "." + key + " may contain at most " + MAX_ACTIONS + " actions");
        }
        List<CommandMenuAction> result = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            result.add(CommandMenuAction.parse(array.get(index), context + "." + key + "[" + index + "]",
                    allowConsole));
        }
        return List.copyOf(result);
    }

    private static void validateTargets(CommandMenuDefinition definition,
                                        Map<String, CommandMenuDefinition> definitions) {
        for (CommandMenuItem item : definition.page().items().values()) {
            for (CommandMenuAction action : concat(item.leftClick(), item.rightClick())) {
                if (action.type() == CommandMenuAction.Type.OPEN_MENU && !definitions.containsKey(action.value())) {
                    throw new JsonParseException("menu " + definition.id() + " references unknown menu " + action.value());
                }
            }
        }
    }

    private static List<CommandMenuAction> concat(List<CommandMenuAction> left, List<CommandMenuAction> right) {
        List<CommandMenuAction> all = new ArrayList<>(left);
        all.addAll(right);
        return all;
    }

    private static ItemStack createStack(String value, int amount, String context) {
        Identifier id = Identifier.tryParse(value);
        if (id == null) {
            throw new JsonParseException(context + " item is not a valid identifier: " + value);
        }
        Item item = BuiltInRegistries.ITEM.get(id).map(holder -> holder.value())
                .orElseThrow(() -> new JsonParseException(context + " references unknown item: " + id));
        if (item == net.minecraft.world.item.Items.AIR) {
            throw new JsonParseException(context + " cannot use minecraft:air");
        }
        return new ItemStack(item, amount);
    }

    private static List<String> parseLore(JsonObject object, String context) {
        JsonArray array = array(object, "lore", false);
        List<String> lore = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            JsonElement element = array.get(index);
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new JsonParseException(context + ".lore[" + index + "] must be a string");
            }
            lore.add(element.getAsString());
        }
        if (lore.size() > net.minecraft.world.item.component.ItemLore.MAX_LINES) {
            throw new JsonParseException(context + ".lore may contain at most "
                    + net.minecraft.world.item.component.ItemLore.MAX_LINES + " lines");
        }
        return List.copyOf(lore);
    }

    private static void validateFileName(String file) {
        if (!FILE_NAME.matcher(file).matches() || file.contains("..") || file.contains("/") || file.contains("\\")
                || Path.of(file).isAbsolute()) {
            throw new JsonParseException("Menu file must be a single safe .json filename: " + file);
        }
    }

    private static JsonObject readObject(Path path, String context) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement root = GSON.fromJson(reader, JsonElement.class);
            if (root == null || !root.isJsonObject()) {
                throw new JsonParseException(context + " must be an object");
            }
            return root.getAsJsonObject();
        }
    }

    private static void writeEmptyRegistry(Path path) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("format_version", CURRENT_FORMAT_VERSION);
        root.add("menus", new JsonArray());
        root.addProperty("allow_console_commands", false);
        Files.createDirectories(path.getParent());
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
    }

    private static void writeEmptyPage(Path path) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("format_version", CURRENT_FORMAT_VERSION);
        root.addProperty("title", "空菜单");
        root.addProperty("size", 27);
        root.add("items", new JsonArray());
        Files.createDirectories(path.getParent());
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
    }

    private static JsonArray array(JsonObject object, String key, boolean required) {
        JsonElement element = object.get(key);
        if (element == null && !required) {
            return new JsonArray();
        }
        if (element == null || !element.isJsonArray()) {
            throw new JsonParseException(key + " must be an array");
        }
        return element.getAsJsonArray();
    }

    private static String requiredString(JsonObject object, String key, String context) {
        String value = optionalString(object, key, null, context);
        if (value == null || value.isBlank()) {
            throw new JsonParseException(context + " requires a non-empty " + key);
        }
        return value.trim();
    }

    private static String optionalString(JsonObject object, String key, String fallback, String context) {
        JsonElement element = object.get(key);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new JsonParseException(context + "." + key + " must be a string");
        }
        return element.getAsString();
    }

    private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
        JsonElement element = object.get(key);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new JsonParseException(key + " must be boolean");
        }
        return element.getAsBoolean();
    }

    private static int positiveInt(JsonObject object, String key, int fallback) {
        int value = integer(object, key, fallback);
        if (value < 1) {
            throw new JsonParseException(key + " must be positive");
        }
        return value;
    }

    private static int amount(JsonObject object, String key, int fallback) {
        int value = integer(object, key, fallback);
        if (value < 1 || value > 64) {
            throw new JsonParseException(key + " must be between 1 and 64");
        }
        return value;
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
}
