package dev.modmind.omnitools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import dev.modmind.omnitools.config.ConfigPaths;
import dev.modmind.omnitools.config.ModuleId;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Administrator-editable effect definitions referenced by title ids. */
public final class TitleEffectConfig {
    public static final String FILE_NAME = "omnitools-title-effects.json";
    private static final Pattern EFFECT_ID = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = ConfigPaths.moduleConfig(ModuleId.TITLE_EFFECTS);

    private final Map<String, EffectDefinition> effects;

    private TitleEffectConfig(Map<String, EffectDefinition> effects) {
        this.effects = new LinkedHashMap<>(effects);
    }

    public static TitleEffectConfig load() {
        if (!Files.exists(FILE)) {
            TitleEffectConfig defaults = defaults();
            defaults.save();
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
            throw new IllegalStateException("Invalid title effect configuration", exception);
        }
    }

    public static TitleEffectConfig empty() {
        return new TitleEffectConfig(Map.of());
    }

    public static Path path() {
        return FILE;
    }

    public synchronized List<EffectDefinition> definitions() {
        return List.copyOf(effects.values());
    }

    public synchronized Optional<EffectDefinition> definition(String id) {
        return Optional.ofNullable(effects.get(normalizeId(id)));
    }

    private static TitleEffectConfig parse(JsonObject root) {
        JsonObject definitions = root;
        JsonElement wrapped = root.get("effects");
        if (wrapped != null && wrapped.isJsonObject()) {
            definitions = wrapped.getAsJsonObject();
        }

        Map<String, EffectDefinition> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : definitions.entrySet()) {
            if ("format_version".equals(entry.getKey())) {
                continue;
            }
            String id = normalizeId(entry.getKey());
            if (!EFFECT_ID.matcher(id).matches() || "effects".equals(id)) {
                throw new JsonParseException("Effect id " + entry.getKey() + " must match " + EFFECT_ID.pattern());
            }
            if (!entry.getValue().isJsonObject()) {
                throw new JsonParseException("Effect " + id + " must be an object");
            }
            if (result.containsKey(id)) {
                throw new JsonParseException("Effect id " + id + " is configured more than once");
            }
            result.put(id, parseDefinition(id, entry.getValue().getAsJsonObject()));
        }
        return new TitleEffectConfig(result);
    }

    private static EffectDefinition parseDefinition(String id, JsonObject object) {
        String name = optionalString(object, "name", id);
        EffectType type = EffectType.parse(requiredString(object, "type"));
        String effect = optionalString(object, "effect", "");
        int amplifier = optionalInt(object, "amplifier", 0);
        int duration = optionalInt(object, "duration", -1);
        String attribute = optionalString(object, "attribute", "");
        AttributeOperation operation = AttributeOperation.parse(optionalString(object, "operation", "ADDITION"));
        double amount = optionalDouble(object, "amount", 0.0D);
        String particle = optionalString(object, "particle", "");
        int frequency = optionalInt(object, "frequency", 10);
        String permission = optionalString(object, "permission", "");
        String display = optionalString(object, "display", name);

        switch (type) {
            case POTION -> {
                if (effect.isBlank()) {
                    throw new JsonParseException("Potion effect " + id + " requires effect");
                }
                if (amplifier < 0 || duration == 0 || duration < -1) {
                    throw new JsonParseException("Potion effect " + id + " has invalid amplifier or duration");
                }
            }
            case ATTRIBUTE -> {
                if (attribute.isBlank() || !Double.isFinite(amount)) {
                    throw new JsonParseException("Attribute effect " + id + " requires attribute and finite amount");
                }
            }
            case PARTICLE -> {
                if (particle.isBlank() || frequency < 1) {
                    throw new JsonParseException("Particle effect " + id + " requires particle and positive frequency");
                }
            }
            case PERMISSION -> {
                if (permission.isBlank()) {
                    throw new JsonParseException("Permission effect " + id + " requires permission");
                }
            }
        }
        if (type == EffectType.PERMISSION) {
            Identifier permissionId = Identifier.tryParse(permission);
            if (permissionId == null) {
                throw new JsonParseException("Permission effect " + id + " contains an invalid permission id");
            }
            permission = permissionId.toString();
        } else if (Identifier.tryParse(switch (type) {
            case POTION -> effect;
            case ATTRIBUTE -> attribute;
            case PARTICLE -> particle;
            case PERMISSION -> throw new IllegalStateException("Handled above");
        }) == null) {
            throw new JsonParseException("Effect " + id + " contains an invalid Minecraft identifier");
        }
        return new EffectDefinition(id, name, type, effect, amplifier, duration, attribute, operation, amount,
                particle, frequency, permission, display);
    }

    private static TitleEffectConfig defaults() {
        Map<String, EffectDefinition> defaults = new LinkedHashMap<>();
        defaults.put("speed_1", potion("speed_1", "\u901f\u5ea6 I", "minecraft:speed", 0,
                "\u00a7a\u79fb\u52a8\u901f\u5ea6\u63d0\u5347 20%"));
        defaults.put("speed_2", potion("speed_2", "\u901f\u5ea6 II", "minecraft:speed", 1,
                "\u00a7a\u79fb\u52a8\u901f\u5ea6\u63d0\u5347 40%"));
        defaults.put("resistance_1", potion("resistance_1", "\u6297\u6027\u63d0\u5347 I", "minecraft:resistance", 0,
                "\u00a7a\u6297\u6027\u63d0\u5347 I\uff08\u51cf\u5c11\u6240\u53d7\u4f24\u5bb3\uff09"));
        defaults.put("health_2", new EffectDefinition("health_2", "\u751f\u547d\u63d0\u5347 II", EffectType.ATTRIBUTE, "", 0, -1,
                "minecraft:generic.max_health", AttributeOperation.ADDITION, 4.0D, "", 10, "",
                "\u00a7c\u2665 \u751f\u547d\u4e0a\u9650 +4"));
        defaults.put("night_vision", potion("night_vision", "\u591c\u89c6", "minecraft:night_vision", 0,
                "\u00a7f\u6c38\u4e45\u591c\u89c6\uff08\u65e0\u9700\u836f\u6c34\uff09"));
        defaults.put("fire_resistance", potion("fire_resistance", "\u9632\u706b", "minecraft:fire_resistance", 0,
                "\u00a76\u514d\u75ab\u706b\u7130\u4f24\u5bb3"));
        defaults.put("particle_redstone", new EffectDefinition("particle_redstone", "\u7ea2\u77f3\u7c92\u5b50", EffectType.PARTICLE,
                "", 0, -1, "", AttributeOperation.ADDITION, 0.0D, "minecraft:redstone", 10, "",
                "\u00a7c\u884c\u8d70\u65f6\u98d8\u843d\u7ea2\u77f3\u7c92\u5b50"));
        return new TitleEffectConfig(defaults);
    }

    private static EffectDefinition potion(String id, String name, String effect, int amplifier, String display) {
        return new EffectDefinition(id, name, EffectType.POTION, effect, amplifier, -1, "",
                AttributeOperation.ADDITION, 0.0D, "", 10, "", display);
    }

    private synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("format_version", 1);
            for (EffectDefinition definition : effects.values()) {
                JsonObject object = new JsonObject();
                object.addProperty("name", definition.name());
                object.addProperty("type", definition.type().name());
                if (definition.type() == EffectType.POTION) {
                    object.addProperty("effect", definition.effect());
                    object.addProperty("amplifier", definition.amplifier());
                    object.addProperty("duration", definition.duration());
                } else if (definition.type() == EffectType.ATTRIBUTE) {
                    object.addProperty("attribute", definition.attribute());
                    object.addProperty("operation", definition.operation().serializedName());
                    object.addProperty("amount", definition.amount());
                } else if (definition.type() == EffectType.PARTICLE) {
                    object.addProperty("particle", definition.particle());
                    object.addProperty("frequency", definition.frequency());
                } else if (definition.type() == EffectType.PERMISSION) {
                    object.addProperty("permission", definition.permission());
                }
                object.addProperty("display", definition.display());
                root.add(definition.id(), object);
            }
            try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (IOException exception) {
            System.err.println("[omnitools] Could not save " + FILE + ": " + exception.getMessage());
        }
    }

    private static String requiredString(JsonObject object, String key) {
        String value = optionalString(object, key, null);
        if (value == null || value.isBlank()) {
            throw new JsonParseException(key + " must be a non-empty string");
        }
        return value;
    }

    private static String optionalString(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new JsonParseException(key + " must be a string");
        }
        return element.getAsString();
    }

    private static int optionalInt(JsonObject object, String key, int fallback) {
        JsonElement element = object.get(key);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException(key + " must be an integer");
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value) || value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new JsonParseException(key + " must be an integer");
        }
        return (int) value;
    }

    private static double optionalDouble(JsonObject object, String key, double fallback) {
        JsonElement element = object.get(key);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException(key + " must be a number");
        }
        return element.getAsDouble();
    }

    private static String normalizeId(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

    public enum EffectType {
        POTION,
        ATTRIBUTE,
        PARTICLE,
        PERMISSION;

        static EffectType parse(String value) {
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new JsonParseException("Unknown title effect type: " + value);
            }
        }
    }

    public enum AttributeOperation {
        ADDITION,
        ADD_MULTIPLIED_BASE,
        ADD_MULTIPLIED_TOTAL;

        public String serializedName() {
            return name();
        }

        static AttributeOperation parse(String value) {
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            return switch (normalized) {
                case "ADDITION", "ADD_VALUE" -> ADDITION;
                case "MULTIPLY_BASE", "ADD_MULTIPLIED_BASE" -> ADD_MULTIPLIED_BASE;
                case "MULTIPLY_TOTAL", "ADD_MULTIPLIED_TOTAL" -> ADD_MULTIPLIED_TOTAL;
                default -> throw new JsonParseException("Unknown attribute operation: " + value);
            };
        }
    }

    public record EffectDefinition(String id, String name, EffectType type, String effect, int amplifier,
                                    int duration, String attribute, AttributeOperation operation, double amount,
                                    String particle, int frequency, String permission, String display) {
    }
}
