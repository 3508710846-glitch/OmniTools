package dev.modmind.qiandao;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/** Converts the legacy section-sign formatting accepted by title configuration into Components. */
public final class LegacyTitleText {
    private static final char FORMAT_MARKER = '\u00a7';

    private LegacyTitleText() {
    }

    public static Component parse(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }

        MutableComponent result = Component.empty();
        Style style = Style.EMPTY;
        StringBuilder segment = new StringBuilder();
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current != FORMAT_MARKER || index + 1 >= text.length()) {
                segment.append(current);
                continue;
            }

            char code = Character.toLowerCase(text.charAt(index + 1));
            if (code == 'x' && index + 13 < text.length()) {
                String hex = readHexColor(text, index);
                if (hex != null) {
                    appendSegment(result, segment, style);
                    style = style.withColor(Integer.parseInt(hex, 16));
                    index += 13;
                    continue;
                }
            }

            ChatFormatting formatting = ChatFormatting.getByCode(code);
            if (formatting == null) {
                segment.append(current);
                continue;
            }

            appendSegment(result, segment, style);
            style = style.applyLegacyFormat(formatting);
            index++;
        }
        appendSegment(result, segment, style);
        return result;
    }

    public static String plainText(String text) {
        return ChatFormatting.stripFormatting(text == null ? "" : text);
    }

    private static void appendSegment(MutableComponent result, StringBuilder segment, Style style) {
        if (segment.isEmpty()) {
            return;
        }
        result.append(Component.literal(segment.toString()).setStyle(style));
        segment.setLength(0);
    }

    private static String readHexColor(String text, int index) {
        StringBuilder hex = new StringBuilder(6);
        for (int offset = 2; offset <= 12; offset += 2) {
            if (text.charAt(index + offset) != FORMAT_MARKER) {
                return null;
            }
            char digit = text.charAt(index + offset + 1);
            if (Character.digit(digit, 16) < 0) {
                return null;
            }
            hex.append(digit);
        }
        return hex.toString();
    }
}
