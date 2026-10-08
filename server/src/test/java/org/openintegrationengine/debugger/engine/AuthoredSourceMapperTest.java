package org.openintegrationengine.debugger.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.openintegrationengine.debugger.runtime.DebugRuntime;
import org.openintegrationengine.debugger.engine.AuthoredSourceMapper.Snippet;

import com.mirth.connect.model.Channel;
import com.mirth.connect.model.DeployedChannelInfo;
import com.mirth.connect.server.controllers.ChannelController;
import com.mirth.connect.server.util.CompiledScriptCache;

class AuthoredSourceMapperTest {
    @Test void exactUniqueTextMapsToCapturedSourceAndAuthoredLine() {
        String generated = "function doScript() {\nvar value = 1;\nvalue++; \n}\n";
        List<Map<String, Object>> result = AuthoredSourceMapper.match("generation:sha256", generated,
                List.of(new Snippet("id", "Step", "transformer", 7, "var value = 1;\nvalue++;", 4)));
        assertEquals(1, result.size());
        assertEquals("generation:sha256", result.get(0).get("sourceId"));
        assertEquals(2, result.get(0).get("generatedStartLine"));
        assertEquals(3, result.get(0).get("generatedEndLine"));
        assertEquals(4, result.get(0).get("authoredStartLine"));
    }

    @Test void repeatedTextOverlappingCandidatesAndMidStatementFragmentsAreRejected() {
        Snippet shortCode = snippet("short", "value++;");
        assertTrue(AuthoredSourceMapper.match("s", "value++;\nvalue++;\n", List.of(shortCode)).isEmpty());
        assertTrue(AuthoredSourceMapper.match("s", "value++;\n", List.of(shortCode, snippet("same", "value++;"))).isEmpty());
        assertTrue(AuthoredSourceMapper.match("s", "function f() { value++; }\n", List.of(shortCode)).isEmpty());
        assertTrue(AuthoredSourceMapper.match("s", "value++;\nother();\n",
                List.of(shortCode, snippet("overlap", "value++;\nother();"))).isEmpty());
    }

    @Test void lineCountingPreservesCrLfAndJavascriptUnicodeSeparators() {
        assertEquals(5, AuthoredSourceMapper.lineAt("a\r\nb\rc\u2028d\u2029e", 10));
        assertEquals(1, AuthoredSourceMapper.lineAt("a", 0));
    }

    @Test void uncertainOrConcurrentDeploymentCannotRelabelCompiledSource() {
        Channel channel = mock(Channel.class);
        when(channel.getRevision()).thenReturn(7);
        when(channel.getDeployScript()).thenReturn("run();");
        DeployedChannelInfo info = new DeployedChannelInfo();
        info.setDeployedRevision(7);
        ChannelController controller = mock(ChannelController.class);
        when(controller.getDeployedChannelById("channel")).thenReturn(channel);
        when(controller.getDeployedChannelInfoById("channel")).thenReturn(info);
        try (MockedStatic<ChannelController> controllers = mockStatic(ChannelController.class)) {
            controllers.when(ChannelController::getInstance).thenReturn(controller);
            info.setDeployedRevision(8);
            assertTrue(AuthoredSourceMapper.mapCurrent("channel", "ambiguous", "run();\n").isEmpty());
            info.setDeployedRevision(7);
            assertEquals(1, AuthoredSourceMapper.mapCurrent("channel", "current", "run();\n").size());
            when(controller.getDeployedChannelById("channel")).thenReturn(channel, mock(Channel.class));
            assertTrue(AuthoredSourceMapper.mapCurrent("channel", "concurrent-redeploy", "run();\n").isEmpty());
        }
    }

    @Test void mapsOnlyTheExactCurrentCompiledIdentityAndNeverTheMutableCachedText() {
        String sourceName = "mapper-test-" + UUID.randomUUID();
        String generated = "var value = 1;\n";
        DebugRuntime runtime = new DebugRuntime();
        runtime.configure(true, false);
        ContextFactory factory = new ContextFactory();
        runtime.attach(factory);
        ContextFactory replacementFactory = new ContextFactory();
        runtime.attach(replacementFactory);
        Script replacement = replacementFactory.call(cx -> {
            cx.setOptimizationLevel(-1);
            return cx.compileString("var value = 2;\n", sourceName, 1, null);
        });
        Channel channel = mock(Channel.class);
        when(channel.getRevision()).thenReturn(7);
        when(channel.getDeployScript()).thenReturn("var value = 1;");
        DeployedChannelInfo info = new DeployedChannelInfo();
        info.setDeployedRevision(7);
        ChannelController controller = mock(ChannelController.class);
        when(controller.getDeployedChannelById("channel")).thenReturn(channel);
        when(controller.getDeployedChannelInfoById("channel")).thenReturn(info);
        CompiledScriptCache cache = CompiledScriptCache.getInstance();
        try (MockedStatic<ChannelController> controllers = mockStatic(ChannelController.class)) {
            controllers.when(ChannelController::getInstance).thenReturn(controller);
            factory.call(cx -> {
                cx.setOptimizationLevel(-1);
                Scriptable scope = cx.initStandardObjects();
                scope.put("channelId", scope, "channel");
                Script script = cx.compileString(generated, sourceName, 1, null);
                script.exec(cx, scope);
                String sourceId = (String) runtime.sources("channel").get(0).get("id");
                assertFalse(AuthoredSourceMapper.isCurrentSource(sourceId, sourceName, runtime));
                assertTrue(AuthoredSourceMapper.map("channel", sourceId, sourceName, generated, runtime).isEmpty());
                cache.putCompiledScript(sourceName, script, "deliberately unrelated mutable source text");
                assertTrue(AuthoredSourceMapper.isCurrentSource(sourceId, sourceName, runtime));
                assertEquals(1, AuthoredSourceMapper.map("channel", sourceId, sourceName, generated, runtime).size());
                cache.putCompiledScript(sourceName, replacement, generated);
                assertNotNull(runtime.source(sourceId), "Distinct factory source remains captured but is not current engine code");
                assertFalse(AuthoredSourceMapper.isCurrentSource(sourceId, sourceName, runtime));
                assertTrue(AuthoredSourceMapper.map("channel", sourceId, sourceName, generated, runtime).isEmpty());
                return null;
            });
        } finally {
            cache.removeCompiledScript(sourceName);
            runtime.shutdown();
        }
    }

    private static Snippet snippet(String id, String code) { return new Snippet(id, id, "transformer", 1, code, 1); }
}
