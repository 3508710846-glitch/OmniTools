package dev.modmind.omnitools.achievement;

import java.util.Locale;

/** Matching mode used by a stat condition's resolved targets. */
public enum TargetMatch {
    SUM,
    EACH,
    ANY;

    public static TargetMatch parse(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown target match: " + value
                    + ". Supported values are sum, each and any", exception);
        }
    }
}
