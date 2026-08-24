package dev.modmind.omnitools.commandmenu;

import dev.modmind.omnitools.permissions.CommandRole;

/** A registered menu and its parsed page. */
public record CommandMenuDefinition(String id, String file, CommandRole permission,
                                    CommandMenuPageConfig page) {
}
