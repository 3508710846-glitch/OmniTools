package dev.modmind.omnitools.config;

import dev.modmind.omnitools.ModMindEntry;
import net.minecraft.server.MinecraftServer;

/** Stable adapter between the configuration platform and the existing runtime compensation code. */
public final class RuntimeConfigApplier {
    public void apply(MinecraftServer server, OmniToolsConfigSnapshot previous,
                      OmniToolsConfigSnapshot current) {
        ModMindEntry.applyRuntimeConfigChange(server, previous, current);
    }
}
