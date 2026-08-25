package dev.modmind.omnitools.text;

import dev.modmind.omnitools.LegacyTitleText;
import dev.modmind.omnitools.OmniToolsPlaceholderResolver;
import dev.modmind.omnitools.PlaceholderBootstrap;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared, server-side template renderer for configurable player-facing text. */
public final class TextTemplateRenderer {
    private static final Pattern PLACEHOLDER = Pattern.compile("%([^%]+)%");
    private static final Set<String> WARNED_PLACEHOLDERS = new HashSet<>();
    private static final Map<CacheKey, Component> TICK_CACHE = new HashMap<>();
    private static long cacheTick = Long.MIN_VALUE;

    private TextTemplateRenderer() {
    }

    public static Component render(ServerPlayer player, String template) {
        if (player == null) {
            return LegacyTitleText.parse(sanitize(template));
        }
        long tick = player.level().getServer().getTickCount();
        if (tick != cacheTick) {
            TICK_CACHE.clear();
            cacheTick = tick;
        }
        CacheKey key = new CacheKey(player.getUUID(), template == null ? "" : template);
        return TICK_CACHE.computeIfAbsent(key, ignored -> renderUncached(player, template));
    }

    private static Component renderUncached(ServerPlayer player, String template) {
        String source = sanitize(template);
        Matcher matcher = PLACEHOLDER.matcher(source);
        MutableComponent result = Component.empty();
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() > cursor) {
                result.append(LegacyTitleText.parse(source.substring(cursor, matcher.start())));
            }
            String token = matcher.group(1).trim();
            String id = token.toLowerCase(java.util.Locale.ROOT);
            if (id.startsWith("omnitools:")) {
                id = id.substring("omnitools:".length());
            }
            if (OmniToolsPlaceholderResolver.IDS.contains(id)) {
                result.append(OmniToolsPlaceholderResolver.resolve(player, id));
            } else {
                Component external = PlaceholderBootstrap.resolveExternal(player, token);
                if (external != null) {
                    result.append(external);
                } else {
                    if (WARNED_PLACEHOLDERS.add(token)) {
                        System.err.println("[omnitools] Unknown text placeholder: " + token);
                    }
                    result.append(Component.literal("-"));
                }
            }
            cursor = matcher.end();
        }
        if (cursor < source.length()) {
            result.append(LegacyTitleText.parse(source.substring(cursor)));
        }
        return result;
    }

    private static String sanitize(String template) {
        return (template == null ? "" : template).replace('&', '\u00a7').replace('\n', ' ');
    }

    private record CacheKey(UUID playerId, String template) {
    }
}
