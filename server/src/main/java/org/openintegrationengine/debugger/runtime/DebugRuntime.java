package org.openintegrationengine.debugger.runtime;

import org.mozilla.javascript.*;
import org.mozilla.javascript.Callable;
import org.mozilla.javascript.debug.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/** Headless, fail-open Rhino debugger. Every operation touching live scopes runs on the script worker. */
public final class DebugRuntime implements Debugger {
    private static final DebugRuntime INSTANCE = new DebugRuntime();
    private static final int MAX_SESSIONS = 32, MAX_PAUSES = 4, MAX_COMMANDS = 256;
    private static final long ARM_REQUEST_WINDOW_MILLIS = 30_000;
    private final SourceRegistry registry = new SourceRegistry();
    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final Object creationLock = new Object();
    // create() is serialized; only publication and release notifications share this short lock.
    private Creation creating;
    private final Map<ContextFactory, ContextFactory.Listener> factories = Collections.synchronizedMap(new WeakHashMap<>());
    private final AtomicInteger pausedCount = new AtomicInteger();
    private volatile Predicate<ContextFactory> attachmentGuard = factory -> true;
    private volatile boolean enabled, evaluationEnabled;
    private volatile boolean debuggerConflict;
    private volatile String lastFailure;
    private ScheduledExecutorService watchdog;

    public static DebugRuntime getInstance() { return INSTANCE; }

    public DebugRuntime() { }

    /** Proof that this Arm request has no live lease and cannot create one later. */
    public static final class ArmRequestRejected extends RuntimeException {
        private final String requestId;
        ArmRequestRejected(String requestId, RuntimeException cause) {
            super(cause.getMessage(), cause); this.requestId = requestId;
        }
        public String requestId() { return requestId; }
        @Override public synchronized RuntimeException getCause() { return (RuntimeException) super.getCause(); }
    }

    public void setAttachmentGuard(Predicate<ContextFactory> guard) { attachmentGuard = Objects.requireNonNull(guard); }

