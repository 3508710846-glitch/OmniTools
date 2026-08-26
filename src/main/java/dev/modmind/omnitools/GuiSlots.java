package dev.modmind.omnitools;

/** Shared slot conventions for vanilla chest menus. */
public final class GuiSlots {
    public static final int HEADER_LEFT_54 = 0;
    public static final int HEADER_CENTER_54 = 4;
    public static final int HEADER_CLOSE_54 = 8;
    public static final int CONTENT_START_54 = 9;
    public static final int CONTENT_END_54 = 44;
    public static final int CONTENT_SLOT_COUNT_54 = CONTENT_END_54 - CONTENT_START_54 + 1;
    public static final int FIRST_ACTION_SLOT_54 = 45;
    public static final int LAST_SLOT_54 = 53;
    public static final int FIRST_ACTION_SLOT_27 = 18;
    public static final int LAST_SLOT_27 = 26;
    public static final int CENTER_54 = 49;
    public static final int CENTER_27 = 22;
    public static final int HEADER_LEFT_27 = 0;
    public static final int HEADER_CENTER_27 = 4;
    public static final int HEADER_CLOSE_27 = 8;
    public static final int CONTENT_START_27 = 9;
    public static final int CONTENT_END_27 = 17;
    public static final int CONTENT_SLOT_COUNT_27 = CONTENT_END_27 - CONTENT_START_27 + 1;

    private GuiSlots() {
    }

    public static boolean isContentSlot54(int slot) {
        return slot >= CONTENT_START_54 && slot <= CONTENT_END_54;
    }

    public static int contentIndex54(int slot) {
        return isContentSlot54(slot) ? slot - CONTENT_START_54 : -1;
    }

    public static int contentSlot54(int index) {
        if (index < 0 || index >= CONTENT_SLOT_COUNT_54) {
            throw new IllegalArgumentException("54-slot content index is outside the shared content area: " + index);
        }
        return CONTENT_START_54 + index;
    }

    public static boolean isContentSlot27(int slot) {
        return slot >= CONTENT_START_27 && slot <= CONTENT_END_27;
    }

    public static int contentIndex27(int slot) {
        return isContentSlot27(slot) ? slot - CONTENT_START_27 : -1;
    }

    public static int contentSlot27(int index) {
        if (index < 0 || index >= CONTENT_SLOT_COUNT_27) {
            throw new IllegalArgumentException("27-slot content index is outside the shared content area: " + index);
        }
        return CONTENT_START_27 + index;
    }
}
