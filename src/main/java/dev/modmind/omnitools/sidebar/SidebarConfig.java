package dev.modmind.omnitools.sidebar;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.modmind.omnitools.LegacyTitleText;
import dev.modmind.omnitools.config.ConfigFieldReporter;
import dev.modmind.omnitools.config.ConfigPaths;
import dev.modmind.omnitools.config.ModuleId;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Administrator-editable sidebar pages with v1/v2 text-sidebar compatibility. */
public record SidebarConfig(int formatVersion, boolean defaultVisible, int refreshIntervalTicks,
                            ConflictPolicy conflictPolicy, Presentation presentation, List<Page> pages) {
    public static final int CURRENT_FORMAT_VERSION = 3;
    public static final int MAX_LINES = 15;
    public static final int MAX_PAGES = 64;
    public static final int MIN_REFRESH_INTERVAL_TICKS = 5;
    public static final int MAX_REFRESH_INTERVAL_TICKS = 600;
    public static final int MIN_ROTATION_TICKS = 20;
    public static final int MAX_ROTATION_TICKS = 72_000;
    private static final int MAX_TITLE_LENGTH = 64;
    private static final int MAX_LINE_LENGTH = 256;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public SidebarConfig {
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw new JsonParseException("Unsupported sidebar effective format_version: " + formatVersion);
        }
        if (refreshIntervalTicks < MIN_REFRESH_INTERVAL_TICKS || refreshIntervalTicks > MAX_REFRESH_INTERVAL_TICKS) {
            throw new JsonParseException("sidebar.refresh_interval_ticks must be between 5 and 600");
        }
        conflictPolicy = conflictPolicy == null ? ConflictPolicy.SKIP : conflictPolicy;
        List<Page> copy = List.copyOf(pages == null ? List.of() : pages);
        if (copy.size() > MAX_PAGES) {
            throw new JsonParseException("sidebar.pages may contain at most " + MAX_PAGES + " entries");
        }
        Set<String> ids = new HashSet<>();
        for (Page page : copy) {
            if (page == null || !ids.add(page.id().toLowerCase(Locale.ROOT))) {
                throw new JsonParseException("sidebar page ids must be unique");
            }
        }
        pages = copy;
        presentation = presentation == null ? Presentation.fixed("main") : presentation;
        presentation.validate(ids);
    }

    public static SidebarConfig empty() {
        return new SidebarConfig(CURRENT_FORMAT_VERSION, false, 20, ConflictPolicy.SKIP,
                Presentation.fixed("main"), List.of());
    }

    public static SidebarConfig load() {
        Path file = path();
        if (!Files.exists(file)) {
            SidebarConfig defaults = defaults();
            try {
                save(defaults);
            } catch (IOException exception) {
                throw new IllegalStateException("Could not create " + file, exception);
            }
            return defaults;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement element = GSON.fromJson(reader, JsonElement.class);
            if (element == null || !element.isJsonObject()) {
                throw new JsonParseException("sidebar configuration must be an object");
            }
            return parse(element.getAsJsonObject());
        } catch (IOException | JsonParseException | IllegalArgumentException exception) {
            System.err.println("[omnitools] Could not load " + file + ": " + exception.getMessage()
                    + ". The configuration snapshot will not be replaced.");
            throw new IllegalStateException("Invalid sidebar configuration", exception);
        }
    }

    public static Path path() {
        return ConfigPaths.moduleConfig(ModuleId.SIDEBAR);
    }

    /** Selects a page from the server-global rotation clock; unavailable pages are skipped. */
    public Page activePage(long tick, Predicate<Page> available) {
        Predicate<Page> predicate = available == null ? page -> true : available;
        List<Page> candidates = new ArrayList<>();
        List<String> requested = presentation.mode() == PresentationMode.FIXED
                ? List.of(presentation.fixedPage()) : presentation.pageIds();
        for (String id : requested) {
            page(id).filter(predicate).ifPresent(candidates::add);
        }
        if (candidates.isEmpty()) {
            return null;
        }
        if (presentation.mode() == PresentationMode.FIXED) {
            return candidates.getFirst();
        }
        long phase = Math.max(0L, tick) / presentation.rotationTicks();
        return candidates.get((int) (phase % candidates.size()));
    }

    public java.util.Optional<Page> page(String id) {
        if (id == null) {
            return java.util.Optional.empty();
        }
        return pages.stream().filter(page -> page.id().equalsIgnoreCase(id.trim())).findFirst();
    }

    /** Compatibility accessors for callers that only understand one text page. */
    public String title() {
        return page("main").filter(page -> page.type() == PageType.TEXT).map(Page::title).orElse("");
    }

    public List<SidebarLine> lines() {
        return page("main").filter(page -> page.type() == PageType.TEXT).map(Page::lines).orElse(List.of());
    }

    private static SidebarConfig defaults() {
        Page main = new Page("main", PageType.TEXT, "&b&lOmniTools", List.of(
                new SidebarLine("balance", "&eBalance: &f%omnitools:balance_formatted%"),
                new SidebarLine("checkin", "&aCheck-in: &f%omnitools:checkin_total_days%"),
                new SidebarLine("online", "&dOnline: &f%omnitools:online_today_hms%")
        ), "", 10, "");
        return new SidebarConfig(CURRENT_FORMAT_VERSION, true, 20, ConflictPolicy.SKIP,
                Presentation.fixed("main"), List.of(main));
    }

    static SidebarConfig parse(JsonObject root) {
        int version = integer(root, "format_version", CURRENT_FORMAT_VERSION);
        if (version < 1 || version > CURRENT_FORMAT_VERSION) {
            throw new JsonParseException("Unsupported sidebar format_version: " + version);
        }
        boolean visible = bool(root, "default_visible", true);
        int interval = integer(root, "refresh_interval_ticks", 20);
        ConflictPolicy policy = ConflictPolicy.parse(string(root, "conflict_policy", "skip"));
        if (version < CURRENT_FORMAT_VERSION) {
            ConfigFieldReporter.warnUnknown(root, "sidebar", Set.of("format_version", "default_visible",
                    "refresh_interval_ticks", "title", "conflict_policy", "lines"));
            Page main = new Page("main", PageType.TEXT, string(root, "title", ""), parseLines(root.get("lines"),
                    "sidebar.lines"), "", 10, "");
            return new SidebarConfig(CURRENT_FORMAT_VERSION, visible, interval, policy,
                    Presentation.fixed("main"), List.of(main));
        }
        ConfigFieldReporter.warnUnknown(root, "sidebar", Set.of("format_version", "default_visible",
                "refresh_interval_ticks", "conflict_policy", "presentation", "pages"));
        JsonElement pagesElement = root.get("pages");
        if (pagesElement == null || !pagesElement.isJsonArray()) {
            throw new JsonParseException("sidebar.pages must be an array");
        }
        List<Page> pages = new ArrayList<>();
        JsonArray array = pagesElement.getAsJsonArray();
        for (int index = 0; index < array.size(); index++) {
            if (!array.get(index).isJsonObject()) {
                throw new JsonParseException("sidebar.pages[" + index + "] must be an object");
            }
            pages.add(Page.parse(array.get(index).getAsJsonObject(), "sidebar.pages[" + index + "]"));
        }
        return new SidebarConfig(CURRENT_FORMAT_VERSION, visible, interval, policy,
                Presentation.parse(root.get("presentation")), pages);
    }

    private static List<SidebarLine> parseLines(JsonElement element, String context) {
        if (element == null || !element.isJsonArray()) {
            throw new JsonParseException(context + " must be an array");
        }
        List<SidebarLine> lines = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        JsonArray array = element.getAsJsonArray();
        if (array.size() > MAX_LINES) {
            throw new JsonParseException(context + " may contain at most " + MAX_LINES + " entries");
        }
        for (int index = 0; index < array.size(); index++) {
            if (!array.get(index).isJsonObject()) {
                throw new JsonParseException(context + "[" + index + "] must be an object");
            }
            JsonObject line = array.get(index).getAsJsonObject();
            ConfigFieldReporter.warnUnknown(line, context + "[" + index + "]", Set.of("id", "text"));
            String id = string(line, "id", "");
            String text = string(line, "text", "");
            if (!ID.matcher(id).matches() || !ids.add(id.toLowerCase(Locale.ROOT))) {
                throw new JsonParseException(context + " ids must be unique and match " + ID.pattern());
            }
            if (text.isBlank() || text.length() > MAX_LINE_LENGTH) {
                throw new JsonParseException(context + "[" + index + "].text must be non-empty and at most "
                        + MAX_LINE_LENGTH + " characters");
            }
            lines.add(new SidebarLine(id, text));
        }
        return List.copyOf(lines);
    }

    private static void save(SidebarConfig config) throws IOException {
        Path file = path();
        Files.createDirectories(file.getParent());
        JsonObject root = new JsonObject();
        root.addProperty("format_version", CURRENT_FORMAT_VERSION);
        root.addProperty("default_visible", config.defaultVisible());
        root.addProperty("refresh_interval_ticks", config.refreshIntervalTicks());
        root.addProperty("conflict_policy", config.conflictPolicy().serializedName());
        root.add("presentation", config.presentation().toJson());
        JsonArray pages = new JsonArray();
        config.pages().forEach(page -> pages.add(page.toJson()));
        root.add("pages", pages);
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
    }

    private static String colored(String value) {
        return value.replace('&', '\u00a7');
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        if (value == null) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new JsonParseException("sidebar." + key + " must be a string");
        }
        return value.getAsString();
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        JsonElement value = object.get(key);
        if (value == null) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new JsonParseException("sidebar." + key + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static int integer(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        if (value == null) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException("sidebar." + key + " must be an integer");
        }
        try {
            return Integer.parseInt(value.getAsString());
        } catch (NumberFormatException exception) {
            throw new JsonParseException("sidebar." + key + " must be an integer");
        }
    }

    public record Page(String id, PageType type, String title, List<SidebarLine> lines,
                       String leaderboardId, int maxEntries, String lineFormat) {
        public Page {
            id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
            if (!ID.matcher(id).matches()) {
                throw new JsonParseException("sidebar page id must match " + ID.pattern());
            }
            type = type == null ? PageType.TEXT : type;
            title = title == null ? "" : title;
            if (title.length() > MAX_TITLE_LENGTH || LegacyTitleText.plainText(colored(title)).length() > MAX_TITLE_LENGTH) {
                throw new JsonParseException("sidebar page " + id + " title is too long");
            }
            lines = List.copyOf(lines == null ? List.of() : lines);
            if (type == PageType.TEXT && lines.size() > MAX_LINES) {
                throw new JsonParseException("sidebar page " + id + " may contain at most " + MAX_LINES + " lines");
            }
            if (type == PageType.TEXT && lines.isEmpty()) {
                throw new JsonParseException("text sidebar page " + id + " needs at least one line");
            }
            leaderboardId = leaderboardId == null ? "" : leaderboardId.trim().toLowerCase(Locale.ROOT);
            maxEntries = maxEntries == 0 ? 10 : maxEntries;
            lineFormat = lineFormat == null ? "" : lineFormat;
            if (type == PageType.LEADERBOARD) {
                if (!ID.matcher(leaderboardId).matches()) {
                    throw new JsonParseException("leaderboard sidebar page " + id + " needs a valid leaderboard_id");
                }
                if (maxEntries < 1 || maxEntries > MAX_LINES) {
                    throw new JsonParseException("leaderboard sidebar page " + id + " max_entries must be 1--" + MAX_LINES);
                }
                if (lineFormat.isBlank() || lineFormat.length() > MAX_LINE_LENGTH) {
                    throw new JsonParseException("leaderboard sidebar page " + id + " needs a valid line_format");
                }
            }
        }

        private static Page parse(JsonObject value, String context) {
            String typeName = string(value, "type", "text");
            PageType type = PageType.parse(typeName);
            Set<String> fields = type == PageType.TEXT
                    ? Set.of("id", "type", "title", "lines")
                    : Set.of("id", "type", "title", "leaderboard_id", "max_entries", "line_format");
            ConfigFieldReporter.warnUnknown(value, context, fields);
            return new Page(string(value, "id", ""), type, string(value, "title", ""),
                    type == PageType.TEXT ? parseLines(value.get("lines"), context + ".lines") : List.of(),
                    string(value, "leaderboard_id", ""), integer(value, "max_entries", 10),
                    string(value, "line_format", "&7#{rank} &f{player} &b{value}"));
        }

        private JsonObject toJson() {
            JsonObject value = new JsonObject();
            value.addProperty("id", id);
            value.addProperty("type", type.serializedName());
            value.addProperty("title", title);
            if (type == PageType.TEXT) {
                JsonArray values = new JsonArray();
                lines.forEach(line -> {
                    JsonObject lineValue = new JsonObject();
                    lineValue.addProperty("id", line.id());
                    lineValue.addProperty("text", line.text());
                    values.add(lineValue);
                });
                value.add("lines", values);
            } else {
                value.addProperty("leaderboard_id", leaderboardId);
                value.addProperty("max_entries", maxEntries);
                value.addProperty("line_format", lineFormat);
            }
            return value;
        }
    }

    public record Presentation(PresentationMode mode, int rotationTicks, String fixedPage, List<String> pageIds) {
        public Presentation {
            mode = mode == null ? PresentationMode.FIXED : mode;
            rotationTicks = rotationTicks == 0 ? 200 : rotationTicks;
            fixedPage = normalizePageId(fixedPage, "fixed_page");
            List<String> ids = new ArrayList<>();
            for (String id : pageIds == null ? List.<String>of() : pageIds) {
                ids.add(normalizePageId(id, "page_ids"));
            }
            pageIds = List.copyOf(ids);
            if (rotationTicks < MIN_ROTATION_TICKS || rotationTicks > MAX_ROTATION_TICKS) {
                throw new JsonParseException("sidebar.presentation.rotation_ticks must be between "
                        + MIN_ROTATION_TICKS + " and " + MAX_ROTATION_TICKS);
            }
            if (mode == PresentationMode.ROTATE && pageIds.isEmpty()) {
                throw new JsonParseException("sidebar.presentation.page_ids must be non-empty in rotate mode");
            }
        }

        static Presentation fixed(String pageId) {
            return new Presentation(PresentationMode.FIXED, 200, pageId, List.of(pageId));
        }

        private static Presentation parse(JsonElement element) {
            if (element == null || !element.isJsonObject()) {
                throw new JsonParseException("sidebar.presentation must be an object");
            }
            JsonObject value = element.getAsJsonObject();
            ConfigFieldReporter.warnUnknown(value, "sidebar.presentation", Set.of("mode", "rotation_ticks",
                    "fixed_page", "page_ids"));
            List<String> ids = new ArrayList<>();
            JsonElement idsElement = value.get("page_ids");
            if (idsElement != null) {
                if (!idsElement.isJsonArray()) {
                    throw new JsonParseException("sidebar.presentation.page_ids must be an array");
                }
                for (JsonElement id : idsElement.getAsJsonArray()) {
                    if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) {
                        throw new JsonParseException("sidebar.presentation.page_ids must contain strings");
                    }
                    ids.add(id.getAsString());
                }
            }
            return new Presentation(PresentationMode.parse(string(value, "mode", "fixed")),
                    integer(value, "rotation_ticks", 200), string(value, "fixed_page", "main"), ids);
        }

        private void validate(Set<String> knownPages) {
            if (!knownPages.isEmpty() && !knownPages.contains(fixedPage)) {
                throw new JsonParseException("sidebar.presentation.fixed_page references unknown page " + fixedPage);
            }
            Set<String> ids = new HashSet<>();
            for (String id : pageIds) {
                if (!ids.add(id) || (!knownPages.isEmpty() && !knownPages.contains(id))) {
                    throw new JsonParseException("sidebar.presentation.page_ids contains an invalid page " + id);
                }
            }
        }

        private JsonObject toJson() {
            JsonObject value = new JsonObject();
            value.addProperty("mode", mode.serializedName());
            value.addProperty("rotation_ticks", rotationTicks);
            value.addProperty("fixed_page", fixedPage);
            JsonArray ids = new JsonArray();
            pageIds.forEach(ids::add);
            value.add("page_ids", ids);
            return value;
        }
    }

    public enum PageType {
        TEXT("text"), LEADERBOARD("leaderboard");

        private final String serializedName;

        PageType(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }

        static PageType parse(String value) {
            for (PageType type : values()) {
                if (type.serializedName.equalsIgnoreCase(value == null ? "" : value.trim())) {
                    return type;
                }
            }
            throw new JsonParseException("sidebar page type must be text or leaderboard");
        }
    }

    public enum PresentationMode {
        FIXED("fixed"), ROTATE("rotate");

        private final String serializedName;

        PresentationMode(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }

        static PresentationMode parse(String value) {
            for (PresentationMode mode : values()) {
                if (mode.serializedName.equalsIgnoreCase(value == null ? "" : value.trim())) {
                    return mode;
                }
            }
            throw new JsonParseException("sidebar.presentation.mode must be fixed or rotate");
        }
    }

    public enum ConflictPolicy {
        SKIP("skip"), REPLACE("replace"), RESTORE("restore");

        private final String serializedName;

        ConflictPolicy(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }

        static ConflictPolicy parse(String value) {
            if ("warn".equalsIgnoreCase(value == null ? "" : value.trim())
                    || "disabled".equalsIgnoreCase(value == null ? "" : value.trim())) {
                return SKIP;
            }
            for (ConflictPolicy policy : values()) {
                if (policy.serializedName.equalsIgnoreCase(value == null ? "" : value.trim())) {
                    return policy;
                }
            }
            throw new JsonParseException("sidebar.conflict_policy must be skip, replace, or restore");
        }
    }

    private static String normalizePageId(String value, String field) {
        String id = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!ID.matcher(id).matches()) {
            throw new JsonParseException("sidebar.presentation." + field + " must contain valid page ids");
        }
        return id;
    }
}
