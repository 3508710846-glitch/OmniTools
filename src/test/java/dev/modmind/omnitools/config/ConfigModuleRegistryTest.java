package dev.modmind.omnitools.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigModuleRegistryTest {
    @Test
    void rejectsDuplicateModuleRegistration() {
        ConfigModuleRegistry registry = new ConfigModuleRegistry();
        org.junit.jupiter.api.Assertions.assertTrue(registry.modules().isEmpty());
        ConfigurableModule<String> module = new ConfigurableModule<>() {
            @Override
            public ModuleId id() {
                return ModuleId.CDK;
            }

            @Override
            public String load(LoadContext context) {
                return "ok";
            }
        };
        registry.register(module);
        assertThrows(IllegalArgumentException.class, () -> registry.register(module));
    }

    @Test
    void loadsOneRegisteredModuleWithoutLoadingTheRest() throws Exception {
        ConfigModuleRegistry registry = new ConfigModuleRegistry();
        registry.register(new ConfigurableModule<String>() {
            @Override
            public ModuleId id() {
                return ModuleId.CDK;
            }

            @Override
            public String load(LoadContext context) {
                return "cdk";
            }
        });

        assertEquals("cdk", registry.load(ModuleId.CDK, null));
        assertThrows(IllegalArgumentException.class, () -> registry.load(ModuleId.SHOP, null));
    }

    @Test
    void retainsTheFailingModuleAndCauseForReloadDiagnostics() {
        ConfigModuleRegistry registry = new ConfigModuleRegistry();
        IllegalStateException cause = new IllegalStateException("invalid package entry");
        registry.register(new ConfigurableModule<String>() {
            @Override
            public ModuleId id() {
                return ModuleId.PACKAGES;
            }

            @Override
            public String load(LoadContext context) {
                throw cause;
            }
        });

        ConfigModuleRegistry.ModuleLoadException exception = assertThrows(
                ConfigModuleRegistry.ModuleLoadException.class, () -> registry.loadAll(null));

        assertEquals(ModuleId.PACKAGES, exception.module());
        assertEquals(cause, exception.getCause());
        assertEquals("Could not load configuration module packages", exception.getMessage());
    }
}
