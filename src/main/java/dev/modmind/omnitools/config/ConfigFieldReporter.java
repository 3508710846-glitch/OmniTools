package dev.modmind.omnitools.config;

import com.google.gson.JsonObject;

import java.util.Set;

/** Emits actionable warnings for unknown data fields without weakening strict value validation. */
public final class ConfigFieldReporter {
    private ConfigFieldReporter() {
    }

    public static void warnUnknown(JsonObject object, String context, Set<String> known) {
        if (object == null) {
            return;
        }
        for (String key : object.keySet()) {
            if (!known.contains(key)) {
                System.err.println("[omnitools] Unknown configuration field " + context + "." + key
                        + " will be ignored");
            }
        }
    }
}
