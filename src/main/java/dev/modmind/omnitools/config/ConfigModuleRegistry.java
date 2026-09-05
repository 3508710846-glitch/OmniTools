package dev.modmind.omnitools.config;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Registry for typed module loaders and lifecycle adapters. */
public final class ConfigModuleRegistry {
    private final EnumMap<ModuleId, ConfigurableModule<?>> modules = new EnumMap<>(ModuleId.class);

    public synchronized ConfigModuleRegistry register(ConfigurableModule<?> module) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(module.id(), "module.id");
        if (modules.putIfAbsent(module.id(), module) != null) {
            throw new IllegalArgumentException("Configuration module is already registered: " + module.id().id());
        }
        return this;
    }

    public synchronized boolean contains(ModuleId id) {
        return modules.containsKey(id);
    }

    public synchronized Map<ModuleId, ConfigurableModule<?>> modules() {
        EnumMap<ModuleId, ConfigurableModule<?>> copy = new EnumMap<>(ModuleId.class);
        copy.putAll(modules);
        return Collections.unmodifiableMap(copy);
    }

    @SuppressWarnings("unchecked")
    public synchronized <T> ConfigurableModule<T> require(ModuleId id) {
        ConfigurableModule<?> module = modules.get(id);
        if (module == null) {
            throw new IllegalArgumentException("Configuration module is not registered: " + id);
        }
        return (ConfigurableModule<T>) module;
    }

    public synchronized Map<ModuleId, Object> loadAll(LoadContext context) throws Exception {
        Map<ModuleId, Object> loaded = new LinkedHashMap<>();
        for (Map.Entry<ModuleId, ConfigurableModule<?>> entry : modules.entrySet()) {
            ModuleId module = entry.getKey();
            loaded.put(module, load(module, context));
        }
        return Map.copyOf(loaded);
    }

    /**
     * Loads every module independently so one malformed optional module cannot prevent unrelated
     * modules from receiving a valid snapshot.
     */
    public synchronized LoadReport loadAllIsolated(LoadContext context) {
        Map<ModuleId, Object> loaded = new LinkedHashMap<>();
        Map<ModuleId, ModuleLoadException> failures = new EnumMap<>(ModuleId.class);
        for (ModuleId module : modules.keySet()) {
            try {
                loaded.put(module, load(module, context));
            } catch (ModuleLoadException exception) {
                failures.put(module, exception);
            } catch (Exception exception) {
                failures.put(module, new ModuleLoadException(module, exception));
            }
        }
        return new LoadReport(loaded, failures);
    }

    /** Loads one module through the same typed lifecycle used by full snapshot reloads. */
    public synchronized Object load(ModuleId id, LoadContext context) throws Exception {
        ConfigurableModule<?> module = require(id);
        try {
            return load(module, context);
        } catch (ModuleLoadException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ModuleLoadException(id, exception);
        }
    }

    public record LoadReport(Map<ModuleId, Object> loaded,
                             Map<ModuleId, ModuleLoadException> failures) {
        public LoadReport {
            loaded = Map.copyOf(loaded == null ? Map.of() : loaded);
            failures = Map.copyOf(failures == null ? Map.of() : failures);
        }

        public boolean failed(ModuleId module) {
            return failures.containsKey(module);
        }
    }

    /** Retains the module identity when a typed loader rejects its configuration. */
    public static final class ModuleLoadException extends Exception {
        private final ModuleId module;

        public ModuleLoadException(ModuleId module, Exception cause) {
            super("Could not load configuration module " + module.id(), cause);
            this.module = module;
        }

        public ModuleId module() {
            return module;
        }
    }

    public synchronized void validateAll(Map<ModuleId, Object> loaded, OmniToolsConfigSnapshot snapshot) {
        for (Map.Entry<ModuleId, ConfigurableModule<?>> entry : modules.entrySet()) {
            validate(entry.getValue(), loaded.get(entry.getKey()), snapshot);
        }
    }

    public synchronized void applyAll(Map<ModuleId, Object> previous, Map<ModuleId, Object> current,
                                      RuntimeContext runtime) {
        for (Map.Entry<ModuleId, ConfigurableModule<?>> entry : modules.entrySet()) {
            apply(entry.getValue(), previous.get(entry.getKey()), current.get(entry.getKey()), runtime);
        }
    }

    private static <T> T load(ConfigurableModule<T> module, LoadContext context) throws Exception {
        return module.load(context);
    }

    private static <T> void validate(ConfigurableModule<T> module, Object config,
                                     OmniToolsConfigSnapshot snapshot) {
        module.validate(moduleConfig(config), snapshot);
    }

    private static <T> void apply(ConfigurableModule<T> module, Object previous, Object current,
                                  RuntimeContext runtime) {
        module.apply(moduleConfig(previous), moduleConfig(current), runtime);
    }

    @SuppressWarnings("unchecked")
    private static <T> T moduleConfig(Object value) {
        return (T) value;
    }
}
