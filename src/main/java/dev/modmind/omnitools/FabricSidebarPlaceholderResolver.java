package dev.modmind.omnitools;

import eu.pb4.placeholders.api.PlaceholderContext;
import eu.pb4.placeholders.api.PlaceholderResult;
import eu.pb4.placeholders.api.Placeholders;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Optional Placeholder API bridge used by the sidebar. Keep this class behind
 * PlaceholderBootstrap so servers without the optional mod never load these types.
 */
public final class FabricSidebarPlaceholderResolver {
    private FabricSidebarPlaceholderResolver() {
    }

    public static Component resolve(ServerPlayer player, String token) {
        if (player == null || token == null || token.isBlank()) {
            return null;
        }
        String trimmed = token.trim();
        int separator = trimmed.indexOf(' ');
        String identifierText = separator < 0 ? trimmed : trimmed.substring(0, separator);
        String argument = separator < 0 ? "" : trimmed.substring(separator + 1).trim();
        Identifier identifier = Identifier.tryParse(identifierText);
        if (identifier == null) {
            return null;
        }
        try {
            PlaceholderResult result = Placeholders.parsePlaceholder(identifier, argument,
                    PlaceholderContext.of(player));
            return result != null && result.isValid() ? result.text() : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