    public synchronized void configure(boolean enabled, boolean evaluationEnabled) {
        if (enabled && !this.enabled) { debuggerConflict = false; lastFailure = null; }
        this.evaluationEnabled = evaluationEnabled;
        this.enabled = enabled;
        if (enabled && (watchdog == null || watchdog.isShutdown())) {
            watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "oie-debugger-lease"); thread.setDaemon(true); return thread;
            });
            watchdog.scheduleWithFixedDelay(this::expire, 250, 250, TimeUnit.MILLISECONDS);
        }
        if (!enabled) {
            closeAll("Debugger disabled");
            detach();
            if (watchdog != null) watchdog.shutdownNow();
        }
        if (!evaluationEnabled) sessions.values().forEach(s -> { synchronized (s) { if (s.evaluationEnabled) closeLocked(s, "Evaluation permission disabled"); } });
    }

    public void attach(ContextFactory factory) {
        Objects.requireNonNull(factory);
        try { if (!enabled || !attachmentGuard.test(factory)) return; }
        catch (RuntimeException failure) { failed(failure); return; }
        synchronized (factories) {
            if (factories.containsKey(factory)) return;
            ContextFactory.Listener listener = new ContextFactory.Listener() {
                @Override public void contextCreated(Context context) {
                    try {
                        if (!enabled || !attachmentGuard.test(context.getFactory())) return;
                        if (context.getDebugger() != null && context.getDebugger() != DebugRuntime.this) {
                            debuggerConflict = true;
                            lastFailure = "Another Rhino debugger owns this context";
                            closeAll(lastFailure);
                            return;
                        }
                        context.setDebugger(DebugRuntime.this, new Execution());
                        context.setGeneratingDebug(true);
                    } catch (RuntimeException failure) {
                        failed(failure);
                    }
                }
                @Override public void contextReleased(Context context) {
                    if (context.getDebuggerContextData() instanceof Execution execution)
                        execution.frames.forEach(frame -> release(frame.session, execution, "Execution ended"));
                }
            };
            try { factory.addListener(listener); }
            catch (RuntimeException failure) { failed(failure); return; }
            factories.put(factory, listener);
        }
    }

    /** Detaches only this runtime's listeners; never overwrites a foreign debugger. */
    public void shutdown() {
        configure(false, false);
    }

    private void detach() {
        synchronized (factories) {
            factories.forEach((factory, listener) -> { try { factory.removeListener(listener); } catch (RuntimeException ignored) { } });
            factories.clear();
        }
        registry.clear();
    }

    public List<Map<String, Object>> sources(String channelId) { return registry.list(channelId); }
    public Map<String, Object> source(String id) { return registry.detail(id); }
    /** Exact captured sources may be read for this owner's live pause, including Rhino-generated scripts. */
    public boolean isPausedSource(String owner, String sessionId, String tabId, String sourceId) {
        requireText(tabId, "tabId", 128);
        Session session = owned(owner, sessionId, tabId);
        synchronized (session) {
            expireLocked(session);
            return !session.closed && session.paused
                    && session.frames.stream().anyMatch(frame -> sourceId.equals(frame.get("sourceId")));
        }
    }
    public boolean isSourceScript(String sourceId, DebuggableScript script) {
        SourceRegistry.Source source = registry.compiled(script);
        return source != null && source.id.equals(sourceId);
    }

    public Map<String, Object> create(String owner, String channelId, Map<String, Object> options) {
        return create(owner, channelId, options, () -> { }, () -> { });
    }

    /** A repeated Arm request recovers the original session without renewing or reopening it. */
    public synchronized Map<String, Object> create(String owner, String channelId, Map<String, Object> options,
            Runnable authorize, Runnable validateFreshSource) {
        requireText(owner, "owner", 512); requireText(channelId, "channelId", 128);
        String tabId = requiredString(options, "tabId"); requireText(tabId, "tabId", 128);
        String requestId = creationRequestId(options);
        long requestExpiresAt = requestId == null ? 0 : creationDeadline(options);
        expire();
        Session previous = null;
        if (requestId != null) {
            for (Session candidate : sessions.values()) {
                if (owner.equals(candidate.owner) && tabId.equals(candidate.tabId) && requestId.equals(candidate.requestId)) {
                    previous = candidate;
                    break;
                }
            }
        }
        Creation creation;
        synchronized (creationLock) { creation = previous == null ? new Creation(channelId) : null; creating = creation; }
        try {
            try { authorize.run(); }
            catch (RuntimeException denied) {
                if (previous != null) {
                    synchronized (previous) { closeLocked(previous, "Arm authorization failed"); }
                    if (channelId.equals(previous.channel) && previous.creationOptions.equals(options))
                        throw new ArmRequestRejected(requestId, denied);
                } else if (requestId != null && requestExpiresAt <= now()) {
                    throw new ArmRequestRejected(requestId, denied);
                }
                throw denied;
            }
            // The opaque server deadline expires before closed receipts can be evicted.
            // An absent expired request is terminal even if the runtime is unavailable.
            if (previous == null && requestId != null && requestExpiresAt <= now())
                throw new ArmRequestRejected(requestId, new IllegalStateException("Arm request expired; start a new Arm request"));
            if (!enabled) throw new IllegalStateException("Debugger is disabled or incompatible");
            if (debuggerConflict) throw new IllegalStateException("Another Rhino debugger owns a context; disable the conflicting debugger and restart the plugin");
            if (previous != null) {
                if (!channelId.equals(previous.channel) || !previous.creationOptions.equals(options))
                    throw new IllegalStateException("Arm request ID was reused with different options");
                return snapshot(previous);
            }
            if (requestId != null && requestExpiresAt > now() + ARM_REQUEST_WINDOW_MILLIS)
                throw new IllegalStateException("Arm request deadline is invalid; refresh capabilities");
            if (sessions.values().stream().filter(s -> !s.closed).count() >= MAX_SESSIONS) throw new IllegalStateException("Debug session limit reached");
            if (sessions.size() >= 128) throw new IllegalStateException("Recent session retention limit reached; retry after one minute");
            Session session = new Session(owner, channelId, options, creation.startedAtNanos);
            if (channelId.equals("global") && !bool(options, "includeGlobal", false)) throw new IllegalArgumentException("Global scripts require explicit includeGlobal opt-in");
            if (session.evaluationEnabled && !evaluationEnabled) throw new SecurityException("Evaluation is disabled by server policy");
            if (!session.watches.isEmpty() && !session.evaluationEnabled) throw new SecurityException("Watches require elevated evaluation permission");
            validateFreshSource.run();
            if (session.sourceId != null) {
                SourceRegistry.Source source = registry.require(session.sourceId);
                synchronized (registry) {
                    if (!source.channels.contains(channelId)) throw new IllegalArgumentException("Source has not been observed in this channel");
                }
            }
            // One tab controls a target per owner; different message filters may coexist without sharing worker ownership.
            synchronized (creationLock) {
                if (creation.closeReason != null) synchronized (session) { closeLocked(session, creation.closeReason); }
                sessions.put(session.id, session);
            }
            return snapshot(session);
        } finally { synchronized (creationLock) { creating = null; } }
    }

    public Map<String, Object> session(String owner, String id) { return snapshot(owned(owner, id, null)); }
    public Map<String, Object> session(String owner, String id, String tabId) { return snapshot(owned(owner, id, tabId)); }
    public Map<String, Object> heartbeat(String owner, String id) { return heartbeat(owner, id, null); }
    public Map<String, Object> heartbeat(String owner, String id, String tabId) {
        Session session = owned(owner, id, tabId);
        synchronized (session) {
            expireLocked(session);
            if (session.closed) return snapshot(session);
            session.leaseDeadline = now() + session.leaseMillis;
            return snapshot(session);
        }
    }
    public Map<String, Object> close(String owner, String id) { return close(owner, id, null); }
    public Map<String, Object> close(String owner, String id, String tabId) {
        Session session = owned(owner, id, tabId);
        synchronized (session) { closeLocked(session, "Closed by owner"); return snapshot(session); }
    }

    public Map<String, Object> breakpoints(String owner, String id, Map<String, Object> request) {
        return breakpoints(owner, id, request, () -> { });
    }

    public Map<String, Object> breakpoints(String owner, String id, Map<String, Object> request, Runnable validateFreshSource) {
        Map<String, Object> command = new LinkedHashMap<>(request);
        command.put("action", "setBreakpoints");
        return command(owner, id, command, validateFreshSource);
    }

    /** Called under the session lock through the same receipt path as all other control mutations. */
    private Map<String, Object> setBreakpoints(Session session, Map<String, Object> request) {
        SourceRegistry.Source source = registry.require(requiredString(request, "sourceId"));
        synchronized (registry) {
            if (!source.channels.contains(session.channel)) throw new IllegalArgumentException("Source does not belong to this channel");
        }
        if (!source.hash.equals(requiredString(request, "sourceHash")) || source.generation != number(request, "sourceGeneration", -1))
            throw new IllegalStateException("Source generation changed");
        Object raw = request.get("lines");
        if (!(raw instanceof List<?> lines) || lines.size() > 256) throw new IllegalArgumentException("At most 256 breakpoints are permitted");
        Map<Integer, Breakpoint> updated = new LinkedHashMap<>();
        for (Object value : lines) {
            Map<?, ?> entry = value instanceof Map<?, ?> map ? map : Map.of("line", value);
            Object lineValue = entry.get("line");
            if (!(lineValue instanceof Number)) throw new IllegalArgumentException("Breakpoint line must be a number");
            int line = ((Number) lineValue).intValue();
            if (!source.lines.contains(line)) throw new IllegalArgumentException("Line " + line + " has no executable code");
            String condition = entry.get("condition") instanceof String text && !text.isBlank() ? boundedExpression(text) : null;
            if (condition != null && (!session.evaluationEnabled || !evaluationEnabled)) throw new SecurityException("Conditional breakpoints require elevated evaluation permission");
            long hitCount = entry.get("hitCount") instanceof Number count ? count.longValue() : 1;
            if (hitCount < 1 || hitCount > 1_000_000) throw new IllegalArgumentException("hitCount must be 1–1000000");
            updated.put(line, new Breakpoint(condition, hitCount, !Boolean.FALSE.equals(entry.get("enabled"))));
        }
        synchronized (session) {
            requireOpen(session);
            if (!source.valid || source.invalidChannels.contains(session.channel)) throw new IllegalStateException("Source generation changed");
            if (session.breakpoints.size() >= 32 && !session.breakpoints.containsKey(source.id)) throw new IllegalStateException("Breakpoint source limit reached");
            session.breakpoints.put(source.id, updated);
            return Map.of("sourceId", source.id, "count", updated.size());
        }
    }

    public Map<String, Object> command(String owner, String id, Map<String, Object> request) {
        return command(owner, id, request, () -> { });
    }

    /** Validate mutable source provenance only for a fresh command, atomically with the pause state. */
    public Map<String, Object> command(String owner, String id, Map<String, Object> request, Runnable validateFreshSource) {
        Session session = owned(owner, id, requiredString(request, "tabId"));
        String commandId = requiredString(request, "commandId"); requireText(commandId, "commandId", 128);
        String action = requiredString(request, "action");
        synchronized (session) {
            Pending previous = session.commands.get(commandId);
            if (previous != null) {
                if (!previous.request.equals(request)) throw new IllegalArgumentException("Idempotency key was reused for a different command");
                return previous.dto();
            }
            requireOpen(session);
            if (session.commands.size() >= MAX_COMMANDS) throw new IllegalStateException("Command limit reached; create a new session");
            if (!Set.of("continue", "pause", "stepInto", "stepOver", "stepOut", "inspect", "evaluate", "setValue", "setWatches", "setBreakpoints").contains(action))
                throw new IllegalArgumentException("Unknown debugger action");
            if (Set.of("evaluate", "setValue", "setWatches").contains(action) && (!evaluationEnabled || !session.evaluationEnabled))
                throw new SecurityException("Evaluation is disabled for this session");
            long expected = number(request, "expectedPauseGeneration", -1);
            if (expected != session.pauseGeneration) throw new IllegalStateException("Stale pause generation; refresh session state");
            Pending pending = new Pending(commandId, request);
            if (action.equals("setBreakpoints")) validateFreshSource.run();
            if (action.equals("pause")) {
                session.pauseRequested = true; pending.result = Map.of("pauseRequested", true); pending.completed = true;
            } else if (action.equals("setWatches")) {
                session.watches = expressions(request.get("expressions")); pending.result = Map.of("count", session.watches.size()); pending.completed = true;
            } else if (action.equals("setBreakpoints")) {
                pending.result = setBreakpoints(session, request); pending.completed = true;
            } else {
                if (!session.paused) throw new IllegalStateException("Execution is not paused");
                if (session.queue.size() >= 16) throw new IllegalStateException("Command queue is full");
                session.queue.add(pending);
            }
            session.commands.put(commandId, pending);
            session.notifyAll();
            return pending.dto();
        }
    }

    public void invalidate(String channelId, String reason) {
        registry.invalidate(channelId);
        releaseChannel(channelId, reason);
    }

    /** Close existing sessions so neither message nor identity-free work can pause a channel's drain again. */
    public void releaseChannel(String channelId, String reason) {
        releaseChannel(channelId, reason, System.nanoTime());
    }

    /** Delayed engine notifications must not close a session armed after that state change. */
    public void releaseChannel(String channelId, String reason, long eventNanos) {
        List<Session> released;
        synchronized (creationLock) {
            if (creating != null && creating.channel.equals(channelId) && creating.startedAtNanos <= eventNanos)
                creating.closeReason = reason;
            released = sessions.values().stream().filter(s -> s.channel.equals(channelId) && s.createdAtNanos <= eventNanos).toList();
        }
        released.forEach(s -> {
            synchronized (s) {
                closeLocked(s, reason);
            }
        });
    }

    public void closeAll(String reason) {
        List<Session> released;
        synchronized (creationLock) {
            if (creating != null) creating.closeReason = reason;
            released = List.copyOf(sessions.values());
        }
        released.forEach(s -> { synchronized (s) { closeLocked(s, reason); } });
    }

    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled); result.put("evaluationEnabled", evaluationEnabled);
        result.put("debuggerConflict", debuggerConflict);
        result.put("armRequestExpiresAt", now() + ARM_REQUEST_WINDOW_MILLIS);
        result.put("maxSessions", MAX_SESSIONS); result.put("maxPaused", MAX_PAUSES); result.put("paused", pausedCount.get());
        result.put("attachedFactories", factories.size()); result.put("activeSessions", sessions.values().stream().filter(s -> !s.closed).count());
        result.put("evaluationBudget", "Best effort: 10000 Rhino callbacks or 1000ms checked at callbacks; tight JavaScript loops without callbacks and Java/native calls cannot be forcibly cancelled");
        if (lastFailure != null) result.put("lastFailure", lastFailure);
        return result;
    }

    @Override public void handleCompilationDone(Context context, DebuggableScript script, String source) {
        if (enabled) try {
            Set<String> invalidated = registry.register(context.getFactory(), script, source);
            if (!invalidated.isEmpty()) sessions.values().forEach(session -> {
                synchronized (session) {
                    if (invalidated.contains(session.sourceId) || session.breakpoints.keySet().stream().anyMatch(invalidated::contains)
                            || session.frames.stream().anyMatch(frame -> invalidated.contains(frame.get("sourceId"))))
                        closeLocked(session, "Compiled source changed or source retention limit reached");
                }
            });
        } catch (RuntimeException failure) { failed(failure); }
    }

    @Override public DebugFrame getFrame(Context context, DebuggableScript script) {
        if (!enabled) return null;
        SourceRegistry.Source source = registry.compiled(script);
        if (source == null) return null;
        Object data = context.getDebuggerContextData();
        if (!(data instanceof Execution execution)) return null;
        return new Frame(execution, script, source);
    }

    private final class Frame implements DebugFrame {
        final Execution execution;
        final DebuggableScript script;
        final SourceRegistry.Source source;
        Scriptable scope, thisObject;
        Identity identity;
        Session session;
        int line;
        boolean anchor;

        Frame(Execution execution, DebuggableScript script, SourceRegistry.Source source) {
            this.execution = execution; this.script = script; this.source = source;
        }

        @Override public void onEnter(Context context, Scriptable activation, Scriptable thisObject, Object[] args) {
            try {
                this.scope = activation; this.thisObject = thisObject;
                identity = identify(activation);
                execution.frames.add(this);
                registry.observe(source, identity.channel);
                if (execution.frames.size() > 1) {
                    Frame parent = execution.frames.get(execution.frames.size() - 2);
                    if (parent.session != null && parent.identity.sameTarget(identity)) session = parent.session;
                }
                if (session == null) {
                    for (Session candidate : sessions.values()) {
                        synchronized (candidate) {
                            expireLocked(candidate);
                            if (!candidate.closed && candidate.execution == null && matches(candidate, this)) {
                                candidate.execution = execution; candidate.identity = identity; candidate.executionId = UUID.randomUUID().toString();
                                candidate.entryPending = candidate.breakOnEntry;
                                session = candidate; anchor = true; break;
                            }
                        }
                    }
                }
            } catch (RuntimeException failure) { failed(failure); }
        }

        @Override public void onLineChange(Context context, int lineNumber) {
            line = lineNumber;
            execution.lastException = null;
            if (session == null) return;
            try {
                String reason = null;
                Breakpoint breakpoint = null;
                synchronized (session) {
                    expireLocked(session);
                    if (session.closed || !source.valid || !matches(session, this)) return;
                    if (session.pauseRequested) { session.pauseRequested = false; session.entryPending = false; reason = "pause"; }
                    else if (session.entryPending) { session.entryPending = false; reason = "entry"; }
                    else if (session.step != null && (session.step.equals("stepInto") ||
                            session.step.equals("stepOver") && execution.frames.size() <= session.stepDepth ||
                            session.step.equals("stepOut") && execution.frames.size() < session.stepDepth)) reason = "step";
                    Map<Integer, Breakpoint> byLine = session.breakpoints.get(source.id);
                    if (byLine != null) breakpoint = byLine.get(lineNumber);
                    if (breakpoint != null && (!breakpoint.enabled || ++breakpoint.hits < breakpoint.hitCount)) breakpoint = null;
                }
                if (reason == null && breakpoint != null) {
                    if (breakpoint.condition == null) reason = "breakpoint";
                    else {
                        try { if (Context.toBoolean(evaluate(context, this, breakpoint.condition))) reason = "breakpoint"; }
                        catch (RuntimeException | LinkageError | EvaluationAbort failure) { synchronized (session) { session.lastError = "Breakpoint condition failed: " + failure.getClass().getSimpleName(); } }
                    }
                }
                if (reason != null) pause(context, this, reason, null);
            } catch (RuntimeException failure) { failOpen(session, failure); }
        }

        @Override public void onExceptionThrown(Context context, Throwable throwable) {
            // MirthContext halts scripts by throwing Error from its instruction observer.
            if (throwable instanceof Error || Thread.currentThread().isInterrupted()) {
                if (session != null) synchronized (session) { closeLocked(session, "Script worker halted or interrupted"); }
                return;
            }
            if (session != null && session.pauseOnExceptions && execution.lastException != throwable) {
                execution.lastException = throwable;
                try { pause(context, this, "exception", throwable.getClass().getName()); }
                catch (RuntimeException failure) { failOpen(session, failure); }
            }
        }
        @Override public void onDebuggerStatement(Context context) {
            if (session != null && session.pauseOnDebugger)
                try { pause(context, this, "debugger", null); } catch (RuntimeException failure) { failOpen(session, failure); }
        }
        @Override public void onExit(Context context, boolean byThrow, Object resultOrException) {
            try {
                if (anchor) release(session, execution, byThrow ? "Execution threw an exception" : "Execution completed");
                execution.frames.remove(this);
            } catch (RuntimeException failure) { failOpen(session, failure); }
        }
    }

    private void pause(Context context, Frame frame, String reason, String exception) {
        Session session = frame.session;
        synchronized (session) {
            expireLocked(session);
            if (!enabled || session.closed || session.paused || !frame.source.valid || !matches(session, frame)) return;
            if (pausedCount.incrementAndGet() > MAX_PAUSES) { pausedCount.decrementAndGet(); session.lastError = "Maximum paused execution count reached"; return; }
            session.paused = true;
            session.pauseGeneration++;
            session.pauseDeadline = now() + session.maxPauseMillis;
            session.reason = reason; session.exception = exception; session.step = null;
        }
        try {
            updateInspection(context, frame, 0);
            while (true) {
                Pending pending;
                synchronized (session) {
                    expireLocked(session);
                    if (session.closed || !frame.source.valid || Thread.currentThread().isInterrupted()) {
                        if (!frame.source.valid) closeLocked(session, "Compiled source changed or source retention limit reached");
                        if (Thread.currentThread().isInterrupted()) closeLocked(session, "Script worker interrupted");
                        break;
                    }
                    pending = session.queue.poll();
                    if (pending == null) {
                        try { session.wait(Math.max(1, Math.min(250, Math.min(session.leaseDeadline, session.pauseDeadline) - now()))); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); closeLocked(session, "Script worker interrupted"); break; }
                        continue;
                    }
                    if (number(pending.request, "expectedPauseGeneration", -1) != session.pauseGeneration) {
                        pending.error = "Stale pause generation"; pending.completed = true; continue;
                    }
                }
                String action = requiredString(pending.request, "action");
                try {
                    if (Set.of("continue", "stepInto", "stepOver", "stepOut").contains(action)) {
                        synchronized (session) {
                            session.step = action.equals("continue") ? null : action;
                            session.stepDepth = frame.execution.frames.size();
                            pending.result = Map.of("resumed", true, "pauseGeneration", session.pauseGeneration); pending.completed = true;
                        }
                        break;
                    }
                    int index = Math.toIntExact(number(pending.request, "frameIndex", 0));
                    Frame selected = selected(frame, index);
                    Object result;
                    if (action.equals("inspect")) { updateInspection(context, frame, index); result = session.inspection; }
                    else if (action.equals("evaluate")) result = new SafeInspector().inspect(evaluate(context, selected, boundedExpression(requiredString(pending.request, "expression"))));
                    else if (action.equals("setValue")) {
                        String name = requiredString(pending.request, "name");
                        if (!name.matches("[A-Za-z_$][A-Za-z0-9_$]{0,127}")) throw new IllegalArgumentException("Value editing requires a local variable name");
                        Object original = SafeInspector.ownData(selected.scope, name);
                        if (original == Scriptable.NOT_FOUND) throw new IllegalArgumentException("Variable is not local to the selected frame");
                        result = new SafeInspector().inspect(evaluate(context, selected, name + " = (" + boundedExpression(requiredString(pending.request, "valueExpression")) + ")"));
                    } else throw new IllegalArgumentException("Unsupported paused command");
                    synchronized (session) { pending.result = result; pending.completed = true; }
                    if (!action.equals("inspect")) updateInspection(context, frame, index);
                } catch (RuntimeException | LinkageError | EvaluationAbort failure) {
                    synchronized (session) { pending.error = boundedError(failure); pending.completed = true; }
                }
            }
        } finally {
            synchronized (session) {
                session.paused = false; session.frames = List.of(); session.inspection = Map.of(); session.watchResults = List.of();
                // Commands arriving behind a resume must not execute in the next pause.
                Pending abandoned;
                while ((abandoned = session.queue.poll()) != null) { abandoned.error = "Pause ended before command executed"; abandoned.completed = true; }
                session.notifyAll();
            }
            pausedCount.decrementAndGet();
        }
    }

    private void updateInspection(Context context, Frame frame, int index) {
        Frame selected = selected(frame, index);
        List<Map<String, Object>> frames = new ArrayList<>();
        List<Frame> stack = visibleFrames(frame);
        for (int i = 0; i < stack.size(); i++) {
            Frame entry = stack.get(i);
            frames.add(Map.of("index", i, "functionName", Objects.toString(entry.script.getFunctionName(), "(script)"), "sourceId", entry.source.id,
                    "sourceHash", entry.source.hash, "sourceGeneration", entry.source.generation, "line", entry.line));
        }
        SafeInspector inspector = new SafeInspector();
        Map<String, Object> inspection = Map.of("frameIndex", index, "locals", inspector.scope(selected.scope),
                "globals", new SafeInspector().scope(ScriptableObject.getTopLevelScope(selected.scope)),
                "this", new SafeInspector().inspect(selected.thisObject));
        List<Map<String, Object>> watches = new ArrayList<>();
        List<String> expressions;
        synchronized (frame.session) { expressions = frame.session.watches; }
        for (String expression : expressions) {
            if (now() > frame.session.pauseDeadline) break;
            try { watches.add(Map.of("expression", expression, "result", new SafeInspector().inspect(evaluate(context, selected, expression)))); }
            catch (RuntimeException | LinkageError | EvaluationAbort failure) { watches.add(Map.of("expression", expression, "error", boundedError(failure))); }
        }
        synchronized (frame.session) { frame.session.frames = List.copyOf(frames); frame.session.inspection = inspection; frame.session.watchResults = List.copyOf(watches); }
    }

    private List<Frame> visibleFrames(Frame current) {
        List<Frame> result = new ArrayList<>();
        List<Frame> stack = current.execution.frames;
        for (int i = stack.size() - 1; i >= 0 && result.size() < 64; i--) {
            Frame frame = stack.get(i);
            if (frame.session == current.session && frame.identity.sameTarget(current.identity)) result.add(frame);
        }
        return result;
    }
    private Frame selected(Frame current, int index) {
        List<Frame> stack = visibleFrames(current);
        if (index < 0 || index >= stack.size()) throw new IllegalArgumentException("Frame is unavailable");
        return stack.get(index);
    }

    private Object evaluate(Context context, Frame frame, String expression) {
        if (!evaluationEnabled || !frame.session.evaluationEnabled || frame.session.closed) throw new SecurityException("Evaluation permission unavailable");
        Debugger previous = context.getDebugger(); Object previousData = context.getDebuggerContextData();
        boolean oldGenerating = context.isGeneratingDebug();
        BudgetDebugger budget = new BudgetDebugger(frame.session);
        try {
            context.setDebugger(budget, null); context.setGeneratingDebug(true);
            // Rhino's own debugger invokes the compiled script as Callable to preserve the paused receiver.
            Callable script = (Callable) context.compileString(expression, "<debugger-evaluation>", 1, null);
            return script.call(context, frame.scope, frame.thisObject, ScriptRuntime.emptyArgs);
        } finally { context.setDebugger(previous, previousData); context.setGeneratingDebug(oldGenerating); }
    }

    private static final class BudgetDebugger implements Debugger, DebugFrame {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        final Session session;
        int callbacks;
        BudgetDebugger(Session session) { this.session = session; }
        void check() { if (++callbacks > 10_000 || System.nanoTime() > deadline || session.closed || Thread.currentThread().isInterrupted()) throw new EvaluationAbort(); }
        @Override public void handleCompilationDone(Context cx, DebuggableScript fn, String source) { check(); }
        @Override public DebugFrame getFrame(Context cx, DebuggableScript fn) { check(); return this; }
        @Override public void onEnter(Context cx, Scriptable scope, Scriptable self, Object[] args) { check(); }
        @Override public void onLineChange(Context cx, int line) { check(); }
        @Override public void onExceptionThrown(Context cx, Throwable ex) { check(); }
        @Override public void onExit(Context cx, boolean byThrow, Object result) { }
        @Override public void onDebuggerStatement(Context cx) { check(); }
    }
    private static final class EvaluationAbort extends Error { private static final long serialVersionUID = 1L; }

    private boolean matches(Session session, Frame frame) {
        Identity identity = frame.identity;
        return !frame.source.invalidChannels.contains(identity.channel) && identity.channel.equals(session.channel)
                && (session.messageId == null || session.messageId.equals(identity.messageId))
                && (session.connectorId == null || session.connectorId.equals(identity.connectorId))
                && (session.sourceId == null || session.sourceId.equals(frame.source.id))
                && (session.scriptKind == null || session.scriptKind.equals(frame.source.kind))
                && (session.includeLifecycle || identity.messageId != null);
    }

    private Identity identify(Scriptable scope) {
        Scriptable top = ScriptableObject.getTopLevelScope(scope);
        Object channelValue = SafeInspector.unwrap(SafeInspector.ownData(top, "channelId"));
        String channel = channelValue instanceof String text && !text.isBlank() ? text : "global";
        String message = null, connector = null;
        Integer sendAttempts = null;
        Object raw = SafeInspector.unwrap(SafeInspector.ownData(top, "connectorMessage"));
        if (!trustedMessage(raw)) raw = SafeInspector.unwrap(SafeInspector.ownData(top, "message"));
        if (trustedMessage(raw)) {
            try {
                Object actualChannel = raw.getClass().getMethod("getChannelId").invoke(raw);
                // Scope and trusted message must agree; inconsistent identities never match a user channel.
                if (actualChannel instanceof String text && !channel.equals(text)) channel = "unverified";
                Object actualMessage = raw.getClass().getMethod("getMessageId").invoke(raw);
                if (actualMessage instanceof Number number) message = Long.toString(number.longValue());
                if (raw.getClass().getName().endsWith("ImmutableConnectorMessage")) {
                    Object actualConnector = raw.getClass().getMethod("getMetaDataId").invoke(raw);
                    if (actualConnector instanceof Number number) connector = Integer.toString(number.intValue());
                    Object attempts = raw.getClass().getMethod("getSendAttempts").invoke(raw);
                    if (attempts instanceof Number number) sendAttempts = number.intValue();
                }
            } catch (ReflectiveOperationException ignored) { channel = "unverified"; }
        }
        return new Identity(channel, message, connector, sendAttempts);
    }

    private static boolean trustedMessage(Object raw) {
        return SafeInspector.engineClass(raw, "com.mirth.connect.userutil.ImmutableConnectorMessage") || SafeInspector.engineClass(raw, "com.mirth.connect.userutil.ImmutableMessage");
    }

    private Session owned(String owner, String id, String tab) {
        Session session = sessions.get(id);
        if (session == null || !session.owner.equals(owner) || tab != null && !session.tabId.equals(tab)) throw new SecurityException("Debug session is unavailable to this owner/tab");
        synchronized (session) { expireLocked(session); }
        return session;
    }
    private void requireOpen(Session session) { expireLocked(session); if (session.closed) throw new IllegalStateException("Debug session is closed: " + session.closeReason); }

    private void expire() {
        long now = now();
        sessions.values().forEach(session -> {
            synchronized (session) {
                expireLocked(session);
                if (session.closed && !session.paused && now - session.closedAt > 60_000) sessions.remove(session.id, session);
            }
        });
    }
    private void expireLocked(Session session) {
        long now = now();
        if (!session.closed && now >= session.leaseDeadline) closeLocked(session, "Owner lease expired");
        else if (!session.closed && session.paused && now >= session.pauseDeadline) closeLocked(session, "Maximum pause duration expired");
    }
    private void closeLocked(Session session, String reason) {
        if (session.closed) return;
        session.closed = true; session.closedAt = now(); session.closeReason = reason;
        session.step = null; session.notifyAll();
    }
    private void release(Session session, Execution execution, String reason) {
        if (session == null) return;
        synchronized (session) {
            if (session.execution == execution) { session.execution = null; session.reason = reason; session.step = null; session.notifyAll(); }
        }
    }
    private void failOpen(Session session, RuntimeException failure) { failed(failure); if (session != null) synchronized (session) { session.lastError = boundedError(failure); closeLocked(session, "Debugger failure; worker released"); } }
    private void failed(RuntimeException failure) { lastFailure = failure.getClass().getSimpleName(); }

    private Map<String, Object> snapshot(Session session) {
        synchronized (session) {
            expireLocked(session);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", session.id); result.put("channelId", session.channel);
            result.put("status", session.closed ? "closed" : session.paused ? "paused" : session.execution == null ? "armed" : "running");
            result.put("pauseGeneration", session.pauseGeneration); result.put("reason", session.reason);
            result.put("leaseExpiresAt", System.currentTimeMillis() + Math.max(0, session.leaseDeadline - now())); result.put("maxPauseMillis", session.maxPauseMillis);
            result.put("frames", session.frames); result.put("inspection", session.inspection); result.put("watches", session.watchResults);
            result.put("evaluationEnabled", session.evaluationEnabled && evaluationEnabled);
            result.put("includeLifecycle", session.includeLifecycle);
            if (session.executionId != null) result.put("executionId", session.executionId);
            if (session.identity != null) { result.put("messageId", session.identity.messageId); result.put("connectorId", session.identity.connectorId); result.put("sendAttempts", session.identity.sendAttempts); }
            if (session.exception != null) result.put("exception", session.exception);
            if (session.closeReason != null) result.put("closeReason", session.closeReason);
            if (session.lastError != null) result.put("lastError", session.lastError);
            return result;
        }
    }

    private static final class Execution { final List<Frame> frames = new ArrayList<>(); Throwable lastException; }
    private record Identity(String channel, String messageId, String connectorId, Integer sendAttempts) {
        boolean sameTarget(Identity other) { return Objects.equals(channel, other.channel) && Objects.equals(messageId, other.messageId) && Objects.equals(connectorId, other.connectorId); }
    }
    private static final class Breakpoint {
        final String condition; final long hitCount; final boolean enabled; long hits;
        Breakpoint(String condition, long hitCount, boolean enabled) { this.condition = condition; this.hitCount = hitCount; this.enabled = enabled; }
    }
    private static final class Pending {
        final String id; final Map<String, Object> request; boolean completed; Object result; String error;
        Pending(String id, Map<String, Object> request) { this.id = id; this.request = Collections.unmodifiableMap(new LinkedHashMap<>(request)); }
        Map<String, Object> dto() {
            Map<String, Object> dto = new LinkedHashMap<>(); dto.put("commandId", id); dto.put("status", completed ? "completed" : "pending");
            if (result != null) dto.put("result", result); if (error != null) dto.put("error", error); return dto;
        }
    }
    private static final class Creation {
        final String channel;
        final long startedAtNanos = System.nanoTime();
        String closeReason;
        Creation(String channel) { this.channel = channel; }
    }
    private static final class Session {
        final long createdAtNanos;
        final String id = UUID.randomUUID().toString(), owner, tabId, channel, messageId, connectorId, sourceId, scriptKind;
        final String requestId;
        final Map<String, Object> creationOptions;
        final boolean evaluationEnabled, includeLifecycle, pauseOnExceptions, pauseOnDebugger, breakOnEntry;
        final long leaseMillis, maxPauseMillis;
        final Map<String, Map<Integer, Breakpoint>> breakpoints = new LinkedHashMap<>();
        final Map<String, Pending> commands = new LinkedHashMap<>();
        final Queue<Pending> queue = new ArrayDeque<>();
        List<String> watches;
        volatile boolean closed;
        boolean paused, entryPending, pauseRequested;
        long leaseDeadline, pauseDeadline, pauseGeneration, closedAt;
        int stepDepth;
        String step, reason = "Waiting for the next matching execution", closeReason, lastError, exception, executionId;
        Execution execution;
        Identity identity;
        List<Map<String, Object>> frames = List.of(), watchResults = List.of();
        Map<String, Object> inspection = Map.of();
        Session(String owner, String channel, Map<String, Object> options, long createdAtNanos) {
            this.createdAtNanos = createdAtNanos;
            this.owner = owner; this.channel = channel; tabId = requiredString(options, "tabId"); requireText(tabId, "tabId", 128);
            requestId = creationRequestId(options);
            creationOptions = requestId == null ? Map.of() : immutableOptions(options);
            messageId = optionalString(options, "messageId"); connectorId = optionalString(options, "connectorId");
            sourceId = optionalString(options, "sourceId"); scriptKind = optionalString(options, "scriptKind");
            evaluationEnabled = bool(options, "evaluationEnabled", false);
            includeLifecycle = bool(options, "includeLifecycle", false) || channel.equals("global");
            pauseOnExceptions = bool(options, "pauseOnExceptions", false); pauseOnDebugger = bool(options, "pauseOnDebugger", true);
            breakOnEntry = bool(options, "breakOnEntry", true); watches = expressions(options.get("watches"));
            leaseMillis = Math.max(2_000, Math.min(60_000, number(options, "leaseMillis", 15_000)));
            maxPauseMillis = Math.max(1_000, Math.min(120_000, number(options, "maxPauseMillis", 60_000)));
            leaseDeadline = now() + leaseMillis;
        }
    }

    private static String creationRequestId(Map<String, Object> options) {
        if (!options.containsKey("requestId")) return null;
        if (!(options.get("requestId") instanceof String value)) throw new IllegalArgumentException("requestId must be text");
        requireText(value, "requestId", 128);
        return value;
    }

    private static long creationDeadline(Map<String, Object> options) {
        Object value = options.get("requestExpiresAt");
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long))
            throw new IllegalArgumentException("requestExpiresAt must be the integer deadline from capabilities");
        return ((Number) value).longValue();
    }

    private static Map<String, Object> immutableOptions(Map<String, Object> options) {
        Map<String, Object> copy = new LinkedHashMap<>();
        options.forEach((key, value) -> copy.put(key, immutableOption(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableOption(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(key, immutableOption(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) return Collections.unmodifiableList(list.stream().map(DebugRuntime::immutableOption).toList());
        return value;
    }

    private static List<String> expressions(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list) || list.size() > 16) throw new IllegalArgumentException("At most 16 watch expressions are permitted");
        List<String> result = new ArrayList<>();
        for (Object value : list) { if (!(value instanceof String text)) throw new IllegalArgumentException("Watch expressions must be text"); result.add(boundedExpression(text)); }
        return List.copyOf(result);
    }
    private static long now() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()); }
    private static String boundedExpression(String expression) { requireText(expression, "expression", 4096); return expression; }
    private static void requireText(String value, String name, int maximum) { if (value == null || value.isBlank() || value.length() > maximum) throw new IllegalArgumentException(name + " must contain 1–" + maximum + " characters"); }
    private static String requiredString(Map<String, Object> map, String key) { String value = optionalString(map, key); if (value == null) throw new IllegalArgumentException(key + " is required"); return value; }
    private static String optionalString(Map<String, Object> map, String key) { Object value = map.get(key); return value instanceof String text && !text.isBlank() ? text : value instanceof Number number ? number.toString() : null; }
    private static long number(Map<String, Object> map, String key, long fallback) { Object value = map.get(key); return value instanceof Number number ? number.longValue() : fallback; }
    private static boolean bool(Map<String, Object> map, String key, boolean fallback) { Object value = map.get(key); return value instanceof Boolean bool ? bool : fallback; }
    private static String boundedError(Throwable failure) {
        if (failure instanceof EvaluationAbort) return "Evaluation callback/time budget exceeded or session ended; tight JavaScript loops and Java calls cannot be forcibly cancelled";
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message.substring(0, Math.min(512, message.length())));
    }
}
