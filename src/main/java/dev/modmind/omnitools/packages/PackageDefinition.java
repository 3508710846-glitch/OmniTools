package dev.modmind.omnitools.packages;

import net.minecraft.world.item.Item;
import java.util.List;
import java.util.Locale;

/** Immutable published package definition. */
public record PackageDefinition(String id, String display, List<String> description, String iconId, Item icon,
                                Mode mode, List<PackageItem> items, int version) {
    public PackageDefinition {
        id = normalizeId(id);
        display = display == null || display.isBlank() ? id : display.trim();
        description = List.copyOf(description == null ? List.of() : description);
        iconId = iconId == null ? "" : iconId.trim();
        if (icon == null) throw new IllegalArgumentException("Package icon is required: " + id);
        mode = mode == null ? Mode.ALL : mode;
        items = List.copyOf(items == null ? List.of() : items);
        if (items.isEmpty()) throw new IllegalArgumentException("Package must contain at least one item: " + id);
        if (display.length() > 128 || description.size() > 32) throw new IllegalArgumentException("Package text is too long: " + id);
        if (version < 1) throw new IllegalArgumentException("Package version must be positive");
    }
    private static String normalizeId(String value) {
        String id = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_.-]{1,64}")) throw new IllegalArgumentException("Invalid package id: " + value);
        return id;
    }
    public enum Mode {
        ALL, RANDOM_ONE;
        public static Mode parse(String value) {
            try { return valueOf((value == null ? "all" : value.trim()).toUpperCase(Locale.ROOT)); }
            catch (RuntimeException e) { throw new IllegalArgumentException("Package mode must be all or random_one"); }
        }
        public String serializedName() { return name().toLowerCase(Locale.ROOT); }
    }
}
