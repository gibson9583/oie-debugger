package org.openintegrationengine.debugger.runtime;

import org.junit.jupiter.api.Test;
import org.mozilla.javascript.*;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class SourceRetentionRuntimeTest {
    @Test void cachedEngineScriptStillHitsItsBreakpointAfterUnrelatedCompilationChurn() throws Exception {
        DebugRuntime runtime = new DebugRuntime(); runtime.configure(true, false);
        ContextFactory factory = factory();
        runtime.attach(factory);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            String text = "var n=1;\nn++;\nn;";
            Script first = factory.call(cx -> cx.compileString(text, "first-transformer", 1, null));
            assertEquals(2.0, execute(factory, first));
            Map<String, Object> source = runtime.sources("channel").get(0);
            List<Script> engineCache = new ArrayList<>(); engineCache.add(first);
            for (int i = 0; i < 300; i++) {
                String name = "unrelated-validation-" + i;
                engineCache.add(factory.call(cx -> cx.compileString("1;", name, 1, null)));
            }
            String sourceId = source.get("id").toString();
            assertEquals(text, runtime.source(sourceId).get("text"));
            assertEquals(sourceId, runtime.sources("channel").get(0).get("id"));
            assertTrue(runtime.isSourceScript(sourceId, ((NativeFunction) first).getDebuggableView()));
            String id = runtime.create("owner", "channel", Map.of("tabId", "tab", "includeLifecycle", true,
                    "sourceId", sourceId, "breakOnEntry", false)).get("id").toString();
            runtime.breakpoints("owner", id, Map.of("tabId", "tab", "sourceId", sourceId,
                    "sourceHash", source.get("hash"), "sourceGeneration", source.get("generation"), "lines", List.of(2),
                    "commandId", "set-breakpoint", "expectedPauseGeneration", 0));
            Future<Object> result = worker.submit(() -> execute(factory, first));
            Map<String, Object> paused = awaitPause(runtime, id);
            assertEquals("breakpoint", paused.get("reason"));
            runtime.command("owner", id, Map.of("tabId", "tab", "commandId", "continue", "action", "continue",
                    "expectedPauseGeneration", paused.get("pauseGeneration")));
            assertEquals(2.0, result.get(2, TimeUnit.SECONDS));
            assertEquals("armed", runtime.session("owner", id, "tab").get("status"));
            java.lang.ref.Reference.reachabilityFence(engineCache);
        } finally {
            runtime.shutdown(); worker.shutdownNow();
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS), "Owned script worker must exit");
        }
    }

    @Test void simultaneousEvalInvocationsKeepTheirOwnPausedSources() throws Exception {
        DebugRuntime runtime = new DebugRuntime(); runtime.configure(true, false);
        ContextFactory factory = factory(); runtime.attach(factory);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Script script = factory.call(cx -> cx.compileString("eval('debugger;\\n1;');", "transformer", 1, null));
            Map<String, Object> options = Map.of("tabId", "tab", "includeLifecycle", true, "breakOnEntry", false);
            String firstId = runtime.create("owner", "channel", options).get("id").toString();
            Future<Object> first = workers.submit(() -> execute(factory, script));
            Map<String, Object> firstPause = awaitPause(runtime, firstId);
            String secondId = runtime.create("owner", "channel", options).get("id").toString();
            Future<Object> second = workers.submit(() -> execute(factory, script));
            Map<String, Object> secondPause = awaitPause(runtime, secondId);
            Map<?, ?> firstFrame = (Map<?, ?>) ((List<?>) firstPause.get("frames")).get(0);
            Map<?, ?> secondFrame = (Map<?, ?>) ((List<?>) secondPause.get("frames")).get(0);
            Map<String, Object> firstSource = runtime.source(firstFrame.get("sourceId").toString());
            Map<String, Object> secondSource = runtime.source(secondFrame.get("sourceId").toString());
            assertEquals(true, firstSource.get("dynamic")); assertEquals(true, secondSource.get("dynamic"));
            assertEquals(firstSource.get("name"), secondSource.get("name"));
            assertNotEquals(firstSource.get("id"), secondSource.get("id"));
            assertEquals("paused", runtime.session("owner", firstId, "tab").get("status"));
            runtime.closeAll("Test complete");
            assertEquals(1.0, first.get(2, TimeUnit.SECONDS)); assertEquals(1.0, second.get(2, TimeUnit.SECONDS));
        } finally {
            runtime.shutdown(); workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS), "Owned script workers must exit");
        }
    }

    private static ContextFactory factory() {
        return new ContextFactory() {
            @Override protected Context makeContext() {
                Context context = super.makeContext(); context.setOptimizationLevel(-1); return context;
            }
        };
    }

    private static Object execute(ContextFactory factory, Script script) {
        return factory.call(cx -> {
            Scriptable scope = cx.initStandardObjects(); ScriptableObject.putProperty(scope, "channelId", "channel");
            return script.exec(cx, scope);
        });
    }

    private static Map<String, Object> awaitPause(DebugRuntime runtime, String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        Map<String, Object> snapshot;
        do {
            snapshot = runtime.session("owner", id, "tab");
            if ("paused".equals(snapshot.get("status")) && !((List<?>) snapshot.get("frames")).isEmpty()) return snapshot;
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        fail("Retained script did not hit its breakpoint: " + snapshot); return null;
    }
}
