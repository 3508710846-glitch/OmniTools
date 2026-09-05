package dev.modmind.omnitools.diagnostics;

import dev.modmind.omnitools.config.ModuleId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A bounded background writer for non-authoritative audit copies.
 *
 * <p>Transaction journals and SavedData remain the source of truth and are persisted by their
 * owning module before records reach this writer. This worker never receives Minecraft objects.</p>
 */
public final class AsyncAuditLogWriter {
    private static final int QUEUE_CAPACITY = 512;
    private static final int MAX_ATTEMPTS = 3;
    private static final AsyncAuditLogWriter GLOBAL = new AsyncAuditLogWriter();

    private final ArrayBlockingQueue<Job> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final Thread worker;

    public static AsyncAuditLogWriter global() {
        return GLOBAL;
    }

    AsyncAuditLogWriter() {
        worker = new Thread(this::run, "omnitools-audit-writer");
        worker.setDaemon(true);
        worker.start();
    }

    public boolean submit(ModuleId module, String feature, Path path, String line) {
        if (path == null || line == null) {
            return false;
        }
        WriteJob job = new WriteJob(module, feature == null ? "audit_write" : feature, path, line);
        if (queue.offer(job)) {
            accepted.incrementAndGet();
            return true;
        }
        rejected.incrementAndGet();
        OperationalErrorReporter.global().warn(OperationalErrorReporter.Context.forModule(module, job.feature())
                .withParameters(Map.of("path", path.toString(), "queueCapacity", Integer.toString(QUEUE_CAPACITY)))
                .withRecoveryAction("audit_record_rejected_before_write"),
                new AuditQueueFullException());
        return false;
    }

    /** Waits briefly for records accepted before the marker; does not stop the daemon worker. */
    public boolean flush(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            return queue.isEmpty();
        }
        CountDownLatch drained = new CountDownLatch(1);
        long deadline = System.nanoTime() + timeout.toNanos();
        FlushJob marker = new FlushJob(drained);
        long remaining;
        while (!(queue.offer(marker))) {
            remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                return false;
            }
            try {
                Thread.sleep(Math.min(25L, TimeUnit.NANOSECONDS.toMillis(remaining) + 1L));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        try {
            remaining = deadline - System.nanoTime();
            return remaining > 0L && drained.await(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public Metrics metrics() {
        return new Metrics(accepted.get(), rejected.get(), completed.get(), failed.get(), queue.size(), QUEUE_CAPACITY);
    }

    private void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                queue.take().run();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void write(WriteJob job) {
        IOException failure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                Files.createDirectories(job.path().getParent());
                Files.writeString(job.path(), job.line(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                completed.incrementAndGet();
                return;
            } catch (IOException exception) {
                failure = exception;
                if (attempt < MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(25L * attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        failed.incrementAndGet();
        OperationalErrorReporter.global().warn(OperationalErrorReporter.Context.forModule(job.module(), job.feature())
                .withParameters(Map.of("path", job.path().toString(), "attempts", Integer.toString(MAX_ATTEMPTS)))
                .withRecoveryAction("audit_record_dropped_after_retries"), failure);
    }

    public record Metrics(long accepted, long rejected, long completed, long failed,
                          int queueDepth, int queueCapacity) {
    }

    private sealed interface Job permits WriteJob, FlushJob {
        void run();
    }

    private final class WriteJob implements Job {
        private final ModuleId module;
        private final String feature;
        private final Path path;
        private final String line;

        private WriteJob(ModuleId module, String feature, Path path, String line) {
            this.module = module;
            this.feature = feature;
            this.path = path;
            this.line = line;
        }

        @Override
        public void run() {
            write(this);
        }

        private ModuleId module() {
            return module;
        }

        private String feature() {
            return feature;
        }

        private Path path() {
            return path;
        }

        private String line() {
            return line;
        }
    }

    private static final class FlushJob implements Job {
        private final CountDownLatch drained;

        private FlushJob(CountDownLatch drained) {
            this.drained = drained;
        }

        @Override
        public void run() {
            drained.countDown();
        }
    }

    private static final class AuditQueueFullException extends RuntimeException {
        private AuditQueueFullException() {
            super("audit queue capacity reached");
        }
    }
}
