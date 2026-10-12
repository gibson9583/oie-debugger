package org.openintegrationengine.debugger.runtime;

import com.mirth.connect.userutil.ImmutableConnectorMessage;
import org.junit.jupiter.api.*;
import org.mozilla.javascript.*;
import org.mozilla.javascript.debug.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DebugRuntimeTest {
    private DebugRuntime runtime;
    private ContextFactory factory;
    private ExecutorService workers;
    private static final String OWNER = "authenticated-owner", TAB = "tab-token", CHANNEL = "channel-a";

    @BeforeEach void setup() {
        runtime = new DebugRuntime(); runtime.configure(true, true);
        factory = new ContextFactory() {
            @Override protected Context makeContext() { Context context = super.makeContext(); context.setOptimizationLevel(-1); return context; }
        };
        runtime.attach(factory); workers = Executors.newCachedThreadPool();
    }
    @AfterEach void cleanup() throws Exception {
        runtime.shutdown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS), "Owned script workers must exit");
    }

    @Test void entryInspectStepAndContinuePreserveScriptResultAndExactSource() throws Exception {
        String text = "var a = 1;\na = a + 2;\na;";
        Script script = compile(text, "transformer");
        String id = create(Map.of());
        Future<Object> result = run(script, CHANNEL);
        Map<String, Object> paused = awaitPause(id, 1);
        Map<String, Object> frame = frames(paused).get(0);
        Map<String, Object> source = runtime.source((String) frame.get("sourceId"));
        assertEquals(text, source.get("text")); assertEquals(CHANNEL, ((List<?>) source.get("channels")).get(0));
        assertEquals(64, source.get("hash").toString().length());
        receipt(id, command(paused, "inspect", Map.of()));
        receipt(id, command(paused, "stepOver", Map.of()));
        Map<String, Object> next = awaitPause(id, 2);
        assertTrue(((Number) frames(next).get(0).get("line")).intValue() > ((Number) frame.get("line")).intValue());
        receipt(id, command(next, "continue", Map.of()));
        assertEquals(3.0, result.get(2, TimeUnit.SECONDS));
        assertEquals("armed", runtime.session(OWNER, id, TAB).get("status"));
    }

    @Test void eachMatchingMessageBreaksOnEntryWithoutBreakingAgainInNestedFunctions() throws Exception {
        Script script = compile("function child(){\nreturn 7;\n}\nfunction parent(){\nreturn child();\n}\nparent();", "transformer");
        String id = create(Map.of("includeLifecycle", false, "connectorId", "0"));
        Object previousExecution = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            long messageId = 40L + attempt;
            Future<Object> result = run(script, CHANNEL, message(CHANNEL, messageId, 0));
            Map<String,Object> paused = awaitPause(id, attempt);
            assertEquals("entry", paused.get("reason")); assertEquals(Long.toString(messageId), paused.get("messageId"));
            assertNotEquals(previousExecution, paused.get("executionId")); previousExecution = paused.get("executionId");
            receipt(id, command(paused, "continue", Map.of()));
            assertEquals(7.0, result.get(2, TimeUnit.SECONDS), "Continue must pass nested function entries without another stop");
            assertEquals("armed", runtime.session(OWNER, id, TAB).get("status"));
        }
    }

    @Test void repeatedEntryStopsRetainMessageAndConnectorFilters() throws Exception {
        Script script = compile("var n=1;\nn+1;", "transformer");
        String id = create(Map.of("includeLifecycle", false, "messageId", "42", "connectorId", "3"));
        for (int attempt = 1; attempt <= 2; attempt++) {
            assertEquals(2.0, run(script, CHANNEL, message(CHANNEL, 41 + attempt * 2L, 3)).get(1, TimeUnit.SECONDS));
            assertEquals(2.0, run(script, CHANNEL, message(CHANNEL, 42, 4)).get(1, TimeUnit.SECONDS));
            assertEquals((long) attempt - 1, runtime.session(OWNER, id, TAB).get("pauseGeneration"));
            Future<Object> result = run(script, CHANNEL, message(CHANNEL, 42, 3));
            Map<String,Object> paused = awaitPause(id, attempt);
            assertEquals("entry", paused.get("reason")); assertEquals("42", paused.get("messageId")); assertEquals("3", paused.get("connectorId"));
            receipt(id, command(paused, "continue", Map.of())); assertEquals(2.0, result.get(2, TimeUnit.SECONDS));
        }
    }

    @Test void disabledBreakOnEntryStaysDisabledAcrossMessages() throws Exception {
        Script script = compile("var n=1;\nn+1;", "transformer");
        String id = create(Map.of("includeLifecycle", false, "breakOnEntry", false));
        for (long messageId : List.of(41L, 42L)) assertEquals(2.0, run(script, CHANNEL, message(CHANNEL, messageId, 0)).get(1, TimeUnit.SECONDS));
        Map<String,Object> snapshot = runtime.session(OWNER, id, TAB);
        assertEquals("armed", snapshot.get("status")); assertEquals(0L, snapshot.get("pauseGeneration"));
    }

    @Test void persistentBreakpointRemainsActiveAfterEntryStopsOnSuccessiveMessages() throws Exception {
        Script script = compile("var n=1;\nn++;\nn;", "transformer");
        run(script, CHANNEL).get(1, TimeUnit.SECONDS);
        Map<String,Object> source = runtime.sources(CHANNEL).get(0);
        String id = create(Map.of("includeLifecycle", false, "connectorId", "0"));
        setBreakpoints(id, Map.of("tabId", TAB, "sourceId", source.get("id"), "sourceHash", source.get("hash"),
                "sourceGeneration", source.get("generation"), "lines", List.of(2)));
        for (int attempt = 1; attempt <= 2; attempt++) {
            Future<Object> result = run(script, CHANNEL, message(CHANNEL, 40L + attempt, 0));
            Map<String,Object> entry = awaitPause(id, attempt * 2L - 1); assertEquals("entry", entry.get("reason"));
            receipt(id, command(entry, "continue", Map.of()));
            Map<String,Object> breakpoint = awaitPause(id, attempt * 2L); assertEquals("breakpoint", breakpoint.get("reason"));
            assertEquals(2, frames(breakpoint).get(0).get("line"));
            receipt(id, command(breakpoint, "continue", Map.of())); assertEquals(2.0, result.get(2, TimeUnit.SECONDS));
        }
    }

    @Test void explicitPauseBeforeEntryConsumesThatExecutionsEntryStop() throws Exception {
        Script script = compile("var n=1;\nn+1;", "transformer");
        String id = create(Map.of("includeLifecycle", false));
        receipt(id, command(runtime.session(OWNER, id, TAB), "pause", Map.of()));
        Future<Object> first = run(script, CHANNEL, message(CHANNEL, 41, 0));
        Map<String,Object> requestedPause = awaitPause(id, 1); assertEquals("pause", requestedPause.get("reason"));
        receipt(id, command(requestedPause, "continue", Map.of())); assertEquals(2.0, first.get(2, TimeUnit.SECONDS));
        Future<Object> second = run(script, CHANNEL, message(CHANNEL, 42, 0));
        Map<String,Object> entry = awaitPause(id, 2); assertEquals("entry", entry.get("reason"));
        receipt(id, command(entry, "continue", Map.of())); assertEquals(2.0, second.get(2, TimeUnit.SECONDS));
    }

    @Test void exceptionCompletionReleasesOwnershipAndRearmsEntryForNextMessage() throws Exception {
        Script script = compile("throw 'script failed';", "transformer");
        String id = create(Map.of("includeLifecycle", false));
        for (int attempt = 1; attempt <= 2; attempt++) {
            Future<Object> result = run(script, CHANNEL, message(CHANNEL, 40L + attempt, 0));
            Map<String,Object> entry = awaitPause(id, attempt); assertEquals("entry", entry.get("reason"));
            receipt(id, command(entry, "continue", Map.of()));
            ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertInstanceOf(JavaScriptException.class, failure.getCause()); assertEquals("armed", runtime.session(OWNER, id, TAB).get("status"));
        }
    }

    @Test void concurrentUnownedMessageDoesNotStealOrConsumeNextEntryStop() throws Exception {
        Script script = compile("var n=1;\nn+1;", "transformer");
        String id = create(Map.of("includeLifecycle", false));
        Future<Object> first = run(script, CHANNEL, message(CHANNEL, 41, 0));
        Map<String,Object> entry = awaitPause(id, 1);
        assertEquals(2.0, run(script, CHANNEL, message(CHANNEL, 42, 0)).get(1, TimeUnit.SECONDS));
        Map<String,Object> stillOwned = runtime.session(OWNER, id, TAB);
        assertEquals("41", stillOwned.get("messageId")); assertEquals(entry.get("executionId"), stillOwned.get("executionId"));
        assertEquals(1L, stillOwned.get("pauseGeneration"));
        receipt(id, command(entry, "continue", Map.of())); assertEquals(2.0, first.get(2, TimeUnit.SECONDS));
        Future<Object> third = run(script, CHANNEL, message(CHANNEL, 43, 0));
        Map<String,Object> next = awaitPause(id, 2); assertEquals("43", next.get("messageId")); assertEquals("entry", next.get("reason"));
        receipt(id, command(next, "continue", Map.of())); assertEquals(2.0, third.get(2, TimeUnit.SECONDS));
    }

    @Test void stepIntoAndOutFollowNestedFrames() throws Exception {
        Script script = compile("function plus(n) {\n var local = n + 1;\n return local;\n}\nvar answer = plus(3);\nanswer;", "transformer");
        String id = create(Map.of()); Future<Object> result = run(script, CHANNEL);
        Map<String, Object> pause = awaitPause(id, 1);
        receipt(id, command(pause, "stepInto", Map.of()));
        pause = awaitPause(id, 2);
        // The first entered function body has a two-frame stack.
        for (int i = 0; frames(pause).size() < 2 && i < 6; i++) {
            long generation = ((Number) pause.get("pauseGeneration")).longValue();
            receipt(id, command(pause, "stepInto", Map.of())); pause = awaitPause(id, generation + 1);
        }
        assertEquals("plus", frames(pause).get(0).get("functionName"));
        long generation = ((Number) pause.get("pauseGeneration")).longValue();
        receipt(id, command(pause, "stepOut", Map.of())); pause = awaitPause(id, generation + 1);
        assertEquals(1, frames(pause).size());
        receipt(id, command(pause, "continue", Map.of())); assertEquals(4.0, result.get(2, TimeUnit.SECONDS));
    }

    @Test void duplicateResumeCannotAdvanceNextPauseAndStaleCommandsAreRejected() throws Exception {
        Script script = compile("var a=1;\na++;\na++;\na;", "transformer");
        String id = create(Map.of()); Future<Object> result = run(script, CHANNEL);
        Map<String, Object> first = awaitPause(id, 1);
        Map<String, Object> step = command(first, "stepOver", Map.of());
        receipt(id, step); Map<String, Object> second = awaitPause(id, 2);
        assertEquals("completed", runtime.command(OWNER, id, step).get("status"));
        assertEquals(2L, runtime.session(OWNER, id, TAB).get("pauseGeneration"));
        assertThrows(IllegalStateException.class, () -> runtime.command(OWNER, id, command(first, "continue", Map.of())));
        Map<String, Object> reused = new HashMap<>(step); reused.put("action", "continue");
        assertThrows(IllegalArgumentException.class, () -> runtime.command(OWNER, id, reused));
        receipt(id, command(second, "continue", Map.of())); assertEquals(3.0, result.get(2, TimeUnit.SECONDS));
    }

    @Test void sameFactoryDoesNotPauseUnselectedChannel() throws Exception {
        Script script = compile("var a=1;\na++;\na;", "shared");
        String id = create(Map.of()); Future<Object> selected = run(script, CHANNEL);
        Map<String, Object> paused = awaitPause(id, 1);
        assertEquals(2.0, run(script, "channel-b").get(1, TimeUnit.SECONDS));
        assertFalse(selected.isDone()); receipt(id, command(paused, "continue", Map.of()));
        assertEquals(2.0, selected.get(2, TimeUnit.SECONDS));
        assertEquals(1, runtime.sources("channel-b").size());
    }

    @Test void selectedMessageAndConnectorUseTrustedScopeIdentity() throws Exception {
        Script script = compile("var a=1;\na++;\na;", "same-name");
        String id = create(Map.of("messageId", "42", "connectorId", "3", "includeLifecycle", false));
        assertEquals(2.0, run(script, CHANNEL, message(CHANNEL, 41, 3)).get(1, TimeUnit.SECONDS));
        assertEquals(2.0, run(script, CHANNEL, message(CHANNEL, 42, 4)).get(1, TimeUnit.SECONDS));
        Future<Object> selected = run(script, CHANNEL, message(CHANNEL, 42, 3));
        Map<String, Object> paused = awaitPause(id, 1); assertEquals("42", paused.get("messageId")); assertEquals("3", paused.get("connectorId")); assertEquals(3, paused.get("sendAttempts"));
        receipt(id, command(paused, "continue", Map.of())); assertEquals(2.0, selected.get(2, TimeUnit.SECONDS));
    }

    @Test void spoofedPlainMessageObjectDoesNotEstablishMessageIdentity() throws Exception {
        Script script = compile("var a=1;\na;", "transformer");
        String id = create(Map.of("includeLifecycle", false));
        assertEquals(1.0, run(script, CHANNEL).get(1, TimeUnit.SECONDS));
        assertEquals("armed", runtime.session(OWNER, id, TAB).get("status"));
    }

    @Test void ownerAndTabTokenProtectControls() {
        String id = create(Map.of());
        assertThrows(SecurityException.class, () -> runtime.session("other", id, TAB));
        assertThrows(SecurityException.class, () -> runtime.heartbeat(OWNER, id, "other-tab"));
        assertThrows(SecurityException.class, () -> runtime.close(OWNER, id, "other-tab"));
        assertThrows(IllegalArgumentException.class, () -> runtime.command(OWNER, id, Map.of("action", "pause")));
    }

    @Test void maximumPauseExpiresEvenWhenOwnerHeartbeats() throws Exception {
        String id = create(Map.of("maxPauseMillis", 1000, "leaseMillis", 3000));
        Future<Object> result = run(compile("var n=1;\nn+1;", "transformer"), CHANNEL);
        awaitPause(id, 1); runtime.heartbeat(OWNER, id, TAB);
        assertEquals(2.0, result.get(3, TimeUnit.SECONDS));
        assertEquals("Maximum pause duration expired", runtime.session(OWNER, id, TAB).get("closeReason"));
    }

    @Test void leaseExpiryReleasesAndDisarmsSession() throws Exception {
        String id = create(Map.of("leaseMillis", 2000));
        Script script = compile("var n=1;\nn+1;", "transformer");
        Future<Object> result = run(script, CHANNEL); awaitPause(id, 1);
        assertEquals(2.0, result.get(4, TimeUnit.SECONDS));
        assertEquals("Owner lease expired", runtime.session(OWNER, id, TAB).get("closeReason"));
        assertEquals(2.0, run(script, CHANNEL).get(1, TimeUnit.SECONDS));
    }

    @Test void interruptedWorkerIsReleasedAndInterruptFlagPreserved() throws Exception {
        String id = create(Map.of()); AtomicReference<Thread> thread = new AtomicReference<>();
        Future<Boolean> done = workers.submit(() -> { thread.set(Thread.currentThread()); execute(compile("var n=1;\nn;", "transformer"), CHANNEL, null); return Thread.currentThread().isInterrupted(); });
        awaitPause(id, 1); thread.get().interrupt();
        assertTrue(done.get(2, TimeUnit.SECONDS)); assertEquals("closed", runtime.session(OWNER, id, TAB).get("status"));
    }

    @Test void globalRequiresExplicitOptInAndDoesNotCatchChannelScript() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> runtime.create(OWNER, "global", Map.of("tabId", TAB)));
        String id = runtime.create(OWNER, "global", Map.of("tabId", TAB, "includeGlobal", true)).get("id").toString();
        Script script = compile("var n=1;\nn;", "global-deploy");
        assertEquals(1.0, run(script, CHANNEL).get(1, TimeUnit.SECONDS));
        Future<Object> result = run(script, null); Map<String, Object> paused = awaitPause(id, 1);
        receipt(id, command(paused, "continue", Map.of())); assertEquals(1.0, result.get(2, TimeUnit.SECONDS));
    }

    @Test void watchesEvaluationAndValueEditingRunOnPausedWorker() throws Exception {
        String id = create(Map.of("evaluationEnabled", true, "watches", List.of("typeof local")));
        Script script = compile("var local=4;\nlocal++;\nlocal;", "transformer");
        Future<Object> result = run(script, CHANNEL); Map<String, Object> first = awaitPause(id, 1);
        receipt(id, command(first, "stepOver", Map.of())); Map<String, Object> second = awaitPause(id, 2);
        assertFalse(((List<?>) second.get("watches")).isEmpty());
        Map<String, Object> edited = receipt(id, command(second, "setValue", Map.of("name", "local", "valueExpression", "40")));
        assertEquals(40.0, ((Map<?, ?>) edited.get("result")).get("value"));
        Map<String, Object> evaluated = receipt(id, command(second, "evaluate", Map.of("expression", "local + 1")));
        assertEquals(41.0, ((Map<?, ?>) evaluated.get("result")).get("value"));
        receipt(id, command(second, "continue", Map.of())); assertEquals(41.0, result.get(2, TimeUnit.SECONDS));
    }

    @Test void hl7TransformerLocalsBelongToInnerFrameAndAppearAfterTheirAssignmentsWithoutEvaluation() throws Exception {
        Script script = compile("function doScript(){\n"
                + "msg = new XML('<HL7Message><PID><PID.5><PID.5.1>doe</PID.5.1></PID.5></PID></HL7Message>');\n"
                + "function doTransform(){\n"
                + "var originalFamilyName = String(msg['PID']['PID.5']['PID.5.1']);\n"
                + "var normalizedFamilyName = originalFamilyName.toUpperCase();\n"
                + "msg['PID']['PID.5']['PID.5.1'] = normalizedFamilyName;\n"
                + "}\ndoTransform();\n}\ndoScript();", "hl7-source-transformer");
        run(script, CHANNEL).get(1, TimeUnit.SECONDS);
        Map<String,Object> source = runtime.sources(CHANNEL).get(0);
        String id = create(Map.of("breakOnEntry", false, "evaluationEnabled", false));
        setBreakpoints(id, Map.of("tabId", TAB, "sourceId", source.get("id"), "sourceHash", source.get("hash"),
                "sourceGeneration", source.get("generation"), "lines", List.of(5)));
        Future<Object> result = run(script, CHANNEL);
        Map<String,Object> beforeAssignment = awaitPause(id, 1);
        assertEquals("doTransform", frames(beforeAssignment).get(0).get("functionName"));
        assertEquals("doScript", frames(beforeAssignment).get(1).get("functionName"));
        assertEquals(false, beforeAssignment.get("evaluationEnabled"));
        Map<?,?> beforeLocals = (Map<?,?>) ((Map<?,?>) ((Map<?,?>) beforeAssignment.get("inspection")).get("locals")).get("properties");
        assertEquals("doe", ((Map<?,?>) beforeLocals.get("originalFamilyName")).get("value"));
        assertTrue(!beforeLocals.containsKey("normalizedFamilyName") || "undefined".equals(((Map<?,?>) beforeLocals.get("normalizedFamilyName")).get("type")));

        receipt(id, command(beforeAssignment, "stepOver", Map.of()));
        Map<String,Object> afterAssignment = awaitPause(id, 2);
        Map<?,?> afterLocals = (Map<?,?>) ((Map<?,?>) ((Map<?,?>) afterAssignment.get("inspection")).get("locals")).get("properties");
        assertEquals("doe", ((Map<?,?>) afterLocals.get("originalFamilyName")).get("value"));
        assertEquals("DOE", ((Map<?,?>) afterLocals.get("normalizedFamilyName")).get("value"));

        Map<String,Object> wrapperReceipt = receipt(id, command(afterAssignment, "inspect", Map.of("frameIndex", 1)));
        Map<?,?> wrapperLocals = (Map<?,?>) ((Map<?,?>) ((Map<?,?>) wrapperReceipt.get("result")).get("locals")).get("properties");
        assertFalse(wrapperLocals.containsKey("originalFamilyName")); assertFalse(wrapperLocals.containsKey("normalizedFamilyName"));
        Map<String,Object> outerReceipt = receipt(id, command(afterAssignment, "inspect", Map.of("frameIndex", 2)));
        Map<?,?> outerLocals = (Map<?,?>) ((Map<?,?>) ((Map<?,?>) outerReceipt.get("result")).get("locals")).get("properties");
        assertTrue(outerLocals.containsKey("msg"));
        assertFalse(outerLocals.containsKey("originalFamilyName")); assertFalse(outerLocals.containsKey("normalizedFamilyName"));
        receipt(id, command(afterAssignment, "continue", Map.of())); result.get(2, TimeUnit.SECONDS);
    }

    @Test void evaluationBudgetAbortsCallbackProducingLoopAndWorkerStillResumes() throws Exception {
        String id = create(Map.of("evaluationEnabled", true));
        Future<Object> result = run(compile("var n=1;\nn;", "transformer"), CHANNEL);
        Map<String, Object> paused = awaitPause(id, 1);
        Map<String, Object> evaluated = receipt(id, command(paused, "evaluate", Map.of("expression", "var counter=0; while(true) {\n counter++;\n}")));
        assertTrue(evaluated.get("error").toString().contains("budget"));
        receipt(id, command(paused, "continue", Map.of())); assertEquals(1.0, result.get(2, TimeUnit.SECONDS));
    }

    @Test void nativeLinkageFailuresBecomeWatchAndCommandErrorsWithoutAbortingTheScript() throws Exception {
        String id = create(Map.of("evaluationEnabled", true, "breakOnEntry", false, "pauseOnExceptions", true,
                "watches", List.of("nativeFailure.fail()", "local")));
        Future<Object> result = runWithNativeFailure(compile("var local=41;\ndebugger;\nlocal+1;", "transformer"),
                new NoClassDefFoundError("missing.OptionalLibrary"));
        Map<String, Object> paused = awaitPause(id, 1);
        List<?> watches = (List<?>) paused.get("watches");
        assertTrue(((Map<?, ?>) watches.get(0)).get("error").toString().contains("NoClassDefFoundError"));
        assertEquals(41.0, ((Map<?, ?>) ((Map<?, ?>) watches.get(1)).get("result")).get("value"));
        for (String action : List.of("evaluate", "setValue")) {
            Map<String, Object> fields = action.equals("evaluate") ? Map.of("expression", "nativeFailure.fail()")
                    : Map.of("name", "local", "valueExpression", "nativeFailure.fail()");
            Map<String, Object> request = command(paused, action, fields);
            Map<String, Object> failed = receipt(id, request);
            assertEquals("completed", failed.get("status"));
            assertTrue(failed.get("error").toString().contains("NoClassDefFoundError"));
            assertEquals(failed, runtime.command(OWNER, id, request), "Retry must return the terminal error receipt");
        }
        Map<String, Object> evaluated = receipt(id, command(paused, "evaluate", Map.of("expression", "local+1")));
        assertEquals(42.0, ((Map<?, ?>) evaluated.get("result")).get("value"));
        receipt(id, command(paused, "continue", Map.of()));
        assertEquals(42.0, result.get(2, TimeUnit.SECONDS));
        assertEquals(1L, runtime.session(OWNER, id, TAB).get("pauseGeneration"));
    }

    @Test void nativeLinkageFailureInConditionDoesNotAbortTheScriptOrDisableLaterBreakpoints() throws Exception {
        Script script = compile("var local=41;\nlocal+1;", "transformer");
        assertEquals(42.0, runWithNativeFailure(script, new NoClassDefFoundError()).get(2, TimeUnit.SECONDS));
        Map<String, Object> source = runtime.sources(CHANNEL).get(0);
        String id = create(Map.of("evaluationEnabled", true, "breakOnEntry", false));
        Map<String, Object> fields = new HashMap<>(Map.of("sourceId", source.get("id"), "sourceHash", source.get("hash"),
                "sourceGeneration", source.get("generation"), "lines", List.of(Map.of("line", 2, "condition", "nativeFailure.fail()"))));
        setBreakpoints(id, fields);
        assertEquals(42.0, runWithNativeFailure(script, new NoClassDefFoundError()).get(2, TimeUnit.SECONDS));
        Map<String, Object> state = runtime.session(OWNER, id, TAB);
        assertEquals("armed", state.get("status")); assertEquals(0L, state.get("pauseGeneration"));
        assertEquals("Breakpoint condition failed: NoClassDefFoundError", state.get("lastError"));
        fields.put("lines", List.of(Map.of("line", 2, "condition", "true")));
        setBreakpoints(id, fields);
        Future<Object> result = runWithNativeFailure(script, new NoClassDefFoundError());
        Map<String, Object> paused = awaitPause(id, 1); assertEquals("breakpoint", paused.get("reason"));
        receipt(id, command(paused, "continue", Map.of())); assertEquals(42.0, result.get(2, TimeUnit.SECONDS));
    }

    @Test void nativeHaltAndFatalErrorsDuringEvaluationStillPropagate() throws Exception {
        for (Error failure : List.of(new Error("engine halted"), new AssertionError("native assertion"),
                new OutOfMemoryError("synthetic fatal error"), new ThreadDeath())) {
            String id = create(Map.of("evaluationEnabled", true, "breakOnEntry", false));
            Future<Object> result = runWithNativeFailure(compile("debugger;\n42;", "transformer"), failure);
            Map<String, Object> paused = awaitPause(id, 1);
            runtime.command(OWNER, id, command(paused, "evaluate", Map.of("expression", "nativeFailure.fail()")));
            ExecutionException thrown = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertSame(failure, thrown.getCause(), "Only recoverable linkage failures should become expression errors");
            runtime.close(OWNER, id, TAB);
        }
    }

    @Test void evaluationAndValueEditingPreservePausedMethodReceiverAndLocals() throws Exception {
        String id = create(Map.of("evaluationEnabled", true, "breakOnEntry", false));
        Script script = compile("var object={value:9,run:function(){\nvar local=2;\ndebugger;\nreturn local + this.value;\n}};\nobject.run();", "transformer");
        Future<Object> result = run(script, CHANNEL); Map<String, Object> paused = awaitPause(id, 1);
        Map<String, Object> evaluated = receipt(id, command(paused, "evaluate", Map.of("expression", "this.value + local")));
        assertEquals(11.0, ((Map<?, ?>) evaluated.get("result")).get("value"));
        receipt(id, command(paused, "setValue", Map.of("name", "local", "valueExpression", "40")));
        receipt(id, command(paused, "continue", Map.of())); assertEquals(49.0, result.get(2, TimeUnit.SECONDS));
    }

    @Test void readOnlySessionRejectsEvaluationWatchesAndMutation() throws Exception {
        String id = create(Map.of()); Future<Object> result = run(compile("var n=1;\nn;", "transformer"), CHANNEL);
        Map<String, Object> paused = awaitPause(id, 1);
        for (String action : List.of("evaluate", "setValue", "setWatches"))
            assertThrows(SecurityException.class, () -> runtime.command(OWNER, id, command(paused, action, Map.of())));
        runtime.close(OWNER, id, TAB); assertEquals(1.0, result.get(2, TimeUnit.SECONDS));
    }

    @Test void caughtExceptionPausesWithoutChangingCatchBehavior() throws Exception {
        String id = create(Map.of("breakOnEntry", false, "pauseOnExceptions", true));
        Future<Object> result = run(compile("var n=1;\ntry { throw 'caught'; } catch (e) { n=7; }\nn;", "transformer"), CHANNEL);
        Map<String, Object> paused = awaitPause(id, 1); assertEquals("exception", paused.get("reason"));
        receipt(id, command(paused, "continue", Map.of())); assertEquals(7.0, result.get(2, TimeUnit.SECONDS));
    }

    @Test void debuggerStatementPauses() throws Exception {
        String id = create(Map.of("breakOnEntry", false));
        Future<Object> result = run(compile("var n=1;\ndebugger;\nn;", "transformer"), CHANNEL);
        Map<String, Object> paused = awaitPause(id, 1); assertEquals("debugger", paused.get("reason"));
        receipt(id, command(paused, "continue", Map.of())); assertEquals(1.0, result.get(2, TimeUnit.SECONDS));
    }

    @Test void breakpointsValidateCompiledGenerationAndHitCount() throws Exception {
        Script script = compile("var n=0;\nfor(var i=0;i<4;i++){\nn++;\n}\nn;", "transformer");
        assertEquals(4.0, run(script, CHANNEL).get(1, TimeUnit.SECONDS));
        Map<String, Object> source = runtime.sources(CHANNEL).get(0);
        String id = create(Map.of("breakOnEntry", false, "evaluationEnabled", true));
        Map<String, Object> bp = new HashMap<>(Map.of("tabId", TAB, "sourceId", source.get("id"), "sourceHash", source.get("hash"), "sourceGeneration", source.get("generation"),
                "lines", List.of(Map.of("line", 3, "hitCount", 2, "condition", "i === 1"))));
        setBreakpoints(id, bp);
        bp.put("sourceGeneration", -1); assertThrows(IllegalStateException.class, () -> setBreakpoints(id, bp));
        Future<Object> result = run(script, CHANNEL); Map<String, Object> paused = awaitPause(id, 1);
        assertEquals("breakpoint", paused.get("reason")); assertEquals(3, frames(paused).get(0).get("line"));
        receipt(id, command(paused, "continue", Map.of())); assertEquals(4.0, result.get(2, TimeUnit.SECONDS));
    }

    @Test void invalidatingOneChannelDoesNotInvalidateSharedSourceForOtherChannel() throws Exception {
        Script script = compile("var n=1;\nn;", "shared");
        run(script, CHANNEL).get(1, TimeUnit.SECONDS); run(script, "channel-b").get(1, TimeUnit.SECONDS);
        runtime.invalidate(CHANNEL, "Redeployed");
        assertTrue(runtime.sources(CHANNEL).isEmpty()); assertEquals(1, runtime.sources("channel-b").size());
        String id = runtime.create(OWNER, "channel-b", Map.of("tabId", TAB, "includeLifecycle", true)).get("id").toString();
        Future<Object> result = run(script, "channel-b"); Map<String, Object> paused = awaitPause(id, 1);
        receipt(id, command(paused, "continue", Map.of())); assertEquals(1.0, result.get(2, TimeUnit.SECONDS));
    }

    @Test void delayedChannelEventDoesNotDisarmNewOwner() {
        long oldEvent = System.nanoTime();
        String id = create(Map.of("includeLifecycle", false));
        runtime.releaseChannel(CHANNEL, "Old stop event", oldEvent);
        assertEquals("armed", runtime.session(OWNER, id, TAB).get("status"));
        runtime.releaseChannel(CHANNEL, "New stop event", System.nanoTime());
        assertEquals("closed", runtime.session(OWNER, id, TAB).get("status"));
    }

    @Test void releaseChannelClosesLifecycleSessionAndDoesNotRePauseDrainingWork() throws Exception {
        Script script = compile("var n=1;\ndebugger;\nn;", "transformer"); String id = create(Map.of());
        Future<Object> source = run(script, CHANNEL, message(CHANNEL, 21, 0)); awaitPause(id, 1);
        runtime.releaseChannel(CHANNEL, "Channel stopping"); assertEquals(1.0, source.get(2, TimeUnit.SECONDS));
        assertEquals("closed", runtime.session(OWNER, id, TAB).get("status"));
        // The same message's destination, another in-flight message and identity-free work all drain.
        assertEquals(1.0, run(script, CHANNEL, message(CHANNEL, 21, 1)).get(2, TimeUnit.SECONDS));
        assertEquals(1.0, run(script, CHANNEL, message(CHANNEL, 22, 0)).get(2, TimeUnit.SECONDS));
        assertEquals(1.0, run(script, CHANNEL).get(2, TimeUnit.SECONDS));
        assertEquals(1L, runtime.session(OWNER, id, TAB).get("pauseGeneration"));
        assertFalse(runtime.sources(CHANNEL).isEmpty());

        // Lifecycle debugging remains available through an explicitly armed session after stopping.
        String lifecycle = create(Map.of("breakOnEntry", false));
        Future<Object> next = run(script, CHANNEL); awaitPause(lifecycle, 1);
        runtime.close(OWNER, lifecycle, TAB); assertEquals(1.0, next.get(2, TimeUnit.SECONDS));
    }

    @Test void breakpointRetriesDoNotOverwriteNewerWritesOrResetTheirHitCounts() throws Exception {
        Script script = compile("var n=0;\nfor(var i=0;i<3;i++){\nn++;\n}\nn;", "transformer");
        run(script, CHANNEL).get(2, TimeUnit.SECONDS);
        Map<String, Object> source = runtime.sources(CHANNEL).get(0);
        String id = create(Map.of("breakOnEntry", false));
        Map<String, Object> fields = Map.of("sourceId", source.get("id"), "sourceHash", source.get("hash"),
                "sourceGeneration", source.get("generation"), "lines", List.of(2));
        Map<String, Object> old = command(runtime.session(OWNER, id, TAB), "setBreakpoints", fields);
        Map<String, Object> oldReceipt = runtime.breakpoints(OWNER, id, old);
        Map<String, Object> newerFields = new HashMap<>(fields);
        newerFields.put("lines", List.of(Map.of("line", 3, "hitCount", 2)));
        Map<String, Object> newer = command(runtime.session(OWNER, id, TAB), "setBreakpoints", newerFields);
        Map<String, Object> newerReceipt = runtime.command(OWNER, id, newer);
        assertEquals(oldReceipt, runtime.command(OWNER, id, old), "Late retry must return its receipt without replacing the newer write");
        Future<Object> result = run(script, CHANNEL); Map<String, Object> paused = awaitPause(id, 1);
        assertEquals(3, frames(paused).get(0).get("line"));
        assertEquals(newerReceipt, runtime.breakpoints(OWNER, id, newer), "PUT and command retries share receipts across pause generations");
        Map<String, Object> reused = new HashMap<>(old); reused.put("lines", List.of(3));
        assertThrows(IllegalArgumentException.class, () -> runtime.command(OWNER, id, reused));
        assertThrows(IllegalArgumentException.class, () -> runtime.breakpoints(OWNER, id, fields), "Unreceipted PUT must not bypass ordering");
        receipt(id, command(paused, "continue", Map.of()));
        Map<String, Object> next = awaitPause(id, 2);
        assertEquals(3, frames(next).get(0).get("line"), "Retry must preserve the newer breakpoint's accumulated hits");
        receipt(id, command(next, "continue", Map.of())); assertEquals(3.0, result.get(2, TimeUnit.SECONDS));
    }

    @Test void slowSuccessfulEvaluationWatchAndValueEditRetainScalarResults() throws Exception {
        String expression = "java.lang.Thread.sleep(100); 42";
        String id = create(Map.of("evaluationEnabled", true, "breakOnEntry", false, "watches", List.of(expression)));
        Future<Object> result = run(compile("var local=4;\ndebugger;\nlocal;", "transformer"), CHANNEL);
        Map<String, Object> paused = awaitPause(id, 1);
        Map<?, ?> watch = (Map<?, ?>) ((List<?>) paused.get("watches")).get(0);
        assertEquals(42.0, ((Map<?, ?>) watch.get("result")).get("value"));
        Map<String, Object> evaluated = receipt(id, command(paused, "evaluate", Map.of("expression", expression)));
        assertEquals(42.0, ((Map<?, ?>) evaluated.get("result")).get("value"));
        Map<String, Object> edited = receipt(id, command(paused, "setValue", Map.of("name", "local", "valueExpression", "(java.lang.Thread.sleep(100), 43)")));
        assertEquals(43.0, ((Map<?, ?>) edited.get("result")).get("value"));
        receipt(id, command(paused, "continue", Map.of())); assertEquals(43.0, result.get(2, TimeUnit.SECONDS));
    }

    @Test void pausedSourceRequiresCurrentOwnerTabAndLivePause() throws Exception {
        String id = create(Map.of()); Future<Object> result = run(compile("1;", "transformer"), CHANNEL);
        Map<String, Object> paused = awaitPause(id, 1);
        String source = frames(paused).get(0).get("sourceId").toString();
        assertTrue(runtime.isPausedSource(OWNER, id, TAB, source));
        assertFalse(runtime.isPausedSource(OWNER, id, TAB, "unrelated"));
        assertThrows(SecurityException.class, () -> runtime.isPausedSource("another-owner", id, TAB, source));
        assertThrows(SecurityException.class, () -> runtime.isPausedSource(OWNER, id, "another-tab", source));
        assertThrows(IllegalArgumentException.class, () -> runtime.isPausedSource(OWNER, id, null, source));
        runtime.close(OWNER, id, TAB);
        assertFalse(runtime.isPausedSource(OWNER, id, TAB, source));
        assertEquals(1.0, result.get(2, TimeUnit.SECONDS));
    }

    @Test void attachmentIsIdempotentAndDoesNotOverwriteExistingDebugger() {
        runtime.attach(factory); assertEquals(1, runtime.status().get("attachedFactories"));
        Debugger foreign = mock(Debugger.class);
        ContextFactory existing = new ContextFactory() { @Override protected Context makeContext() { Context cx = super.makeContext(); cx.setDebugger(foreign, null); return cx; } };
        runtime.attach(existing); existing.call(cx -> { assertSame(foreign, cx.getDebugger()); return null; });
        assertTrue(runtime.status().get("lastFailure").toString().contains("Another"));
        assertEquals(true, runtime.status().get("debuggerConflict"));
        assertThrows(IllegalStateException.class, () -> create(Map.of()));
    }

    @Test void attachmentGuardIsRecheckedForEveryContextBeforeDebuggerMutation() {
        AtomicInteger calls = new AtomicInteger(); runtime.setAttachmentGuard(ignored -> calls.incrementAndGet() < 2);
        ContextFactory optimized = new ContextFactory(); runtime.attach(optimized);
        optimized.call(cx -> { assertNull(cx.getDebugger()); cx.setOptimizationLevel(0); assertNotNull(cx.compileString("1+1", "optimized", 1, null)); return null; });
    }

    @Test void attachmentFailureDoesNotAffectOrdinaryEngineContext() {
        ContextFactory sealed = new ContextFactory(); sealed.seal();
        assertDoesNotThrow(() -> runtime.attach(sealed));
        assertEquals(1, runtime.status().get("attachedFactories"));
        sealed.call(cx -> { assertNull(cx.getDebugger()); return null; });
        assertNotNull(runtime.status().get("lastFailure"));
    }

    @Test void nullableOptionalCommandFieldDoesNotThrowAndRetryIsStable() {
        String id = create(Map.of());
        Map<String, Object> request = new HashMap<>(Map.of("tabId", TAB, "commandId", "nullable", "expectedPauseGeneration", 0, "action", "pause"));
        request.put("frameIndex", null);
        Map<String, Object> receipt = runtime.command(OWNER, id, request);
        assertEquals("completed", receipt.get("status")); assertEquals(receipt, runtime.command(OWNER, id, request));
    }

    @Test void activeSessionLimitIsBounded() {
        for (int i = 0; i < 32; i++) create(Map.of());
        assertThrows(IllegalStateException.class, () -> create(Map.of()));
    }

    @Test void concurrentArmRetriesCreateOnlyOneSessionAndDoNotRenewItsLease() throws Exception {
        Map<String,Object> options = armRequest();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Map<String,Object>>> results = new ArrayList<>();
        for (int i = 0; i < 12; i++) results.add(workers.submit(() -> { start.await(); return runtime.create(OWNER, CHANNEL, options); }));
        start.countDown();
        Set<Object> ids = new HashSet<>();
        for (Future<Map<String,Object>> result : results) ids.add(result.get(2, TimeUnit.SECONDS).get("id"));
        assertEquals(1, ids.size()); assertEquals(1L, runtime.status().get("activeSessions"));
        String id = ids.iterator().next().toString();
        long deadline = sessionTime(id, "leaseDeadline");
        assertEquals(id, runtime.create(OWNER, CHANNEL, options).get("id"));
        assertEquals(deadline, sessionTime(id, "leaseDeadline"), "Recovering Arm must not renew the original lease");
    }

    @Test void recoveredArmOwnsAndClosesTheOriginallyHiddenWorker() throws Exception {
        Map<String,Object> options = armRequest();
        String lostId = runtime.create(OWNER, CHANNEL, options).get("id").toString();
        Future<Object> worker = run(compile("var value=41;\nvalue+1;", "transformer"), CHANNEL);
        awaitPause(lostId, 1);
        Map<String,Object> recovered = runtime.create(OWNER, CHANNEL, options);
        assertEquals(lostId, recovered.get("id")); assertEquals("paused", recovered.get("status"));
        runtime.close(OWNER, recovered.get("id").toString(), TAB);
        assertEquals(42.0, worker.get(2, TimeUnit.SECONDS)); assertEquals(0L, runtime.status().get("activeSessions"));
        assertEquals("closed", runtime.create(OWNER, CHANNEL, options).get("status"));
        assertEquals(42.0, run(compile("var value=41;\nvalue+1;", "next-transformer"), CHANNEL).get(1, TimeUnit.SECONDS));
    }

    @Test void armIdsAreScopedToOwnerAndTabAndRejectChangedOptionsOrChannel() {
        Map<String,Object> options = armRequest();
        String id = runtime.create(OWNER, CHANNEL, options).get("id").toString();
        assertNotEquals(id, runtime.create("other-owner", CHANNEL, options).get("id"));
        Map<String,Object> otherTab = new HashMap<>(options); otherTab.put("tabId", "other-tab");
        assertNotEquals(id, runtime.create(OWNER, CHANNEL, otherTab).get("id"));
        Map<String,Object> changed = new HashMap<>(options); changed.put("breakOnEntry", false);
        assertThrows(IllegalStateException.class, () -> runtime.create(OWNER, CHANNEL, changed));
        assertThrows(IllegalStateException.class, () -> runtime.create(OWNER, "other-channel", options));
        assertEquals(3L, runtime.status().get("activeSessions"));
    }

    @Test void armReceiptPreservesTheOriginalNestedOptionsAndWatches() {
        Map<String,Object> options = armRequest();
        List<String> watches = new ArrayList<>(List.of("1"));
        List<String> nested = new ArrayList<>(List.of("original"));
        options.put("evaluationEnabled", true); options.put("watches", watches);
        options.put("extension", new HashMap<>(Map.of("nested", nested)));
        String id = runtime.create(OWNER, CHANNEL, options).get("id").toString();
        watches.add("2"); nested.add("changed");
        assertThrows(IllegalStateException.class, () -> runtime.create(OWNER, CHANNEL, options));
        options.put("watches", List.of("1")); options.put("extension", Map.of("nested", List.of("original")));
        runtime.command(OWNER, id, command(runtime.session(OWNER, id, TAB), "setWatches", Map.of("expressions", List.of("3"))));
        assertEquals(id, runtime.create(OWNER, CHANNEL, options).get("id"));
    }

    @Test void armRecoveryWorksAtBothActiveAndRetentionLimits() {
        Map<String,Object> options = armRequest();
        String id = runtime.create(OWNER, CHANNEL, options).get("id").toString();
        for (int i = 1; i < 32; i++) create(Map.of());
        assertEquals(id, runtime.create(OWNER, CHANNEL, options).get("id"));
        assertThrows(IllegalStateException.class, () -> create(Map.of()));
        runtime.closeAll("test");
        for (int i = 32; i < 128; i++) runtime.close(OWNER, create(Map.of()), TAB);
        assertEquals("closed", runtime.create(OWNER, CHANNEL, options).get("status"));
        assertThrows(IllegalStateException.class, () -> create(Map.of()));
    }

    @Test void expiredArmRecoversRetainedSessionButCannotRecreateAnEvictedReceipt() throws Exception {
        Map<String,Object> options = armRequest();
        long expiry = TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) + 2_000;
        options.put("requestExpiresAt", expiry); options.put("leaseMillis", 60_000);
        String id = runtime.create(OWNER, CHANNEL, options).get("id").toString();
        Thread.sleep(Math.max(1, expiry - TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) + 1));
        assertEquals(id, runtime.create(OWNER, CHANNEL, options).get("id"));
        runtime.close(OWNER, id, TAB);
        assertEquals("closed", runtime.create(OWNER, CHANNEL, options).get("status"));
        setSessionTime(id, "closedAt", TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - 60_001);
        DebugRuntime.ArmRequestRejected rejected = assertThrows(DebugRuntime.ArmRequestRejected.class,
                () -> runtime.create(OWNER, CHANNEL, options));
        assertEquals(options.get("requestId"), rejected.requestId());
        assertInstanceOf(IllegalStateException.class, rejected.getCause());
        assertThrows(SecurityException.class, () -> runtime.session(OWNER, id, TAB));
        assertEquals(0L, runtime.status().get("activeSessions"));
    }

    @Test void armRequestIdsAndDeadlinesAreStrictlyValidatedBeforeCreating() {
        for (Object invalid : Arrays.asList(null, "", " ", 42, "x".repeat(129))) {
            Map<String,Object> options = armRequest(); options.put("requestId", invalid);
            assertThrows(IllegalArgumentException.class, () -> runtime.create(OWNER, CHANNEL, options));
        }
        for (Object invalid : Arrays.asList(null, "123", 1.5, true)) {
            Map<String,Object> options = armRequest(); options.put("requestExpiresAt", invalid);
            assertThrows(IllegalArgumentException.class, () -> runtime.create(OWNER, CHANNEL, options));
        }
        Map<String,Object> missing = armRequest(); missing.remove("requestExpiresAt");
        assertThrows(IllegalArgumentException.class, () -> runtime.create(OWNER, CHANNEL, missing));
        Map<String,Object> expired = armRequest(); expired.put("requestExpiresAt", Long.MIN_VALUE);
        assertThrows(DebugRuntime.ArmRequestRejected.class, () -> runtime.create(OWNER, CHANNEL, expired));
        Map<String,Object> future = armRequest(); future.put("requestExpiresAt", Long.MAX_VALUE);
        assertThrows(IllegalStateException.class, () -> runtime.create(OWNER, CHANNEL, future));
        assertEquals(0L, runtime.status().get("activeSessions"));
        assertEquals("armed", runtime.session(OWNER, create(Map.of()), TAB).get("status"), "Legacy callers can still create without a request ID");
    }

    @Test void recoveryRechecksAuthorizationButSkipsFreshSourceValidation() {
        Map<String,Object> options = armRequest();
        AtomicInteger authorizations = new AtomicInteger(), sourceChecks = new AtomicInteger();
        String id = runtime.create(OWNER, CHANNEL, options, authorizations::incrementAndGet, sourceChecks::incrementAndGet).get("id").toString();
        assertEquals(id, runtime.create(OWNER, CHANNEL, options, authorizations::incrementAndGet, () -> { throw new IllegalStateException("source replaced"); }).get("id"));
        assertEquals(2, authorizations.get()); assertEquals(1, sourceChecks.get());
        assertThrows(SecurityException.class, () -> runtime.create("other-owner", CHANNEL, options, () -> { throw new SecurityException("denied"); }, () -> { }));
        assertEquals("armed", runtime.session(OWNER, id, TAB).get("status"));
        DebugRuntime.ArmRequestRejected rejected = assertThrows(DebugRuntime.ArmRequestRejected.class,
                () -> runtime.create(OWNER, CHANNEL, options, () -> { throw new SecurityException("denied"); }, () -> { }));
        assertEquals(options.get("requestId"), rejected.requestId());
        assertInstanceOf(SecurityException.class, rejected.getCause());
        assertEquals("closed", runtime.session(OWNER, id, TAB).get("status"));
    }

    @Test void stopAndRestartDetachListenersAndRestartLeaseWatchdog() throws Exception {
        String id = create(Map.of()); Future<Object> result = run(compile("var n=1;\nn;", "transformer"), CHANNEL);
        awaitPause(id, 1); runtime.configure(false, false); assertEquals(1.0, result.get(2, TimeUnit.SECONDS));
        assertEquals(0, runtime.status().get("attachedFactories")); assertTrue(runtime.sources(CHANNEL).isEmpty());
        factory.call(cx -> { assertNull(cx.getDebugger()); return null; });
        runtime.configure(true, false); runtime.attach(factory);
        String nextId = create(Map.of("maxPauseMillis", 1000)); Future<Object> next = run(compile("var n=2;\nn;", "transformer"), CHANNEL);
        awaitPause(nextId, 1); assertEquals(2.0, next.get(3, TimeUnit.SECONDS));
        assertEquals("closed", runtime.session(OWNER, nextId, TAB).get("status"));
    }

    @Test void recompileInvalidatesOldSourceAndReleasesPausedGeneration() throws Exception {
        Script script = compile("var n=1;\nn;", "transformer"); String id = create(Map.of());
        Future<Object> result = run(script, CHANNEL); Map<String, Object> paused = awaitPause(id, 1);
        String sourceId = frames(paused).get(0).get("sourceId").toString();
        assertTrue(runtime.isSourceScript(sourceId, ((NativeFunction) script).getDebuggableView()));
        Script replacement = compile("var n=2;\nn;", "transformer");
        assertEquals(1.0, result.get(2, TimeUnit.SECONDS)); assertEquals("closed", runtime.session(OWNER, id, TAB).get("status"));
        assertFalse(runtime.isSourceScript(sourceId, ((NativeFunction) script).getDebuggableView()));
        assertThrows(IllegalArgumentException.class, () -> runtime.source(sourceId));
        assertThrows(IllegalArgumentException.class, () -> create(Map.of("sourceId", sourceId)));
        assertEquals(2.0, run(replacement, CHANNEL).get(1, TimeUnit.SECONDS));
    }

    @Test void oneExceptionUnwindingSeveralFramesPausesOnlyOnce() throws Exception {
        String id = create(Map.of("breakOnEntry", false, "pauseOnExceptions", true));
        Script script = compile("function boom(){\nthrow 'failure';\n}\nfunction outer(){\nboom();\n}\nouter();", "transformer");
        Future<Object> result = run(script, CHANNEL); Map<String, Object> paused = awaitPause(id, 1);
        assertEquals(3, frames(paused).size()); receipt(id, command(paused, "continue", Map.of()));
        ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
        assertInstanceOf(JavaScriptException.class, failure.getCause()); assertEquals(1L, runtime.session(OWNER, id, TAB).get("pauseGeneration"));
    }

    @Test void engineHaltErrorDoesNotBecomeAnExceptionBreakpoint() throws Exception {
        String id = create(Map.of("breakOnEntry", false, "pauseOnExceptions", true));
        Script script = compile("cancel();", "transformer");
        Future<Object> result = workers.submit(() -> factory.call(cx -> {
            Scriptable scope = cx.initStandardObjects(); ScriptableObject.putProperty(scope, "channelId", CHANNEL);
            ScriptableObject.putProperty(scope, "cancel", new BaseFunction() {
                @Override public Object call(Context context, Scriptable scope, Scriptable self, Object[] args) { throw new Error("engine halted"); }
            });
            return script.exec(cx, scope);
        }));
        ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS));
        assertInstanceOf(Error.class, failure.getCause()); assertEquals(0L, runtime.session(OWNER, id, TAB).get("pauseGeneration"));
    }

    @Test void maximumPausedWorkersFailsOpenForAdditionalExecutions() throws Exception {
        Script script = compile("var n=1;\nn;", "transformer");
        List<Future<Object>> executions = new ArrayList<>();
        for (int i = 0; i < 5; i++) create(Map.of());
        for (int i = 0; i < 5; i++) executions.add(run(script, CHANNEL));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (executions.stream().noneMatch(Future::isDone) && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(4, runtime.status().get("paused")); assertEquals(1L, executions.stream().filter(Future::isDone).count());
        runtime.closeAll("Test cleanup");
        for (Future<Object> result : executions) assertEquals(1.0, result.get(2, TimeUnit.SECONDS));
    }

    private Script compile(String text, String name) { return factory.call(cx -> cx.compileString(text, name, 1, null)); }
    public static final class NativeFailure {
        private final Error failure;
        NativeFailure(Error failure) { this.failure = failure; }
        public void fail() { throw failure; }
    }
    private Future<Object> runWithNativeFailure(Script script, Error failure) {
        return workers.submit(() -> factory.call(cx -> {
            Scriptable scope = cx.initStandardObjects(); ScriptableObject.putProperty(scope, "channelId", CHANNEL);
            ScriptableObject.putProperty(scope, "nativeFailure", Context.javaToJS(new NativeFailure(failure), scope));
            return script.exec(cx, scope);
        }));
    }
    private Future<Object> run(Script script, String channel) { return run(script, channel, null); }
    private Future<Object> run(Script script, String channel, Object message) { return workers.submit(() -> execute(script, channel, message)); }
    private Object execute(Script script, String channel, Object message) {
        return factory.call(cx -> {
            Scriptable scope = cx.initStandardObjects();
            if (channel != null) ScriptableObject.putProperty(scope, "channelId", channel);
            if (message != null) ScriptableObject.putProperty(scope, "connectorMessage", Context.javaToJS(message, scope));
            return script.exec(cx, scope);
        });
    }
    private Object message(String channel, long messageId, int connector) {
        ImmutableConnectorMessage message = mock(ImmutableConnectorMessage.class);
        when(message.getChannelId()).thenReturn(channel); when(message.getMessageId()).thenReturn(messageId); when(message.getMetaDataId()).thenReturn(connector); when(message.getSendAttempts()).thenReturn(3); return message;
    }
    private String create(Map<String, Object> overrides) {
        Map<String, Object> options = new HashMap<>(Map.of("tabId", TAB, "includeLifecycle", true)); options.putAll(overrides);
        return runtime.create(OWNER, CHANNEL, options).get("id").toString();
    }
    private Map<String,Object> armRequest() {
        return new HashMap<>(Map.of("tabId", TAB, "includeLifecycle", true, "requestId", UUID.randomUUID().toString(),
                "requestExpiresAt", runtime.status().get("armRequestExpiresAt")));
    }
    private Object storedSession(String id) throws Exception {
        var field = DebugRuntime.class.getDeclaredField("sessions"); field.setAccessible(true);
        return ((Map<?,?>) field.get(runtime)).get(id);
    }
    private long sessionTime(String id, String name) throws Exception {
        Object session = storedSession(id); var field = session.getClass().getDeclaredField(name); field.setAccessible(true);
        synchronized (session) { return field.getLong(session); }
    }
    private void setSessionTime(String id, String name, long value) throws Exception {
        Object session = storedSession(id); var field = session.getClass().getDeclaredField(name); field.setAccessible(true);
        synchronized (session) { field.setLong(session, value); }
    }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> frames(Map<String, Object> snapshot) { return (List<Map<String, Object>>) snapshot.get("frames"); }
    private Map<String, Object> awaitPause(String id, long generation) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        Map<String, Object> snapshot;
        do {
            snapshot = runtime.session(OWNER, id, TAB);
            if (snapshot.get("status").equals("paused") && ((Number) snapshot.get("pauseGeneration")).longValue() == generation && !frames(snapshot).isEmpty()) return snapshot;
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        fail("Expected pause " + generation + ", got " + snapshot); return null;
    }
    private Map<String, Object> command(Map<String, Object> paused, String action, Map<String, Object> extras) {
        Map<String, Object> result = new HashMap<>(Map.of("tabId", TAB, "commandId", UUID.randomUUID().toString(), "expectedPauseGeneration", paused.get("pauseGeneration"), "action", action)); result.putAll(extras); return result;
    }
    private Map<String, Object> setBreakpoints(String id, Map<String, Object> fields) {
        return runtime.breakpoints(OWNER, id, command(runtime.session(OWNER, id, TAB), "setBreakpoints", fields));
    }
    private Map<String, Object> receipt(String id, Map<String, Object> request) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        Map<String, Object> receipt;
        do { receipt = runtime.command(OWNER, id, request); if (receipt.get("status").equals("completed")) return receipt; Thread.sleep(5); } while (System.nanoTime() < deadline);
        fail("Command remained pending: " + receipt); return null;
    }
}
