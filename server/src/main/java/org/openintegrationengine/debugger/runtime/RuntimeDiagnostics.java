package org.openintegrationengine.debugger.runtime;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Bounded diagnostics, independent of scopes, command bodies and patient data. */
final class RuntimeDiagnostics {
    private static final Logger LOGGER = LogManager.getLogger(DebugRuntime.class);
    private static final int MAX_TYPES = 32;
    private final Map<String, Long> counts = new LinkedHashMap<>();
    private final Consumer<Throwable> warning;

    RuntimeDiagnostics() {
        this(
                failure ->
                        LOGGER.warn(
                                "Debugger internal failure; repeats are counted in capabilities",
                                failure));
    }

    RuntimeDiagnostics(Consumer<Throwable> warning) {
        this.warning = warning;
    }

    void record(RuntimeException failure) {
        try {
            String type = failure.getClass().getName();
            boolean first;
            synchronized (this) {
                if (!counts.containsKey(type) && counts.size() >= MAX_TYPES) type = "other";
                first = !counts.containsKey(type);
                counts.merge(type, 1L, Long::sum);
            }
            if (first) {
                // Throwable messages and causes may embed evaluated expressions or
                // message contents. Retain the type and stack frames, never that text.
                RuntimeException diagnostic =
                        new RuntimeException(type + " (message and causes omitted)");
                diagnostic.setStackTrace(failure.getStackTrace());
                warning.accept(diagnostic);
            }
        } catch (RuntimeException ignored) {
            // A broken logging backend must never break engine processing.
        }
    }

    synchronized Map<String, Long> counts() {
        return Map.copyOf(counts);
    }
}
