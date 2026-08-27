package dev.modmind.omnitools.sidebar;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SidebarConfigTest {
    @Test
    void mapsV2TextSidebarToMainPageWithoutChangingItsPresentation() {
        JsonObject root = new JsonObject();
        root.addProperty("format_version", 2);
        root.addProperty("default_visible", true);
        root.addProperty("refresh_interval_ticks", 20);
        root.addProperty("title", "&bLegacy");
        JsonArray lines = new JsonArray();
        JsonObject line = new JsonObject();
        line.addProperty("id", "money");
        line.addProperty("text", "&e%balance%");
        lines.add(line);
        root.add("lines", lines);

        SidebarConfig config = SidebarConfig.parse(root);

        assertEquals(3, config.formatVersion());
        assertEquals(SidebarConfig.PresentationMode.FIXED, config.presentation().mode());
        assertEquals("main", config.activePage(0, page -> true).id());
        assertEquals("&bLegacy", config.title());
        assertEquals(1, config.lines().size());
    }

    @Test
    void rotationUsesOneSharedServerTickClockAndSkipsUnavailablePages() {
        SidebarConfig.Page main = new SidebarConfig.Page("main", SidebarConfig.PageType.TEXT, "Main",
                java.util.List.of(new SidebarLine("line", "main")), "", 10, "");
        SidebarConfig.Page board = new SidebarConfig.Page("board", SidebarConfig.PageType.LEADERBOARD, "Board",
                java.util.List.of(), "mining", 10, "#{rank}");
        SidebarConfig config = new SidebarConfig(3, true, 20, SidebarConfig.ConflictPolicy.SKIP,
                new SidebarConfig.Presentation(SidebarConfig.PresentationMode.ROTATE, 20, "main",
                        java.util.List.of("main", "board")), java.util.List.of(main, board));

        assertEquals("main", config.activePage(0, page -> true).id());
        assertEquals("board", config.activePage(20, page -> true).id());
        assertEquals("main", config.activePage(20, page -> page.type() == SidebarConfig.PageType.TEXT).id());
    }
}
