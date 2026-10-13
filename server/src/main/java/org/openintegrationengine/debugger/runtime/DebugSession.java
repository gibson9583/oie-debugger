package org.openintegrationengine.debugger.runtime;

import static org.openintegrationengine.debugger.runtime.DebugOptions.*;

import org.openintegrationengine.debugger.runtime.CommandReceipts.Pending;
import org.openintegrationengine.debugger.runtime.DebugRuntime.Execution;
import org.openintegrationengine.debugger.runtime.DebugRuntime.Identity;

import java.util.*;

/**
 * Mutable state is guarded by this session monitor; closed is also read by the evaluation worker.
 */
final class DebugSession {
    final long createdAtNanos;
    final String id = UUID.randomUUID().toString(),
            owner,
            tabId,
            channel,
            messageId,
            connectorId,
            sourceId,
            scriptKind;
    final String requestId;
    final Map<String, Object> creationOptions;
    final boolean evaluationEnabled,
            includeLifecycle,
            pauseOnExceptions,
            pauseOnDebugger,
            breakOnEntry;
    final long leaseMillis, maxPauseMillis;
    final Map<String, Map<Integer, Breakpoint>> breakpoints = new LinkedHashMap<>();
    final CommandReceipts commands = new CommandReceipts();
    final Queue<Pending> queue = new ArrayDeque<>();
    List<String> watches;
    volatile boolean closed;
    boolean paused, entryPending, pauseRequested;
    long leaseDeadline, pauseDeadline, pauseGeneration, closedAt;
    int stepDepth;
    String step,
            reason = "Waiting for the next matching execution",
            closeReason,
            lastError,
            exception,
            executionId;
    Execution execution;
    Identity identity;
    List<Map<String, Object>> frames = List.of(), watchResults = List.of();
    Map<String, Object> inspection = Map.of();

    DebugSession(String owner, String channel, Map<String, Object> options, long createdAtNanos) {
        this.createdAtNanos = createdAtNanos;
        this.owner = owner;
        this.channel = channel;
        tabId = requiredString(options, "tabId");
        requireText(tabId, "tabId", 128);
        requestId = creationRequestId(options);
        creationOptions = requestId == null ? Map.of() : immutableOptions(options);
        messageId = optionalString(options, "messageId");
        connectorId = optionalString(options, "connectorId");
        sourceId = optionalString(options, "sourceId");
        scriptKind = optionalString(options, "scriptKind");
        evaluationEnabled = bool(options, "evaluationEnabled", false);
        includeLifecycle = bool(options, "includeLifecycle", false) || channel.equals("global");
        pauseOnExceptions = bool(options, "pauseOnExceptions", false);
        pauseOnDebugger = bool(options, "pauseOnDebugger", true);
        breakOnEntry = bool(options, "breakOnEntry", true);
        watches = expressions(options.get("watches"));
        leaseMillis = Math.max(2_000, Math.min(60_000, number(options, "leaseMillis", 15_000)));
        maxPauseMillis =
                Math.max(1_000, Math.min(120_000, number(options, "maxPauseMillis", 60_000)));
        leaseDeadline = now() + leaseMillis;
    }

    static final class Breakpoint {
        final String condition;
        final long hitCount;
        final boolean enabled;
        long hits;

        Breakpoint(String condition, long hitCount, boolean enabled) {
            this.condition = condition;
            this.hitCount = hitCount;
            this.enabled = enabled;
        }
    }
}
