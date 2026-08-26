package dev.modmind.omnitools.config;

import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;

/** Immutable inputs available to a declarative module loader. */
public record LoadContext(MinecraftServer server, HolderLookup.Provider registries,
                          OmniToolsRootConfig root, CommonConfig common) {
    public LoadContext {
        if (server == null || registries == null || root == null || common == null) {
            throw new IllegalArgumentException("Configuration load context is incomplete");
        }
    }
}
