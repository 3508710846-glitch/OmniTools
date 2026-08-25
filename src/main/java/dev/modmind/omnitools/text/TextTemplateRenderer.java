package dev.modmind.omnitools.text;

import dev.modmind.omnitools.LegacyTitleText;
import dev.modmind.omnitools.OmniToolsPlaceholderResolver;
import dev.modmind.omnitools.PlaceholderBootstrap;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    /**
     * Preserves an existing component unless its visible text contains template or legacy-format
     * syntax. This is useful for NBT/component-defined shop and reward display items.
     */
    public static Component render(ServerPlayer player, Component template) {
        if (template == null) {
            return Component.empty();
        }
        String visibleText = template.getString();
        return containsRenderableMarkup(visibleText) ? render(player, visibleText) : template.copy();
    }

    /** Returns a presentation-only copy; the configured item and delivered reward stay unchanged. */
    public static ItemStack renderItemText(ServerPlayer player, ItemStack source) {
        ItemStack rendered = source.copy();
        Component customName = rendered.get(DataComponents.CUSTOM_NAME);
        if (customName != null) {
            rendered.set(DataComponents.CUSTOM_NAME, render(player, customName));
        }
        ItemLore itemLore = rendered.get(DataComponents.LORE);
        if (itemLore != null && !itemLore.lines().isEmpty()) {
            List<Component> lore = new ArrayList<>(itemLore.lines().size());
            for (Component line : itemLore.lines()) {
                lore.add(render(player, line));
            }
            rendered.set(DataComponents.LORE, new ItemLore(lore));
        }
        return rendered;
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
                appendResolvedComponent(result, OmniToolsPlaceholderResolver.resolve(player, id));
            } else {
                Component external = PlaceholderBootstrap.resolveExternal(player, token);
                if (external != null) {
                    appendResolvedComponent(result, external);
                } else {
                    String warningToken = token.toLowerCase(Locale.ROOT);
                    if (WARNED_PLACEHOLDERS.add(warningToken)) {
                        System.err.println("[omnitools] Unknown text placeholder: " + warningToken);
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

    private static boolean containsRenderableMarkup(String text) {
        return text != null && (PLACEHOLDER.matcher(text).find()
                || text.indexOf('&') >= 0 || text.indexOf('\u00a7') >= 0);
    }

    /** Apply legacy color syntax returned by a placeholder without discarding styled API components. */
    private static void appendResolvedComponent(MutableComponent target, Component value) {
        if (value == null) {
            target.append(Component.literal("-"));
            return;
        }
        String visibleText = value.getString();
        target.append(containsRenderableMarkup(visibleText)
                ? LegacyTitleText.parse(sanitize(visibleText)) : value.copy());
    }

    private record CacheKey(UUID playerId, String template) {
    }
}
