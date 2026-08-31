package dev.modmind.omnitools.skills;

import com.google.gson.JsonParseException;

import java.util.Locale;

/** Auditable skill experience origins. Each tree whitelists the origins it accepts. */
public enum SkillXpSource {
    BLOCK_BREAK(true),
    ENTITY_KILL(true),
    CRAFT(true),
    SURVIVAL(true),
    REWARD(false),
    COMMAND(false);

    private final boolean rateLimited;

    SkillXpSource(boolean rateLimited) {
        this.rateLimited = rateLimited;
    }

    public boolean rateLimited() {
        return rateLimited;
    }

    public static SkillXpSource parse(String value) {
        if (value == null || value.isBlank()) {
            throw new JsonParseException("skill XP source must be a non-empty string");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new JsonParseException("Unknown skill XP source: " + value);
        }
    }

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
