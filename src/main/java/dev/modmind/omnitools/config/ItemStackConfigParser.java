package dev.modmind.omnitools.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;

/** Shared parser for administrator-configured item stacks in shops and unified rewards. */
public final class ItemStackConfigParser {
    public static final int MAX_SNBT_BYTES = 32 * 1024;

    private ItemStackConfigParser() {
    }

    /**
     * Parses either item/count/components or a complete ItemStack SNBT payload. The NBT form is
     * intentionally exclusive so an outer count cannot disagree with the stack payload.
     */
    public static ItemStack parse(JsonObject object, HolderLookup.Provider registries, String context, int maxCount)
            throws CommandSyntaxException {
        if (object == null) {
            throw new JsonParseException(context + " must be an object");
        }
        if (maxCount < 1) {
            throw new IllegalArgumentException("maxCount must be positive");
        }
        if (object.has("nbt")) {
            rejectMixedNbtFields(object, context);
            return validateStack(parseFullStackNbt(requiredString(object, "nbt", context), registries, context),
                    context, maxCount);
        }

        String item = requiredString(object, "item", context);
        int count = positiveInt(object, "count", context, maxCount);
        String components = optionalComponents(object, context);
        StringReader reader = new StringReader(item + (components == null ? "" : components));
        ItemParser.ItemResult result = new ItemParser(registries).parse(reader);
        if (reader.canRead()) {
            throw new JsonParseException(context + " has unexpected text in item or components");
        }
        return validateStack(new ItemInput(result.item(), result.components()).createItemStack(count, false), context,
                maxCount);
    }

    /**
     * Ensures an item can survive the exact codec/NBT path used by the reward ledger before any
     * player-facing event is created.
     */
    public static void validateRewardSnapshot(ItemStack stack, HolderLookup.Provider registries, String context, int maxCount) {
        ItemStack original = validateStack(stack, context, maxCount);
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        Tag encoded = ItemStack.CODEC.encodeStart(ops, original.copy()).result()
                .orElseThrow(() -> new JsonParseException(context + " item cannot be serialized for the reward ledger"));
        if (!(encoded instanceof CompoundTag itemTag)) {
            throw new JsonParseException(context + " item did not encode as a full ItemStack compound");
        }
        ensureEncodedSize(itemTag, context + " serialized item snapshot");
        ItemStack decoded = ItemStack.CODEC.parse(ops, itemTag).result()
                .orElseThrow(() -> new JsonParseException(context + " item cannot be decoded from the reward ledger"));
        validateStack(decoded, context + " decoded item", maxCount);
        if (original.getCount() != decoded.getCount() || !ItemStack.isSameItemSameComponents(original, decoded)) {
            throw new JsonParseException(context + " item changed after reward-ledger serialization");
        }
    }

    private static ItemStack parseFullStackNbt(String source, HolderLookup.Provider registries, String context) {
        ensureSize(source, context + ".nbt");
        CompoundTag tag;
        try {
            tag = TagParser.parseCompoundFully(source);
        } catch (CommandSyntaxException exception) {
            throw new JsonParseException(context + ".nbt must be valid full ItemStack SNBT", exception);
        }
        return ItemStack.CODEC.parse(RegistryOps.create(NbtOps.INSTANCE, registries), tag)
                .getOrThrow(message -> new JsonParseException(
                        context + ".nbt must be a valid full ItemStack SNBT compound: " + message));
    }

    private static void rejectMixedNbtFields(JsonObject object, String context) {
        for (String field : new String[]{"item", "count", "components"}) {
            if (object.has(field)) {
                throw new JsonParseException(context + " cannot combine nbt with " + field);
            }
        }
    }

    private static ItemStack validateStack(ItemStack stack, String context, int maxCount) {
        if (stack == null || stack.isEmpty()) {
            throw new JsonParseException(context + " cannot use an empty item stack");
        }
        int count = stack.getCount();
        if (count < 1 || count > maxCount) {
            throw new JsonParseException(context + " item count must be between 1 and " + maxCount);
        }
        return stack.copy();
    }

    private static String optionalComponents(JsonObject object, String context) {
        JsonElement element = object.get("components");
        if (element == null) {
            return null;
        }
        if (element.isJsonObject() && element.getAsJsonObject().isEmpty()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new JsonParseException(context + ".components must be a vanilla item-component string or an empty object");
        }
        return element.getAsString();
    }

    private static String requiredString(JsonObject object, String key, String context) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                || element.getAsString().isBlank()) {
            throw new JsonParseException(context + "." + key + " must be a non-empty string");
        }
        return element.getAsString();
    }

    private static int positiveInt(JsonObject object, String key, String context, int maxCount) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException(context + "." + key + " must be an integer between 1 and " + maxCount);
        }
        try {
            int value = Integer.parseInt(element.getAsString());
            if (value < 1 || value > maxCount) {
                throw new JsonParseException(context + "." + key + " must be an integer between 1 and " + maxCount);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new JsonParseException(context + "." + key + " must be an integer between 1 and " + maxCount);
        }
    }

    private static void ensureSize(String value, String context) {
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_SNBT_BYTES) {
            throw new JsonParseException(context + " exceeds " + MAX_SNBT_BYTES + " UTF-8 bytes");
        }
    }

    private static void ensureEncodedSize(CompoundTag itemTag, String context) {
        if (itemTag.sizeInBytes() > MAX_SNBT_BYTES) {
            throw new JsonParseException(context + " exceeds " + MAX_SNBT_BYTES + " bytes");
        }
    }
}
