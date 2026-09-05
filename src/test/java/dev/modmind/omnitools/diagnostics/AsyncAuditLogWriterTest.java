package dev.modmind.omnitools.diagnostics;

import dev.modmind.omnitools.config.ModuleId;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncAuditLogWriterTest {
    @Test
    void flushesAcceptedAuditRecord() throws Exception {
        Path directory = Files.createTempDirectory("omnitools-audit-test");
        Path log = directory.resolve("audit.log");
        AsyncAuditLogWriter writer = new AsyncAuditLogWriter();

        assertTrue(writer.submit(ModuleId.SHOP, "test_audit_write", log, "record-one\n"));
        assertTrue(writer.flush(Duration.ofSeconds(2L)));
        assertEquals("record-one\n", Files.readString(log));
        assertEquals(1L, writer.metrics().completed());

        Files.deleteIfExists(log);
        Files.deleteIfExists(directory);
    }
}
