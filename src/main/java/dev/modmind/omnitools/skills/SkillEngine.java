package dev.modmind.omnitools.skills;

import com.google.gson.JsonParseException;

import java.util.Locale;

/** Skill progression engines supported by the server-side skills module. */
public enum SkillEngine {
    /** The original professional-tree implementation retained for old worlds. */
    PROFESSIONAL("professional"),
    /** Independent, mcMMO-compatible behavior implemented for Fabric. */
    MCMOO("mcmmo"),
    /** The pre-professional four-node tree format. */
    LEGACY("legacy");

    private final String serializedName;

    SkillEngine(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return serializedName;
    }

    public static SkillEngine parse(String value) {
        if (value == null || value.isBlank()) {
            throw new JsonParseException("skills.engine must be a non-empty string");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (SkillEngine engine : values()) {
            if (engine.serializedName.equals(normalized) || engine.name().equalsIgnoreCase(normalized)) {
                return engine;
            }
        }
        throw new JsonParseException("Unknown skills.engine: " + value);
    }
}
