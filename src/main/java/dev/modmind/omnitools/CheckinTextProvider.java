package dev.modmind.omnitools;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Centralizes fixed daily-check-in text and keeps journal names and Lore compact. */
public final class CheckinTextProvider {
    public static final int MAX_LORE_LINES = 4;
    public static final int MAX_VISIBLE_LINE_LENGTH = 80;

    private CheckinTextProvider() {
    }

    public static MutableComponent dateName(LocalDate date, boolean showWeekday) {
        if (!showWeekday) {
            return ServerText.translatable("gui.omnitools.checkin.journal.day_number",
                    String.format(Locale.ROOT, "%02d", date.getDayOfMonth()));
        }
        return ServerText.translatable("gui.omnitools.checkin.journal.day_name",
                String.format(Locale.ROOT, "%02d", date.getDayOfMonth()),
                ServerText.translatable(weekdayName(date.getDayOfWeek())));
    }

    public static MutableComponent dateDetail(LocalDate date) {
        return ServerText.translatable("gui.omnitools.checkin.journal.date_detail", date.getMonthValue(),
                date.getDayOfMonth());
    }

    public static List<Component> compactLore(List<Component> lines) {
        List<Component> result = new ArrayList<>(Math.min(MAX_LORE_LINES, lines.size()));
        for (Component line : lines) {
            if (line == null || result.size() >= MAX_LORE_LINES) {
                continue;
            }
            String visible = line.getString();
            result.add(visible.length() <= MAX_VISIBLE_LINE_LENGTH ? line : Component.literal(
                    visible.substring(0, MAX_VISIBLE_LINE_LENGTH - 3) + "..."));
        }
        return List.copyOf(result);
    }

    public static String weekdayName(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "gui.omnitools.checkin.monday";
            case TUESDAY -> "gui.omnitools.checkin.tuesday";
            case WEDNESDAY -> "gui.omnitools.checkin.wednesday";
            case THURSDAY -> "gui.omnitools.checkin.thursday";
            case FRIDAY -> "gui.omnitools.checkin.friday";
            case SATURDAY -> "gui.omnitools.checkin.saturday";
            case SUNDAY -> "gui.omnitools.checkin.sunday";
        };
    }
}
