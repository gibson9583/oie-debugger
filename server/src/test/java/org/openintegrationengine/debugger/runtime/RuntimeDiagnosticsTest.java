package org.openintegrationengine.debugger.runtime;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeDiagnosticsTest {
    @Test void concurrentRepeatsCountOnceWithoutLoggingPayloadsOrCauses() throws Exception {
        List<Throwable> warnings = new CopyOnWriteArrayList<>();
        RuntimeDiagnostics diagnostics = new RuntimeDiagnostics(warnings::add);
        var workers = Executors.newFixedThreadPool(8);
        try {
            for (int i = 0; i < 100; i++) workers.submit(() -> diagnostics.record(
                    new IllegalStateException("private expression", new RuntimeException("private message"))));
        } finally {
            workers.shutdown();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(100L, diagnostics.counts().get(IllegalStateException.class.getName()));
        assertEquals(1, warnings.size());
        Throwable warning = warnings.get(0);
        assertFalse(warning.toString().contains("private"));
        assertNull(warning.getCause());
        assertEquals(0, warning.getSuppressed().length);
        assertTrue(warning.getStackTrace().length > 0);
    }

    @Test void loggingFailureCannotEscapeIntoTheEngine() {
        RuntimeDiagnostics diagnostics = new RuntimeDiagnostics(failure -> { throw new IllegalStateException("logging broken"); });
        assertDoesNotThrow(() -> diagnostics.record(new IllegalArgumentException("private")));
        assertEquals(1L, diagnostics.counts().get(IllegalArgumentException.class.getName()));
    }
}
