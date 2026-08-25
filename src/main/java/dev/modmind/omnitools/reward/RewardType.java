package dev.modmind.omnitools.reward;

import com.google.gson.JsonParseException;

import java.util.Locale;

/** Supported server-authoritative reward effects. */
public enum RewardType {
    CURRENCY,
    ITEM,
    TITLE,
    COMMAND;

    public static RewardType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new JsonParseException("reward type must be a non-empty string");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new JsonParseException("Unknown reward type: " + value);
        }
    }

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
