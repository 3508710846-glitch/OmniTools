package dev.modmind.omnitools.config;

/**
 * Declarative module lifecycle. Implementations own parsing of their typed file; the platform
 * owns ordering, atomic publication, cross-module validation and runtime application.
 */
public interface ConfigurableModule<T> {
    ModuleId id();

    T load(LoadContext context) throws Exception;

    default void validate(T config, OmniToolsConfigSnapshot snapshot) {
    }

    default void apply(T previous, T current, RuntimeContext runtime) {
    }
}
