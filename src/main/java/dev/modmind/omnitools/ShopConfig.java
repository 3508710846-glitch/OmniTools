package dev.modmind.omnitools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.loader.api.FabricLoader;
import dev.modmind.omnitools.config.ConfigPaths;
import dev.modmind.omnitools.config.ConfigFieldReporter;
import dev.modmind.omnitools.config.ItemStackConfigParser;
import dev.modmind.omnitools.config.ModuleId;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loads server-authoritative shop items from an administrator-editable JSON file. */
public final class ShopConfig {
    public static final String FILE_NAME = "omnitools-shop.json";
    public static final int PRODUCTS_PER_PAGE = 45;
    public static final int CURRENT_FORMAT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = ConfigPaths.moduleConfig(ModuleId.SHOP);

    private final Map<Integer, ShopItem> products;
    private final int pageCount;

    private ShopConfig(Map<Integer, ShopItem> products) {
        this.products = Map.copyOf(products);
        int highestIndex = products.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
        this.pageCount = Math.max(1, highestIndex / PRODUCTS_PER_PAGE + 1);
    }

    public static ShopConfig load(HolderLookup.Provider registries) {
        if (!Files.exists(FILE)) {
            ShopConfig defaults = defaults(registries);
            writeDefault();
            return defaults;
        }

        try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            JsonElement root = GSON.fromJson(reader, JsonElement.class);
            if (root == null || (!root.isJsonArray() && !root.isJsonObject())) {
                throw new JsonParseException("Root value must be an array or object of shop products");
            }
            JsonArray products;
            if (root.isJsonArray()) {
                products = root.getAsJsonArray();
            } else {
                JsonObject object = root.getAsJsonObject();
                ConfigFieldReporter.warnUnknown(object, "shop", Set.of("format_version", "products"));
                int version = integer(object, "format_version", CURRENT_FORMAT_VERSION);
                if (version < 1 || version > CURRENT_FORMAT_VERSION) {
                    throw new JsonParseException("Unsupported shop format_version: " + version);
                }
                products = object.getAsJsonArray("products");
            }
            if (products == null) {
                throw new JsonParseException("products must be an array");
            }
            return parse(products, registries);
        } catch (IOException | JsonParseException | CommandSyntaxException exception) {
            System.err.println("[omnitools] Could not load " + FILE + ": " + exception.getMessage()
                    + ". The configuration snapshot will not be replaced.");
            throw new IllegalStateException("Invalid shop configuration", exception);
        }
    }

    public static ShopConfig empty() {
        return new ShopConfig(Map.of());
    }

    public ShopItem get(int index) {
        return products.get(index);
    }

    public int pageCount() {
        return pageCount;
    }

    public int productCount() {
        return products.size();
    }

    public List<ShopItem> products() {
        return List.copyOf(products.values());
    }

    /** Highest configured position; retained so a visual layout can preserve sparse indexes. */
    public int highestProductIndex() {
        return products.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
    }

    public static Path path() {
        return FILE;
    }

    private static ShopConfig parse(JsonArray array, HolderLookup.Provider registries)
            throws CommandSyntaxException {
        Map<Integer, ShopItem> products = new LinkedHashMap<>();
        for (int entryIndex = 0; entryIndex < array.size(); entryIndex++) {
            JsonElement element = array.get(entryIndex);
            if (!element.isJsonObject()) {
                throw new JsonParseException("Shop entry " + entryIndex + " must be an object");
            }
            JsonObject product = element.getAsJsonObject();
            ConfigFieldReporter.warnUnknown(product, "shop.products[" + entryIndex + "]",
                    Set.of("index", "type", "item", "count", "components", "nbt", "package", "price"));
            int index = nonNegativeInt(product, "index");
            if (products.containsKey(index)) {
                throw new JsonParseException("Shop slot " + index + " is configured more than once");
            }
            long price = nonNegativeLong(product, "price");
            ProductType type = ProductType.parse(optionalString(product, "type"));
            if (type == ProductType.PACKAGE) {
                if (product.has("item") || product.has("count") || product.has("components") || product.has("nbt")) {
                    throw new JsonParseException("Shop package entry " + entryIndex
                            + " cannot contain item, count, components, or nbt");
                }
                products.put(index, new ShopItem(type, new ItemStack(Items.CHEST),
                        requiredString(product, "package"), price));
            } else {
                if (product.has("package")) {
                    throw new JsonParseException("Shop item entry " + entryIndex + " cannot contain package");
                }
                ItemStack stack = ItemStackConfigParser.parse(product, registries, "Shop entry " + entryIndex,
                        Integer.MAX_VALUE);
                if (stack.isEmpty()) {
                    throw new JsonParseException("Shop entry " + entryIndex + " cannot use an empty item stack");
                }
                products.put(index, new ShopItem(type, stack, "", price));
            }
        }
        return new ShopConfig(products);
    }

    /** @deprecated Use {@link ItemStackConfigParser#parse(JsonObject, HolderLookup.Provider, String, int)}. */
    @Deprecated(forRemoval = false)
    public static ItemStack parseItemStack(JsonObject product, HolderLookup.Provider registries)
            throws CommandSyntaxException {
        return ItemStackConfigParser.parse(product, registries, "Shop item", Integer.MAX_VALUE);
    }

    private static int nonNegativeInt(JsonObject object, String key) {
        long value = nonNegativeLong(object, key);
        if (value > Integer.MAX_VALUE) {
            throw new JsonParseException(key + " is too large");
        }
        return (int) value;
    }

    private static int positiveInt(JsonObject object, String key) {
        long value = nonNegativeLong(object, key);
        if (value < 1L || value > Integer.MAX_VALUE) {
            throw new JsonParseException(key + " must be an integer between 1 and " + Integer.MAX_VALUE);
        }
        return (int) value;
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

    private static ShopConfig defaults(HolderLookup.Provider registries) {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("format_version", 1);
            JsonArray defaults = new JsonArray();
            JsonObject diamond = new JsonObject();
            diamond.addProperty("index", 0);
            diamond.addProperty("item", "minecraft:diamond");
            diamond.addProperty("count", 1);
            diamond.addProperty("price", 20);
            defaults.add(diamond);
            return parse(defaults, registries);
        } catch (CommandSyntaxException exception) {
            throw new IllegalStateException("Could not create the built-in shop product", exception);
        }
    }

    private static void writeDefault() {
        try {
            Files.createDirectories(FILE.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("format_version", 1);
            JsonArray defaults = new JsonArray();
            JsonObject diamond = new JsonObject();
            diamond.addProperty("index", 0);
            diamond.addProperty("item", "minecraft:diamond");
            diamond.addProperty("count", 1);
            diamond.addProperty("price", 20);
            defaults.add(diamond);
            root.add("products", defaults);
            try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            System.out.println("[omnitools] Created default shop config at " + FILE);
        } catch (IOException exception) {
            System.err.println("[omnitools] Could not create default shop config at " + FILE + ": "
                    + exception.getMessage());
        }
    }

    public enum ProductType {
        ITEM,
        PACKAGE;

        static ProductType parse(String value) {
            if (value == null || value.isBlank()) {
                return ITEM;
            }
            try {
                return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new JsonParseException("shop product type must be item or package", exception);
            }
        }
    }

    public record ShopItem(ProductType type, ItemStack stack, String packageId, long price) {
        public ShopItem {
            type = type == null ? ProductType.ITEM : type;
            stack = stack == null ? ItemStack.EMPTY : stack.copy();
            packageId = packageId == null ? "" : packageId.trim().toLowerCase(java.util.Locale.ROOT);
            if (price < 0L) {
                throw new IllegalArgumentException("shop price must be non-negative");
            }
            if (type == ProductType.ITEM && stack.isEmpty()) {
                throw new IllegalArgumentException("item shop product cannot be empty");
            }
            if (type == ProductType.PACKAGE && !packageId.matches("[a-z0-9_.-]{1,64}")) {
                throw new IllegalArgumentException("invalid shop package id");
            }
        }

        /** Compatibility constructor for integrations using the original item-only product record. */
        public ShopItem(ItemStack stack, long price) {
            this(ProductType.ITEM, stack, "", price);
        }

        @Override
        public ItemStack stack() {
            return stack.copy();
        }

        public ItemStack createStack() {
            return type == ProductType.ITEM ? stack.copy() : ItemStack.EMPTY;
        }

        public ItemStack createDisplayStack() {
            return stack.copy();
        }
    }
}
