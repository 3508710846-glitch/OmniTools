package dev.modmind.omnitools;

import net.fabricmc.loader.api.FabricLoader;

/** Optional integration gate that never references Placeholder API classes. */
public final class PlaceholderBootstrap {
    private static boolean attempted;

    private PlaceholderBootstrap() {
    }

    public static synchronized void registerIfAvailable() {
        if (attempted || !ModMindEntry.configSnapshot().placeholderApiEnabled()
                || !FabricLoader.getInstance().isModLoaded("placeholder-api")) {
            return;
        }
        attempted = true;
        FabricPlaceholderRegistrar.register();
    }
}
