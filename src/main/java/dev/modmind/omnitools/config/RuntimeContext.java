package dev.modmind.omnitools.config;

import net.minecraft.server.MinecraftServer;

/** Runtime services available when a validated configuration snapshot is applied. */
public record RuntimeContext(MinecraftServer server, OmniToolsConfigSnapshot previous,
                             OmniToolsConfigSnapshot current) {
    public RuntimeContext {
        if (server == null || current == null) {
            throw new IllegalArgumentException("Runtime configuration context is incomplete");
        }
    }
}
