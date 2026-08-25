package dev.modmind.omnitools;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves OmniTools-owned language keys on the server before they are sent to clients.
 * Vanilla translation components are intentionally left to vanilla callers and clients.
 */
public final class ServerText {
    private static final String DEFAULT_LANGUAGE = "zh_cn";
    private static final Map<String, String> ZH_CN = load("zh_cn");
    private static final Map<String, String> EN_US = load("en_us");
    private static final Set<String> MISSING_KEYS = ConcurrentHashMap.newKeySet();
    private static volatile String language = DEFAULT_LANGUAGE;

    private ServerText() {
    }

    public static void setLanguage(String value) {
        language = normalizeLanguage(value);
    }

    public static String language() {
        return language;
    }

    /** Creates a final literal component from an OmniTools language key and printf-style arguments. */
    public static MutableComponent translatable(String key, Object... arguments) {
        String template = translations().get(key);
        if (template == null) {
            template = EN_US.get(key);
        }
        if (template == null) {
            if (MISSING_KEYS.add(key)) {
                System.err.println("[omnitools] Missing server translation key: " + key);
            }
            return Component.literal(key);
        }
        return format(template, arguments == null ? new Object[0] : arguments);
    }

    private static Map<String, String> translations() {
        return language.equals("en_us") ? EN_US : ZH_CN;
    }

    private static MutableComponent format(String template, Object[] arguments) {
        MutableComponent result = Component.empty();
        StringBuilder text = new StringBuilder();
        int argumentIndex = 0;
        for (int index = 0; index < template.length(); index++) {
            char current = template.charAt(index);
            if (current != '%' || index + 1 >= template.length()) {
                text.append(current);
                continue;
            }
            int cursor = index + 1;
            if (template.charAt(cursor) == '%') {
                text.append('%');
                index = cursor;
                continue;
            }

            int digitStart = cursor;
            while (cursor < template.length() && Character.isDigit(template.charAt(cursor))) {
                cursor++;
            }
            String digits = template.substring(digitStart, cursor);
            int positionalIndex = -1;
            int width = 0;
            boolean zeroPadded = false;
            if (!digits.isEmpty()) {
                if (cursor < template.length() && template.charAt(cursor) == '$') {
                    positionalIndex = parsePositiveIndex(digits);
                    cursor++;
                } else {
                    width = parseWidth(digits);
                    zeroPadded = digits.charAt(0) == '0';
                }
            }
            if (cursor >= template.length()
                    || (template.charAt(cursor) != 's' && template.charAt(cursor) != 'd')) {
                text.append(current);
                continue;
            }
            appendLiteral(result, text);
            int resolvedIndex = positionalIndex >= 0 ? positionalIndex : argumentIndex++;
            Object argument = resolvedIndex < arguments.length ? arguments[resolvedIndex] : "";
            result.append(argumentComponent(argument, template.charAt(cursor), width, zeroPadded));
            index = cursor;
        }
        appendLiteral(result, text);
        return result;
    }

    private static Component argumentComponent(Object argument, char conversion, int width, boolean zeroPadded) {
        if (argument instanceof Component component) {
            return component.copy();
        }
        if (conversion == 'd' && width > 0 && argument instanceof Number number) {
            String specifier = "%" + (zeroPadded ? "0" : "") + width + "d";
            return Component.literal(String.format(Locale.ROOT, specifier, number.longValue()));
        }
        return Component.literal(String.valueOf(argument));
    }

    private static int parsePositiveIndex(String value) {
        try {
            int index = Integer.parseInt(value);
            return index > 0 ? index - 1 : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static int parseWidth(String value) {
        try {
            return Math.min(Integer.parseInt(value), 64);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static void appendLiteral(MutableComponent target, StringBuilder text) {
        if (!text.isEmpty()) {
            target.append(Component.literal(text.toString()));
            text.setLength(0);
        }
    }

    private static Map<String, String> load(String language) {
        String path = "/assets/omnitools/lang/" + language + ".json";
        try (InputStream stream = ServerText.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Missing bundled language resource " + path);
            }
            Type type = new TypeToken<Map<String, String>>() { }.getType();
            Map<String, String> entries = new Gson().fromJson(
                    new InputStreamReader(stream, StandardCharsets.UTF_8), type);
            return entries == null ? Map.of() : Map.copyOf(entries);
        } catch (Exception exception) {
            System.err.println("[omnitools] Could not load bundled server language " + language + ": "
                    + exception.getMessage());
            return Map.of();
        }
    }

    private static String normalizeLanguage(String value) {
        String normalized = value == null ? DEFAULT_LANGUAGE : value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("en_us") ? "en_us" : DEFAULT_LANGUAGE;
    }
}
