package org.openintegrationengine.debugger.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/** All access is protected by the owning session's monitor, including completion. */
final class CommandReceipts {
    static final int RETAINED_RESULTS = 64;
    private final Map<String, Pending> receipts = new LinkedHashMap<>();
    private long highWater;

    Pending get(String id) {
        return receipts.get(id);
    }

    long highWater() {
        return highWater;
    }

    int size() {
        return receipts.size();
    }

    void validateSequence(Map<String, Object> request) {
        Object raw = request.get("commandSequence");
        if (!(raw instanceof Byte
                        || raw instanceof Short
                        || raw instanceof Integer
                        || raw instanceof Long)
                || ((Number) raw).longValue() < 1
                || ((Number) raw).longValue() > 9_007_199_254_740_991L) {
            throw new IllegalArgumentException(
                    "commandSequence must be a positive safe integer; reload the debugger after"
                        + " upgrading");
        }
        if (((Number) raw).longValue() <= highWater) {
            throw new IllegalStateException(
                    "Command receipt expired or command arrived out of order; it will not execute"
                        + " again");
        }
    }

    void accept(Pending pending) {
        highWater = ((Number) pending.request.get("commandSequence")).longValue();
        receipts.put(pending.id, pending);
        trim();
    }

    void trim() {
        // Pending/in-flight work is additionally bounded by the runtime queue.
        int completed = (int) receipts.values().stream().filter(item -> item.completed).count();
        var iterator = receipts.values().iterator();
        while (completed > RETAINED_RESULTS && iterator.hasNext()) {
            if (iterator.next().completed) {
                iterator.remove();
                completed--;
            }
        }
    }

    static final class Pending {
        final String id;
        final Map<String, Object> request;
        boolean completed;
        Object result;
        String error;

        Pending(String id, Map<String, Object> request) {
            this.id = id;
            this.request = DebugOptions.immutableOptions(request);
        }

        Map<String, Object> dto() {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("commandId", id);
            dto.put("status", completed ? "completed" : "pending");
            if (result != null) dto.put("result", result);
            if (error != null) dto.put("error", error);
            return dto;
        }
    }
}
