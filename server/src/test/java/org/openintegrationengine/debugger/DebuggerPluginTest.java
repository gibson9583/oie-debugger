package org.openintegrationengine.debugger;

import java.util.Properties;
import com.mirth.connect.model.Channel;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.DefaultContextFactoryController;
import com.mirth.connect.server.controllers.EventController;
import com.mirth.connect.server.event.EventListener;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openintegrationengine.debugger.engine.DebugContextFactoryController;
import org.openintegrationengine.debugger.engine.DebugLifecycleListener;
import org.openintegrationengine.debugger.engine.EngineCompatibility;
import org.openintegrationengine.debugger.runtime.DebugRuntime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DebuggerPluginTest {
    @Test void startRegistersOnceAndStopRemovesAndTerminatesTheListener() throws Exception {
        try (Harness h = new Harness()) {
            h.plugin.start(); h.plugin.start();
            DebugLifecycleListener listener = h.listener();
            verify(h.events).addListener(listener);
            assertNull(DebuggerPlugin.unavailableReason());
            h.plugin.stop();
            verify(h.events).removeListener(listener);
            assertStopped(listener);
            h.plugin.start();
            assertNotSame(listener, h.listener());
            assertNull(DebuggerPlugin.unavailableReason());
        }
    }

    @Test void absentControllerOverrideDisablesTheRuntimeWithoutStartingAListener() throws Exception {
        try (Harness h = new Harness()) {
            when(h.controllers.createContextFactoryController()).thenReturn(mock(DefaultContextFactoryController.class));
            h.plugin.start();
            verify(h.runtime).configure(false, false);
            verifyNoInteractions(h.events);
            assertNotNull(DebuggerPlugin.unavailableReason());
        }
    }

    @Test void registrationFailureAlsoShutsDownTheNewListener() throws Exception {
        try (Harness h = new Harness()) {
            var captured = new java.util.concurrent.atomic.AtomicReference<DebugLifecycleListener>();
            doAnswer(invocation -> {
                captured.set(invocation.getArgument(0));
                throw new IllegalStateException("registration failed");
            }).when(h.events).addListener(any());
            assertThrows(IllegalStateException.class, h.plugin::start);
            assertStopped(captured.get());
            verify(h.runtime).failed(any());
            assertNotNull(DebuggerPlugin.unavailableReason());
        }
    }

    @Test void removalFailureCannotLeakTheListenerWorker() throws Exception {
        try (Harness h = new Harness()) {
            h.plugin.start();
            DebugLifecycleListener listener = h.listener();
            doThrow(new IllegalStateException("remove failed")).when(h.events).removeListener(listener);
            assertThrows(IllegalStateException.class, h.plugin::stop);
            assertStopped(listener);
        }
    }

    @Test void removingAChannelInvalidatesItsSourcesAndSessions() throws Exception {
        try (Harness h = new Harness()) {
            Channel channel = new Channel(); channel.setId("removed-channel");
            h.plugin.remove(channel, null);
            verify(h.runtime).invalidate("removed-channel", "Channel removed");
        }
    }

    private static void assertStopped(DebugLifecycleListener listener) throws Exception {
        assertNotNull(listener);
        var field = EventListener.class.getDeclaredField("workerThread"); field.setAccessible(true);
        Thread worker = (Thread) field.get(listener);
        worker.join(3000);
        assertFalse(worker.isAlive(), "Owned event listener must exit");
    }

    private static final class Harness implements AutoCloseable {
        final DebugRuntime runtime = mock(DebugRuntime.class);
        final ControllerFactory controllers = mock(ControllerFactory.class);
        final EventController events = mock(EventController.class);
        final MockedStatic<DebugRuntime> runtimes = mockStatic(DebugRuntime.class);
        final MockedStatic<ControllerFactory> factories = mockStatic(ControllerFactory.class);
        final MockedStatic<EventController> eventControllers = mockStatic(EventController.class);
        final MockedStatic<EngineCompatibility> compatibility = mockStatic(EngineCompatibility.class);
        final DebuggerPlugin plugin = new DebuggerPlugin();

        Harness() {
            runtimes.when(DebugRuntime::getInstance).thenReturn(runtime);
            factories.when(ControllerFactory::getFactory).thenReturn(controllers);
            eventControllers.when(EventController::getInstance).thenReturn(events);
            when(controllers.createContextFactoryController()).thenReturn(mock(DebugContextFactoryController.class));
            Properties properties = new Properties(); properties.setProperty("enabled", "true");
            plugin.init(properties);
        }

        DebugLifecycleListener listener() throws Exception {
            var field = DebuggerPlugin.class.getDeclaredField("listener"); field.setAccessible(true);
            return (DebugLifecycleListener) field.get(plugin);
        }

        public void close() throws Exception {
            try {
                DebugLifecycleListener listener = listener();
                plugin.stop();
                if (listener != null) assertStopped(listener);
            } finally {
                compatibility.close(); eventControllers.close(); factories.close(); runtimes.close();
            }
        }
    }
}
