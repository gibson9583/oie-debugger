package org.openintegrationengine.debugger.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandReceiptsTest {
    @Test void thousandsOfResultsStayBoundedAndExpiredSequencesCannotReplay() {
        CommandReceipts store = new CommandReceipts();
        for (long sequence = 1; sequence <= 5000; sequence++) {
            Map<String, Object> request = Map.of("commandSequence", sequence);
            store.validateSequence(request);
            var pending = new CommandReceipts.Pending("id-" + sequence, request);
            pending.completed = true;
            store.accept(pending);
        }
        assertEquals(CommandReceipts.RETAINED_RESULTS, store.size());
        assertNull(store.get("id-1"));
        assertNotNull(store.get("id-5000"));
        assertThrows(IllegalStateException.class, () -> store.validateSequence(Map.of("commandSequence", 1)));
        assertThrows(IllegalStateException.class, () -> store.validateSequence(Map.of("commandSequence", 5000)));
        assertDoesNotThrow(() -> store.validateSequence(Map.of("commandSequence", 5002)));
    }

    @Test void pendingResultsAreNeverEvictedAndRequestSnapshotsAreDeeplyImmutable() {
        CommandReceipts store = new CommandReceipts();
        List<Object> lines = new ArrayList<>(List.of(new HashMap<>(Map.of("line", 1))));
        var pending = new CommandReceipts.Pending("in-flight", Map.of("commandSequence", 1, "lines", lines));
        store.accept(pending);
        lines.clear();
        assertEquals(List.of(Map.of("line", 1)), pending.request.get("lines"));
        for (long sequence = 2; sequence <= 100; sequence++) {
            var completed = new CommandReceipts.Pending("id-" + sequence, Map.of("commandSequence", sequence));
            completed.completed = true;
            store.accept(completed);
        }
        assertSame(pending, store.get("in-flight"));
        assertEquals(CommandReceipts.RETAINED_RESULTS + 1, store.size());
    }

    @Test void sequenceIsMandatoryIntegralPositiveAndSafeForJavaScript() {
        CommandReceipts store = new CommandReceipts();
        assertThrows(IllegalArgumentException.class, () -> store.validateSequence(Map.of()));
        for (Object invalid : List.of(0, -1, 1.5, "1", 9_007_199_254_740_992L))
            assertThrows(IllegalArgumentException.class, () -> store.validateSequence(Map.of("commandSequence", invalid)));
    }
}
