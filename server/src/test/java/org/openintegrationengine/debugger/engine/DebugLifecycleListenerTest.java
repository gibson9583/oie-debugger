package org.openintegrationengine.debugger.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.mirth.connect.donkey.model.event.DeployedStateEventType;
import com.mirth.connect.donkey.server.event.DeployedStateEvent;
import com.mirth.connect.server.event.EventListener;

class DebugLifecycleListenerTest {
    @Test void releasesEarlyChannelAndConnectorStopsWithoutInvalidatingCachedScripts() throws Exception {
        List<String> released = new ArrayList<>();
        DebugLifecycleListener listener = new DebugLifecycleListener(event -> released.add(event.getChannelId()));
        try {
            listener.processEvent(event(DeployedStateEventType.STOPPING));
            listener.processEvent(event(DeployedStateEventType.PAUSING));
            listener.processEvent(event(DeployedStateEventType.DEPLOYED));
            listener.processEvent(event(DeployedStateEventType.UNDEPLOYED));
            listener.processEvent(event(DeployedStateEventType.STARTED));
            listener.processEvent(event(DeployedStateEventType.STOPPED));
            assertEquals(List.of("channel", "channel", "channel", "channel"), released);
        } finally { stop(listener); }
    }

    @Test void overloadedListenerNeverBlocksEngineProducersAndReportsDrops() throws Exception {
        CountDownLatch processing = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        DebugLifecycleListener listener = new DebugLifecycleListener(event -> {
            processing.countDown();
            try { gate.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        try {
            listener.getQueue().put(event(DeployedStateEventType.STOPPING));
            assertTrue(processing.await(2, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                for (int i = 0; i < 1_000; i++) listener.getQueue().put(event(DeployedStateEventType.STOPPING));
            });
            assertEquals(256, listener.getQueue().size());
            assertEquals(744, listener.getDroppedEvents());
        } finally {
            gate.countDown();
            stop(listener);
        }
    }

    private static DeployedStateEvent event(DeployedStateEventType state) {
        return new DeployedStateEvent("channel", "Channel", 1, "Connector", state);
    }

    private static void stop(DebugLifecycleListener listener) throws Exception {
        listener.shutdown();
        Field field = EventListener.class.getDeclaredField("workerThread");
        field.setAccessible(true);
        Thread worker = (Thread) field.get(listener);
        worker.join(2_000);
        assertFalse(worker.isAlive());
    }
}
