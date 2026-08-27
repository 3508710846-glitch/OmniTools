package dev.modmind.omnitools.packages;

import com.google.gson.*;
import dev.modmind.omnitools.config.ConfigFieldReporter;
import dev.modmind.omnitools.config.ConfigPaths;
import dev.modmind.omnitools.config.ItemStackConfigParser;
import dev.modmind.omnitools.config.ModuleId;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Versioned package definitions parsed into immutable item prototypes. */
public record PackageConfig(int formatVersion, Settings settings, List<PackageDefinition> packages) {
    public static final int CURRENT_FORMAT_VERSION = 1;
    public static final int MAX_ENTRIES = 256;
    public static final long MAX_TOTAL_QUANTITY = 2304L * 256L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public PackageConfig {
        if (formatVersion != CURRENT_FORMAT_VERSION) throw new JsonParseException("Unsupported packages format_version: " + formatVersion);
        settings = settings == null ? Settings.defaults() : settings;
        packages = List.copyOf(packages == null ? List.of() : packages);
        if (packages.size() > 128) throw new JsonParseException("packages may contain at most 128 definitions");
        Set<String> ids = new HashSet<>();
        for (PackageDefinition definition : packages) {
            if (definition == null || !ids.add(definition.id())) throw new JsonParseException("Duplicate package id");
            long total = 0;
            for (PackageItem item : definition.items()) {
                total = Math.addExact(total, item.quantity());
                if (item.quantity() > settings.maxQuantityPerEntry()) throw new JsonParseException("Package item quantity exceeds max_quantity_per_entry");
            }
            if (total > settings.maxTotalQuantity()) throw new JsonParseException("Package total quantity exceeds configured limit");
        }
    }
    public static PackageConfig empty() { return new PackageConfig(CURRENT_FORMAT_VERSION, Settings.defaults(), List.of()); }
    public Optional<PackageDefinition> definition(String id) { if (id == null) return Optional.empty(); String key = id.trim().toLowerCase(Locale.ROOT); return packages.stream().filter(p -> p.id().equals(key)).findFirst(); }
    public static Path path() { return ConfigPaths.moduleConfig(ModuleId.PACKAGES); }
    public static PackageConfig load(HolderLookup.Provider registries) {
        Path file = path();
        if (!Files.exists(file)) { PackageConfig defaults = empty(); try { save(defaults); } catch (IOException e) { throw new IllegalStateException("Could not create package configuration", e); } return defaults; }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { JsonElement element = GSON.fromJson(reader, JsonElement.class); if (element == null || !element.isJsonObject()) throw new JsonParseException("packages configuration must be an object"); return parse(element.getAsJsonObject(), registries); }
        catch (IOException | RuntimeException e) { throw new IllegalStateException("Invalid packages configuration", e); }
    }
    static PackageConfig parse(JsonObject root, HolderLookup.Provider registries) {
        ConfigFieldReporter.warnUnknown(root, "packages", Set.of("format_version", "settings", "packages"));
        int version = integer(root, "format_version", CURRENT_FORMAT_VERSION); Settings settings = Settings.parse(object(root, "settings")); JsonElement array = root.get("packages");
        if (array == null || !array.isJsonArray()) throw new JsonParseException("packages.packages must be an array");
        List<PackageDefinition> definitions = new ArrayList<>();
        for (int i = 0; i < array.getAsJsonArray().size(); i++) { JsonElement entry = array.getAsJsonArray().get(i); if (!entry.isJsonObject()) throw new JsonParseException("packages[" + i + "] must be an object"); definitions.add(parseDefinition(entry.getAsJsonObject(), settings, registries, "packages[" + i + "]")); }
        return new PackageConfig(version, settings, definitions);
    }
    private static PackageDefinition parseDefinition(JsonObject object, Settings settings, HolderLookup.Provider registries, String context) {
        ConfigFieldReporter.warnUnknown(object, context, Set.of("id", "display", "description", "icon", "mode", "items", "version"));
        String id = requiredString(object, "id", context); String iconId = requiredString(object, "icon", context); Identifier identifier = Identifier.tryParse(iconId); Item icon = identifier == null ? null : BuiltInRegistries.ITEM.getOptional(identifier).orElse(null);
        List<String> description = new ArrayList<>(); JsonElement desc = object.get("description"); if (desc != null) { if (!desc.isJsonArray()) throw new JsonParseException(context + ".description must be an array"); for (JsonElement line : desc.getAsJsonArray()) { if (!line.isJsonPrimitive() || !line.getAsJsonPrimitive().isString()) throw new JsonParseException(context + ".description must contain strings"); description.add(line.getAsString()); } }
        JsonElement items = object.get("items"); if (items == null || !items.isJsonArray() || items.getAsJsonArray().isEmpty() || items.getAsJsonArray().size() > MAX_ENTRIES) throw new JsonParseException(context + ".items must contain 1-" + MAX_ENTRIES + " entries");
        List<PackageItem> parsedItems = new ArrayList<>(); Set<String> itemIds = new HashSet<>();
        for (int i = 0; i < items.getAsJsonArray().size(); i++) { JsonElement value = items.getAsJsonArray().get(i); if (!value.isJsonObject()) throw new JsonParseException(context + ".items[" + i + "] must be an object"); JsonObject itemObject = value.getAsJsonObject(); String itemContext = context + ".items[" + i + "]"; ConfigFieldReporter.warnUnknown(itemObject, itemContext, Set.of("id", "item", "count", "components", "nbt", "quantity")); String itemId = requiredString(itemObject, "id", itemContext); if (!itemIds.add(itemId.toLowerCase(Locale.ROOT))) throw new JsonParseException(context + " has duplicate item id " + itemId); long quantity = positiveLong(itemObject, "quantity", itemContext); if (quantity > settings.maxQuantityPerEntry()) throw new JsonParseException(itemContext + ".quantity exceeds max_quantity_per_entry"); try { JsonObject prototypeObject = itemObject.deepCopy(); prototypeObject.remove("id"); prototypeObject.remove("quantity"); var prototype = ItemStackConfigParser.parse(prototypeObject, registries, itemContext, 64); prototype.setCount(1); ItemStackConfigParser.validateRewardSnapshot(prototype, registries, itemContext, 64); parsedItems.add(new PackageItem(itemId, prototype, quantity)); } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) { throw new JsonParseException(itemContext + " has invalid item", e); } }
        int packageVersion = integer(object, "version", 1); return new PackageDefinition(id, string(object, "display", id), description, iconId, icon, PackageDefinition.Mode.parse(string(object, "mode", "all")), parsedItems, packageVersion);
    }
    private static void save(PackageConfig config) throws IOException { Path file = path(); Files.createDirectories(file.getParent()); JsonObject root = new JsonObject(); root.addProperty("format_version", config.formatVersion()); JsonObject settings = new JsonObject(); settings.addProperty("max_packages_per_player", config.settings().maxPackagesPerPlayer()); settings.addProperty("max_quantity_per_entry", config.settings().maxQuantityPerEntry()); settings.addProperty("max_total_quantity", config.settings().maxTotalQuantity()); settings.addProperty("delivery_policy", config.settings().deliveryPolicy()); settings.addProperty("random_strategy", config.settings().randomStrategy()); root.add("settings", settings); root.add("packages", new JsonArray()); try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) { GSON.toJson(root, writer); } }
    private static JsonObject object(JsonObject root, String key) { JsonElement e = root.get(key); if (e == null) return new JsonObject(); if (!e.isJsonObject()) throw new JsonParseException(key + " must be an object"); return e.getAsJsonObject(); }
    private static String string(JsonObject o, String k, String fallback) { JsonElement e=o.get(k); if(e==null)return fallback; if(!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isString()) throw new JsonParseException(k+" must be a string"); return e.getAsString(); }
    private static String requiredString(JsonObject o,String k,String c){String v=string(o,k,""); if(v.isBlank())throw new JsonParseException(c+"."+k+" must be a non-empty string"); return v.trim();}
    private static int integer(JsonObject o,String k,int f){JsonElement e=o.get(k);if(e==null)return f;if(!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isNumber())throw new JsonParseException(k+" must be an integer");try{return Integer.parseInt(e.getAsString());}catch(NumberFormatException x){throw new JsonParseException(k+" must be an integer");}}
    private static long positiveLong(JsonObject o,String k,String c){JsonElement e=o.get(k);if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isNumber())throw new JsonParseException(c+"."+k+" must be a positive integer");try{long v=Long.parseLong(e.getAsString());if(v<1)throw new JsonParseException(c+"."+k+" must be positive");return v;}catch(NumberFormatException x){throw new JsonParseException(c+"."+k+" must be a positive integer");}}
    public record Settings(int maxPackagesPerPlayer, long maxQuantityPerEntry, long maxTotalQuantity, String deliveryPolicy, String randomStrategy) {
        public Settings { if (maxPackagesPerPlayer < 1 || maxPackagesPerPlayer > 4096) throw new JsonParseException("max_packages_per_player must be 1-4096"); if (maxQuantityPerEntry < 1 || maxQuantityPerEntry > 1_000_000) throw new JsonParseException("max_quantity_per_entry must be 1-1000000"); if (maxTotalQuantity < 1 || maxTotalQuantity > MAX_TOTAL_QUANTITY) throw new JsonParseException("max_total_quantity exceeds server limit"); deliveryPolicy = deliveryPolicy == null ? "inventory_then_inbox" : deliveryPolicy.trim().toLowerCase(Locale.ROOT); if (!deliveryPolicy.equals("inventory_then_inbox")) throw new JsonParseException("delivery_policy must be inventory_then_inbox"); randomStrategy = randomStrategy == null ? "uniform" : randomStrategy.trim().toLowerCase(Locale.ROOT); if (!randomStrategy.equals("uniform")) throw new JsonParseException("random_strategy must be uniform"); }
        static Settings defaults() { return new Settings(256, 2304, MAX_TOTAL_QUANTITY, "inventory_then_inbox", "uniform"); }
        static Settings parse(JsonObject o) {
            long maxQuantity = o.has("max_quantity_per_entry") ? positiveLong(o, "max_quantity_per_entry", "settings") : 2304L;
            long maxTotal = o.has("max_total_quantity") ? positiveLong(o, "max_total_quantity", "settings") : MAX_TOTAL_QUANTITY;
            return new Settings(integer(o, "max_packages_per_player", 256), maxQuantity, maxTotal,
                    string(o, "delivery_policy", "inventory_then_inbox"), string(o, "random_strategy", "uniform"));
        }
    }
}
