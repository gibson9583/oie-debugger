package org.openintegrationengine.debugger.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JsonTest {
    @Test void acceptsPlainObjectsAndRejectsAmbiguousOrUnboundedRequests() {
        assertEquals("pause", Json.read("{\"action\":\"pause\"}").get("action"));
        for (String body : new String[]{"null", "[]", "{} {}", "{\"a\":1,\"a\":2}", " ".repeat(65537), "{\"a\":" + "[".repeat(30) + "0" + "]".repeat(30) + "}"})
            assertThrows(IllegalArgumentException.class, () -> Json.read(body));
    }
}
