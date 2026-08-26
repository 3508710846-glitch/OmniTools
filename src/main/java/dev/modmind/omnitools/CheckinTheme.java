package dev.modmind.omnitools;

import java.time.LocalDate;

/** Slot contract for the six-row Monday-first check-in journal. */
public final class CheckinTheme {
    public static final int ROWS = 6;
    public static final int CONTAINER_SIZE = ROWS * 9;
    public static final int CALENDAR_COLUMNS = 7;
    public static final int PROFILE_SLOT = 7;
    public static final int MONTH_SLOT = 8;
    public static final int REWARD_INFO_SLOT = 16;
    public static final int PROGRESS_SLOT = 17;
    public static final int RECORDS_SLOT = 25;
    public static final int ACHIEVEMENTS_SLOT = 26;
    public static final int STREAK_SLOT = 34;
    public static final int BALANCE_SLOT = 35;
    public static final int REWARD_INBOX_SLOT = 43;
    public static final int HELP_SLOT = 44;
    public static final int REFRESH_SLOT = 52;
    public static final int CLOSE_SLOT = 53;

    private CheckinTheme() {
    }

    public static int monthStartOffset(LocalDate date) {
        return date.withDayOfMonth(1).getDayOfWeek().getValue() - 1;
    }

    public static int slotForDay(LocalDate month, int day) {
        if (day < 1 || day > month.lengthOfMonth()) {
            return -1;
        }
        int index = monthStartOffset(month) + day - 1;
        return (index / CALENDAR_COLUMNS) * 9 + (index % CALENDAR_COLUMNS);
    }

    public static Integer slotToDay(LocalDate month, int slot) {
        if (!isCalendarSlot(slot)) {
            return null;
        }
        int index = (slot / 9) * CALENDAR_COLUMNS + slot % 9 - monthStartOffset(month);
        int day = index + 1;
        return day >= 1 && day <= month.lengthOfMonth() ? day : null;
    }

    public static boolean isCalendarSlot(int slot) {
        return slot >= 0 && slot < CONTAINER_SIZE && slot % 9 < CALENDAR_COLUMNS;
    }
}
