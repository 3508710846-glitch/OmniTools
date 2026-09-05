package dev.modmind.omnitools.config;

/** Runtime state exposed to commands, GUI handlers and event listeners. */
public enum ModuleStatus {
    ENABLED,
    DISABLED,
    /** Configuration or startup failed; services are kept out of the active runtime snapshot. */
    DEGRADED,
    INVALID
}
