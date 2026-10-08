package org.openintegrationengine.debugger.engine;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.mirth.connect.donkey.model.event.DeployedStateEventType;
import com.mirth.connect.donkey.model.event.Event;
import com.mirth.connect.donkey.server.event.DeployedStateEvent;
import com.mirth.connect.donkey.server.event.EventType;
import com.mirth.connect.server.event.EventListener;
import org.openintegrationengine.debugger.runtime.DebugRuntime;

/** Early release notifications supplement, but never replace, the runtime's pause watchdog. */
public final class DebugLifecycleListener extends EventListener {
    private final Consumer<DeployedStateEvent> release;
    private boolean initialized;
    private final AtomicLong dropped = new AtomicLong();

    public DebugLifecycleListener() {
        this(event -> DebugRuntime.getInstance().releaseChannel(event.getChannelId(),
                "Channel state changed to " + event.getState().name().toLowerCase() + '.', event.getNanoTime()));
    }

    DebugLifecycleListener(Consumer<DeployedStateEvent> release) {
        this.release = release;
        synchronized (this) {
            queue = new LinkedBlockingQueue<Event>(256) {
                @Override public void put(Event event) {
                    // EventController invokes put on producer threads; never wait for capacity.
                    if (!offer(event)) dropped.incrementAndGet();
                }
            };
            initialized = true;
            notifyAll();
        }
    }

    @Override public void run() {
        // The engine superclass starts this worker before the subclass constructor executes.
        synchronized (this) {
            while (!initialized) {
                try { wait(); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        super.run();
    }

    @Override public Set<EventType> getEventTypes() { return Collections.singleton(EventType.DEPLOY_STATE); }

    @Override protected void processEvent(Event event) {
        if (!(event instanceof DeployedStateEvent)) return;
        DeployedStateEvent stateEvent = (DeployedStateEvent) event;
        DeployedStateEventType state = stateEvent.getState();
        if (state == DeployedStateEventType.STOPPING || state == DeployedStateEventType.PAUSING
                || state == DeployedStateEventType.UNDEPLOYED || state == DeployedStateEventType.DEPLOYED) {
            release.accept(stateEvent);
        }
    }

    public long getDroppedEvents() { return dropped.get(); }

    @Override protected void onShutdown() { queue.clear(); }
}
