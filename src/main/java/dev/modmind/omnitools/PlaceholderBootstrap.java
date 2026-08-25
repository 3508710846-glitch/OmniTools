package dev.modmind.omnitools;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Optional integration gate that never references Placeholder API classes. */
public final class PlaceholderBootstrap {
    private static final String SIDEBAR_RESOLVER =
            "dev.modmind.omnitools.FabricSidebarPlaceholderResolver";
    private static boolean attempted;
    private static Method sidebarResolver;

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

    /**
     * Resolves a non-OmniTools token through the optional API, returning null when
     * the integration is unavailable or the token is not registered.
     */
    public static Component resolveExternal(ServerPlayer player, String token) {
        if (!ModMindEntry.configSnapshot().placeholderApiEnabled()
                || !FabricLoader.getInstance().isModLoaded("placeholder-api")) {
            return null;
        }
        try {
            Method method = sidebarResolver;
            if (method == null) {
                synchronized (PlaceholderBootstrap.class) {
                    method = sidebarResolver;
                    if (method == null) {
                        Class<?> resolver = Class.forName(SIDEBAR_RESOLVER);
                        method = resolver.getMethod("resolve", ServerPlayer.class, String.class);
                        sidebarResolver = method;
                    }
                }
            }
            return (Component) method.invoke(null, player, token);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                 | InvocationTargetException | ClassCastException exception) {
            return null;
        }
    }
}
