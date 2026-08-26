package dev.modmind.omnitools.config;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

/**
 * Small command-registration boundary used by modules as command ownership is migrated out of
 * the central entrypoint. Registrars only build Brigadier nodes; permission checks remain in code.
 */
public final class ModuleCommandRegistrar {
    @FunctionalInterface
    public interface Registration {
        void register(CommandDispatcher<CommandSourceStack> dispatcher);
    }

    private final EnumMap<ModuleId, List<Registration>> registrations = new EnumMap<>(ModuleId.class);

    public synchronized void register(ModuleId module, Registration registration) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(registration, "registration");
        registrations.computeIfAbsent(module, ignored -> new ArrayList<>()).add(registration);
    }

    public synchronized void registerAll(CommandDispatcher<CommandSourceStack> dispatcher) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        registrations.values().forEach(list -> list.forEach(registration -> registration.register(dispatcher)));
    }

    public synchronized int registrationCount(ModuleId module) {
        return registrations.getOrDefault(module, List.of()).size();
    }
}
