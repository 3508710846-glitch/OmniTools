package dev.modmind.omnitools.commandmenu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.util.Locale;
import java.util.regex.Pattern;

/** Immutable click action parsed from a command-menu file. */
public record CommandMenuAction(Type type, RunAs runAs, String value) {
    private static final Pattern TEXT_PLACEHOLDER = Pattern.compile("%[^%\\r\\n]+%");
    public enum Type {
        OPEN_MENU,
        CLOSE_MENU,
        COMMAND,
        MESSAGE
    }

    public enum RunAs {
        PLAYER,
        CONSOLE
    }

    public static CommandMenuAction parse(JsonElement element, String context, boolean allowConsoleCommands) {
        if (element == null || !element.isJsonObject()) {
            throw new JsonParseException(context + " must be an object");
        }
        JsonObject object = element.getAsJsonObject();
        String rawType = requiredString(object, "type", context);
        Type type = switch (rawType.toLowerCase(Locale.ROOT)) {
            case "open_menu" -> Type.OPEN_MENU;
            case "close_menu" -> Type.CLOSE_MENU;
            case "command" -> Type.COMMAND;
            case "message" -> Type.MESSAGE;
            default -> throw new JsonParseException(context + " has unknown action type: " + rawType);
        };
        if (type == Type.OPEN_MENU) {
            return new CommandMenuAction(type, null, requiredString(object, "menu", context));
        }
        if (type == Type.CLOSE_MENU) {
            return new CommandMenuAction(type, null, "");
        }
        if (type == Type.MESSAGE) {
            return new CommandMenuAction(type, null, requiredString(object, "text", context));
        }

        String rawRunAs = optionalString(object, "run_as", "player", context);
        RunAs runAs;
        try {
            runAs = RunAs.valueOf(rawRunAs.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new JsonParseException(context + " run_as must be player or console");
        }
        if (runAs == RunAs.CONSOLE && !allowConsoleCommands) {
            throw new JsonParseException(context + " uses console commands but allow_console_commands is false");
        }
        String command = requiredString(object, "command", context);
        if (TEXT_PLACEHOLDER.matcher(command).find()) {
            throw new JsonParseException(context + " command must not use text placeholders; use the allowed {player_*} values");
        }
        validatePlaceholders(command, context);
        if (command.length() > 1024) {
            throw new JsonParseException(context + " command is too long");
        }
        return new CommandMenuAction(type, runAs, command);
    }

    private static void validatePlaceholders(String command, String context) {
        for (int index = command.indexOf('{'); index >= 0; index = command.indexOf('{', index + 1)) {
            int end = command.indexOf('}', index + 1);
            if (end < 0) {
                throw new JsonParseException(context + " contains an unterminated placeholder");
            }
            String name = command.substring(index + 1, end);
            if (!switch (name) {
                case "player_name", "player_uuid", "player_x", "player_y", "player_z", "player_world" -> true;
                default -> false;
            }) {
                throw new JsonParseException(context + " contains an unknown placeholder: {" + name + "}");
            }
            index = end;
        }
    }

    private static String requiredString(JsonObject object, String key, String context) {
        String value = optionalString(object, key, null, context);
        if (value == null || value.isBlank()) {
            throw new JsonParseException(context + " requires a non-empty " + key);
        }
        return value.trim();
    }

    private static String optionalString(JsonObject object, String key, String fallback, String context) {
        JsonElement element = object.get(key);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new JsonParseException(context + "." + key + " must be a string");
        }
        return element.getAsString();
    }
}
