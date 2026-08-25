package dev.modmind.omnitools;

import eu.pb4.placeholders.api.Placeholders;
import eu.pb4.placeholders.api.PlaceholderResult;
import net.minecraft.resources.Identifier;

/** The only class in OmniTools that directly links against Fabric Placeholder API. */
public final class FabricPlaceholderRegistrar {
    private static boolean registered;

    private FabricPlaceholderRegistrar() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        for (String id : OmniToolsPlaceholderResolver.IDS) {
            Identifier identifier = Identifier.fromNamespaceAndPath(ModMindEntry.MOD_ID, id);
            Placeholders.register(identifier, (context, argument) -> PlaceholderResult.value(
                    OmniToolsPlaceholderResolver.resolve(context == null ? null : context.player(), id)));
        }
        registered = true;
        System.out.println("[omnitools] Registered " + OmniToolsPlaceholderResolver.IDS.size()
                + " Placeholder API placeholders");
    }
}
