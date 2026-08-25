package dev.modmind.omnitools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.loader.api.FabricLoader;
import dev.modmind.omnitools.config.ConfigPaths;
import dev.modmind.omnitools.config.ModuleId;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.RegistryOps;
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

/** Loads server-authoritative shop items from an administrator-editable JSON file. */
public final class ShopConfig {
    public static final String FILE_NAME = "omnitools-shop.json";
    public static final int PRODUCTS_PER_PAGE = 45;
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
            JsonArray products = root.isJsonArray() ? root.getAsJsonArray()
                    : root.getAsJsonObject().getAsJsonArray("products");
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
            int index = nonNegativeInt(product, "index");
            if (products.containsKey(index)) {
                throw new JsonParseException("Shop slot " + index + " is configured more than once");
            }
            long price = nonNegativeLong(product, "price");
            ItemStack stack = parseItemStack(product, registries);
            if (stack.isEmpty()) {
                throw new JsonParseException("Shop entry " + entryIndex + " cannot use an empty item stack");
            }
            products.put(index, new ShopItem(stack, price));
        }
        return new ShopConfig(products);
    }

    /**
     * Shared parser for administrator-configured item stacks. Reward definitions deliberately use
     * this exact component syntax instead of maintaining a second item-component implementation.
     */
    public static ItemStack parseItemStack(JsonObject product, HolderLookup.Provider registries)
            throws CommandSyntaxException {
        JsonElement nbtElement = product.get("nbt");
        if (nbtElement != null) {
            String nbt = requiredString(product, "nbt");
            CompoundTag tag = TagParser.parseCompoundFully(nbt);
            return ItemStack.CODEC.parse(RegistryOps.create(NbtOps.INSTANCE, registries), tag)
                    .result()
                    .orElseThrow(() -> new JsonParseException(
                            "nbt must be a valid full item-stack SNBT compound"));
        }

        String item = requiredString(product, "item");
        int count = positiveInt(product, "count");
        String components = optionalComponents(product);
        StringReader reader = new StringReader(item + (components == null ? "" : components));
        ItemParser.ItemResult result = new ItemParser(registries).parse(reader);
        if (reader.canRead()) {
            throw new JsonParseException("Unexpected text in item or components for " + item);
        }
        return new ItemInput(result.item(), result.components()).createItemStack(count, false);
    }

    private static String optionalComponents(JsonObject object) {
        JsonElement element = object.get("components");
        if (element == null) {
            return null;
        }
        // An empty object is a convenient no-component spelling for JSON generators. Non-empty
        // components continue to use the vanilla item-parser text accepted by existing shop files.
        if (element.isJsonObject() && element.getAsJsonObject().isEmpty()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new JsonParseException("components must be a vanilla item-component string or an empty object");
        }
        return element.getAsString();
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

    public record ShopItem(ItemStack stack, long price) {
        public ItemStack createStack() {
            return stack.copy();
        }
    }
}
