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

    public static Availability availability() {
        if (!ModMindEntry.configSnapshot().placeholderApiEnabled()) {
            return Availability.DISABLED_IN_CONFIGURATION;
        }
        return FabricLoader.getInstance().isModLoaded("placeholder-api")
                ? Availability.AVAILABLE : Availability.NOT_INSTALLED;
    }

    public static synchronized void registerIfAvailable() {
        if (attempted || availability() != Availability.AVAILABLE) {
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
        if (availability() != Availability.AVAILABLE) {
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

    public enum Availability {
        AVAILABLE("command.omnitools.diagnose.placeholder_available"),
        DISABLED_IN_CONFIGURATION("command.omnitools.diagnose.placeholder_disabled"),
        NOT_INSTALLED("command.omnitools.diagnose.placeholder_not_installed");

        private final String translationKey;

        Availability(String translationKey) {
            this.translationKey = translationKey;
        }

        public String translationKey() {
            return translationKey;
        }
    }
}
