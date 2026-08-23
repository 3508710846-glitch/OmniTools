package dev.modmind.omnitools.permissions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.modmind.omnitools.config.ConfigPaths;
import dev.modmind.omnitools.config.ModuleId;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/** Administrator-editable command role assignments. */
public record CommandPermissionConfig(int formatVersion, Map<CommandAction, CommandRole> roles,
                                      boolean storageAllowNativeNode, boolean allowTitleCommandGrants) {
    private static final int CURRENT_FORMAT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = ConfigPaths.moduleConfig(ModuleId.PERMISSIONS);

    public CommandPermissionConfig {
        if (formatVersion < 1) {
            throw new JsonParseException("permissions.format_version must be a positive integer");
        }
        EnumMap<CommandAction, CommandRole> copy = new EnumMap<>(CommandAction.class);
        for (CommandAction action : CommandAction.values()) {
            copy.put(action, roles == null ? action.defaultRole() : roles.getOrDefault(action, action.defaultRole()));
        }
        roles = Map.copyOf(copy);
    }

    public static CommandPermissionConfig defaults() {
        EnumMap<CommandAction, CommandRole> roles = new EnumMap<>(CommandAction.class);
        for (CommandAction action : CommandAction.values()) {
            roles.put(action, action.defaultRole());
        }
        return new CommandPermissionConfig(CURRENT_FORMAT_VERSION, roles, true, false);
    }

    public CommandRole role(CommandAction action) {
        return roles.getOrDefault(action, action.defaultRole());
    }

    public static CommandPermissionConfig load() {
        if (!Files.exists(FILE)) {
            CommandPermissionConfig defaults = defaults();
            try {
                save(defaults);
            } catch (IOException exception) {
                throw new IllegalStateException("Could not create " + FILE, exception);
            }
            return defaults;
        }
        try (JsonReader reader = new JsonReader(Files.newBufferedReader(FILE, StandardCharsets.UTF_8))) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                throw new JsonParseException("permissions configuration must be an object");
            }
            EnumMap<CommandAction, CommandRole> roles = new EnumMap<>(CommandAction.class);
            boolean nativeNode = defaults().storageAllowNativeNode();
            int version = CURRENT_FORMAT_VERSION;
            boolean allowTitleGrants = false;
            boolean seenVersion = false;
            boolean seenAllowTitleGrants = false;
            boolean seenCommands = false;
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                switch (name) {
                    case "format_version" -> {
                        if (seenVersion || reader.peek() != JsonToken.NUMBER) {
                            throw new JsonParseException("format_version must appear once as an integer");
                        }
                        seenVersion = true;
                        version = reader.nextInt();
                    }
                    case "allow_title_command_grants" -> {
                        if (seenAllowTitleGrants || reader.peek() != JsonToken.BOOLEAN) {
                            throw new JsonParseException("allow_title_command_grants must appear once as a boolean");
                        }
                        seenAllowTitleGrants = true;
                        allowTitleGrants = reader.nextBoolean();
                    }
                    case "commands" -> {
                        if (seenCommands || reader.peek() != JsonToken.BEGIN_OBJECT) {
                            throw new JsonParseException("commands must appear once as an object");
                        }
                        seenCommands = true;
                        reader.beginObject();
                        while (reader.hasNext()) {
                            String actionId = reader.nextName();
                            CommandAction action = CommandAction.parse(actionId).orElseThrow(() ->
                                    new JsonParseException("unknown command action: " + actionId));
                            if (roles.containsKey(action)) {
                                throw new JsonParseException("command action is configured more than once: "
                                        + action.id());
                            }
                            String roleText;
                            if (reader.peek() == JsonToken.STRING) {
                                roleText = reader.nextString();
                            } else if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                boolean seenRole = false;
                                boolean seenNativeNode = false;
                                reader.beginObject();
                                roleText = null;
                                while (reader.hasNext()) {
                                    String option = reader.nextName();
                                    if (option.equals("role")) {
                                        if (seenRole || reader.peek() != JsonToken.STRING) {
                                            throw new JsonParseException("role must appear once as a string for "
                                                    + action.id());
                                        }
                                        seenRole = true;
                                        roleText = reader.nextString();
                                    } else if (option.equals("allow_native_node")
                                            && action == CommandAction.STORAGE_OPEN) {
                                        if (seenNativeNode || reader.peek() != JsonToken.BOOLEAN) {
                                            throw new JsonParseException("allow_native_node must appear once as a boolean");
                                        }
                                        seenNativeNode = true;
                                        nativeNode = reader.nextBoolean();
                                    } else {
                                        throw new JsonParseException("unknown option for command action " + action.id()
                                                + ": " + option);
                                    }
                                }
                                reader.endObject();
                                if (!seenRole) {
                                    throw new JsonParseException("role is required for command action " + action.id());
                                }
                            } else {
                                throw new JsonParseException("command action " + action.id()
                                        + " must be a role string or object");
                            }
                            roles.put(action, CommandRole.parse(roleText));
                        }
                        reader.endObject();
                    }
                    default -> throw new JsonParseException("unknown permissions configuration property: " + name);
                }
            }
            reader.endObject();
            if (!seenCommands) {
                throw new JsonParseException("commands must be an object");
            }
            return new CommandPermissionConfig(version, roles, nativeNode, allowTitleGrants);
        } catch (IOException | JsonParseException | IllegalArgumentException exception) {
            System.err.println("[omnitools] Could not load " + FILE + ": " + exception.getMessage());
            throw new IllegalStateException("Invalid command permission configuration", exception);
        }
    }

    public static Path path() {
        return FILE;
    }

    public static void save(CommandPermissionConfig config) throws IOException {
        Files.createDirectories(FILE.getParent());
        JsonObject root = new JsonObject();
        root.addProperty("format_version", config.formatVersion());
        root.addProperty("allow_title_command_grants", config.allowTitleCommandGrants());
        JsonObject commands = new JsonObject();
        for (CommandAction action : CommandAction.values()) {
            if (action == CommandAction.STORAGE_OPEN) {
                JsonObject storage = new JsonObject();
                storage.addProperty("role", config.role(action).name());
                storage.addProperty("allow_native_node", config.storageAllowNativeNode());
                commands.add(action.id(), storage);
            } else {
                commands.addProperty(action.id(), config.role(action).name());
            }
        }
        root.add("commands", commands);
        try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
    }

}
