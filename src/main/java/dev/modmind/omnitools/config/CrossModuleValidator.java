package dev.modmind.omnitools.config;

/** Single platform entry point for dependency and cross-module safety checks. */
public final class CrossModuleValidator {
    private CrossModuleValidator() {
    }

    public static void validate(OmniToolsConfigSnapshot snapshot) {
        ConfigValidator.validate(snapshot);
    }
}
