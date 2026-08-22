package dev.modmind.omnitools;

import java.util.Locale;

/** The visibility tier assigned to a configured title. */
public enum TitleRarity {
    COMMON("common"),
    RARE("rare"),
    LEGENDARY("legendary");

    private final String serializedName;

    TitleRarity(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return serializedName;
    }

    public boolean appearsInTabList() {
        return this == RARE || this == LEGENDARY;
    }

    public boolean appearsAboveHead() {
        return this == LEGENDARY;
    }

    public static TitleRarity parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("rarity is required");
        }

        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "common", "normal", "ordinary", "\u666e\u901a" -> COMMON;
            case "rare", "\u7a00\u6709" -> RARE;
            case "legendary", "legend", "\u4f20\u8bf4" -> LEGENDARY;
            default -> throw new IllegalArgumentException("Unknown title rarity: " + value);
        };
    }
}
