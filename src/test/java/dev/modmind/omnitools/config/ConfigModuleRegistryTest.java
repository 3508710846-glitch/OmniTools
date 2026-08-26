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
}
