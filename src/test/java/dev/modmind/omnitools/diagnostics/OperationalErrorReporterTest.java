package dev.modmind.omnitools.diagnostics;

import dev.modmind.omnitools.config.ModuleId;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OperationalErrorReporterTest {
    @Test
    void aggregatesRepeatedFailuresWithStructuredContext() {
        OperationalErrorReporter reporter = new OperationalErrorReporter();
        UUID playerId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID operationId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        OperationalErrorReporter.Context context = OperationalErrorReporter.Context
                .forModule(ModuleId.CLOUD_STORAGE, "journal_reconcile")
                .withPlayer(playerId)
                .withOperation(operationId)
                .withWorld("minecraft:overworld")
                .withDataVersion("config:2")
                .withState("PREPARED")
                .withParameters(Map.of("page", "0"))
                .withRecoveryAction("prepared_operation_quarantined");

        OperationalErrorReporter.Report first = reporter.warn(context, new IllegalStateException("page unavailable"));
        OperationalErrorReporter.Report second = reporter.warn(context, new IllegalStateException("page unavailable"));
        OperationalErrorReporter.Report lifecycle = reporter.info(OperationalErrorReporter.Context
                .forModule(ModuleId.CLOUD_STORAGE, "config_reload")
                .withState("APPLIED"), "configuration reload applied");

        assertEquals(1, first.occurrencesInWindow());
        assertEquals(2, second.occurrencesInWindow());
        assertEquals(OperationalErrorReporter.Severity.INFO, lifecycle.severity());
        assertEquals(playerId, second.context().playerId());
        assertEquals(operationId, second.context().operationId());
        assertEquals("minecraft:overworld", second.context().world());
        assertEquals("config:2", second.context().dataVersion());
        assertEquals(2, reporter.summary().reportsByModule().get(ModuleId.CLOUD_STORAGE));
    }
}
