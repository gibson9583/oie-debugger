package org.openintegrationengine.debugger.api;

import com.mirth.connect.client.core.ControllerException;
import com.mirth.connect.client.core.Operation;
import com.mirth.connect.client.core.api.MirthOperation;
import com.mirth.connect.model.Channel;
import com.mirth.connect.server.controllers.AuthorizationController;
import com.mirth.connect.server.controllers.ChannelController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.util.CompiledScriptCache;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.openintegrationengine.debugger.DebuggerPlugin;
import org.openintegrationengine.debugger.runtime.DebugRuntime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises channel authorization independently of the debugger's operation permissions. */
class DebuggerChannelAccessTest {
    private static final String A = "channel-a", B = "channel-b";
    private static final String OWNER = "7:browser-session", TAB = "tab-a";
    private DebugRuntime runtime;
    private ControllerFactory controllers;
    private AuthorizationController authorization;
    private ChannelController channels;
    private HttpServletRequest request;
    private Set<String> allowed;
    private final List<String> cachedSources = new ArrayList<>();

    @BeforeEach void setup() throws Exception {
        runtime = new DebugRuntime();
        runtime.configure(true, true);
        controllers = mock(ControllerFactory.class);
        authorization = mock(AuthorizationController.class);
        channels = mock(ChannelController.class);
        when(controllers.createAuthorizationController()).thenReturn(authorization);
        when(controllers.createChannelController()).thenReturn(channels);
        when(channels.getChannelById(A)).thenReturn(new Channel());
        when(channels.getChannelById(B)).thenReturn(new Channel());
        // Deliberately grant every operation: channel scope must be enforced separately.
        when(authorization.isUserAuthorized(anyInt(), any(), anyMap(), any(), anyBoolean()))
                .thenReturn(true);
        allowed = Set.of(A);
        when(authorization.doesUserHaveChannelRestrictions(anyInt(), any())).thenReturn(true);
        when(authorization.getChannelAuthorizer(anyInt(), any()))
                .thenAnswer(call -> (com.mirth.connect.server.controllers.ChannelAuthorizer)
                        channel -> allowed.contains(channel));
        request = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        when(request.getSession()).thenReturn(session);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("authorized")).thenReturn(true);
        when(session.getAttribute("user")).thenReturn(7);
        when(session.getId()).thenReturn("browser-session");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        Properties policy = new Properties();
        policy.setProperty("enabled", "true");
        new DebuggerPlugin().init(policy);
    }

    @AfterEach void cleanup() {
        runtime.shutdown();
        cachedSources.forEach(CompiledScriptCache.getInstance()::removeCompiledScript);
        new DebuggerPlugin().init(new Properties());
        DebugRuntime.getInstance().configure(false, false);
    }

    @ParameterizedTest @ValueSource(strings = {"list", "detail"})
    void sharedSourceMetadataDoesNotRevealOtherChannels(String endpoint) {
        String sourceId = sharedSource();
        assertEquals(List.of(A, B), runtime.source(sourceId).get("channels"));
        Response response = endpoint.equals("list")
                ? servlet("debuggerSources").sources(A)
                : servlet("debuggerSource").source(sourceId, A, null, null);
        assertEquals(200, response.getStatus());
        Map<?, ?> source = endpoint.equals("list")
                ? (Map<?, ?>) ((List<?>) Json.read("{\"sources\":" + response.getEntity() + "}")
                        .get("sources")).get(0)
                : body(response);
        assertEquals(sourceId, source.get("id"));
        assertEquals(List.of(A), source.get("channels"),
                "Reading a shared source through A must not reveal forbidden channel B");
        assertEquals(List.of(A, B), runtime.source(sourceId).get("channels"),
                "Response filtering must preserve internal source membership");
    }

    @Test void concurrentChannelReadsKeepTheirOwnSourceMembership() throws Exception {
        String sourceId = sharedSource();
        allowed = Set.of(A, B);
        var workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> reads = new ArrayList<>();
            for (String channel : List.of(A, B)) reads.add(workers.submit(() -> {
                assertTrue(start.await(3, TimeUnit.SECONDS));
                Response response = servlet("debuggerSources").sources(channel);
                assertEquals(200, response.getStatus());
                List<?> sources = (List<?>) Json.read("{\"sources\":" + response.getEntity() + "}")
                        .get("sources");
                assertEquals(1, sources.size());
                assertEquals(List.of(channel), ((Map<?, ?>) sources.get(0)).get("channels"));
                Map<String, Object> detail = body(servlet("debuggerSource").source(
                        sourceId, channel, null, null));
                assertEquals(List.of(channel), detail.get("channels"));
                return null;
            }));
            start.countDown();
            for (Future<?> read : reads) read.get(3, TimeUnit.SECONDS);
            assertEquals(List.of(A, B), runtime.source(sourceId).get("channels"));
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS), "Owned HTTP test workers must exit");
        }
    }

    @ParameterizedTest @ValueSource(strings = {A, "global"})
    void unrestrictedReadsAlsoProjectOnlyTheSelectedTarget(String target) throws Exception {
        when(authorization.doesUserHaveChannelRestrictions(anyInt(), any())).thenReturn(false);
        String sourceId = sharedSource(A, B, "global");
        Response response = servlet("debuggerSources").sources(target);
        assertEquals(200, response.getStatus());
        List<?> sources = (List<?>) Json.read("{\"sources\":" + response.getEntity() + "}")
                .get("sources");
        assertEquals(1, sources.size());
        assertEquals(List.of(target), ((Map<?, ?>) sources.get(0)).get("channels"));
        assertEquals(List.of(target), body(servlet("debuggerSource").source(
                sourceId, target, null, null)).get("channels"));
        assertEquals(List.of(A, B, "global"), runtime.source(sourceId).get("channels"));
    }

    @Test void operationGrantsCannotReadOrArmAForbiddenChannel() {
        String sourceId = sharedSource();
        assertEquals(200, servlet("debuggerSources").sources(A).getStatus());
        assertEquals(200, servlet("debuggerSource").source(sourceId, A, null, null).getStatus());
        clearInvocations(channels);
        assertStatus(403, () -> servlet("debuggerSources").sources(B));
        assertStatus(403, () -> servlet("debuggerSource").source(sourceId, B, null, null));
        assertStatus(403, () -> servlet("debuggerCreate").create(
                Json.write(Map.of("channelId", B, "tabId", TAB))));
        assertEquals(0L, runtime.status().get("activeSessions"));
        // The engine audit encoder may look up the target after denial; the mocked
        // audit controller here does not, so this assertion covers the action itself.
        verify(channels, never()).getChannelById(B);
    }

    @Test void restrictedUserCanArmPermittedMessagesButNotGlobalOrLifecycle() {
        try (var plugin = mockStatic(DebuggerPlugin.class, CALLS_REAL_METHODS)) {
            plugin.when(DebuggerPlugin::unavailableReason).thenReturn(null);
            assertEquals(false, body(servlet("debuggerCapabilities").capabilities()).get("globalAllowed"));
            assertEquals("armed", body(servlet("debuggerCreate").create(
                    Json.write(Map.of("channelId", A, "tabId", TAB)))).get("status"));
            assertStatus(403, () -> servlet("debuggerCreate").create(Json.write(Map.of(
                    "channelId", A, "tabId", TAB, "includeLifecycle", true))));
            assertStatus(403, () -> servlet("debuggerCreate").create(Json.write(Map.of(
                    "channelId", "global", "tabId", TAB, "includeGlobal", true))));
            assertEquals(1L, runtime.status().get("activeSessions"));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"session", "heartbeat", "close", "breakpoints",
            "pause", "continue", "stepInto", "stepOver", "stepOut", "inspect", "evaluate",
            "setValue", "setWatches", "setBreakpoints"})
    void channelRevocationClosesOwnedLeaseBeforeAnySessionAction(String action) {
        String id = runtime.create(OWNER, A, Map.of("tabId", TAB)).get("id").toString();
        String foreign = runtime.create("another-owner", A, Map.of("tabId", TAB)).get("id").toString();
        allowed = Set.of(B);
        assertStatus(403, () -> sessionAction(action, id, TAB));
        assertEquals("closed", runtime.session(OWNER, id, TAB).get("status"));
        assertEquals("armed", runtime.session("another-owner", foreign, TAB).get("status"));
    }

    @ParameterizedTest @ValueSource(strings = {"session", "heartbeat", "close", "breakpoints", "inspect"})
    void revokedForeignTabCannotCloseOwnersLease(String action) {
        String id = runtime.create(OWNER, A, Map.of("tabId", TAB)).get("id").toString();
        allowed = Set.of(B);
        assertStatus(403, () -> sessionAction(action, id, "another-tab"));
        assertEquals("armed", runtime.session(OWNER, id, TAB).get("status"));
    }

    @Test void armRetryAfterChannelRevocationClosesOriginalAndCannotRearm() {
        try (var plugin = mockStatic(DebuggerPlugin.class, CALLS_REAL_METHODS)) {
            plugin.when(DebuggerPlugin::unavailableReason).thenReturn(null);
            Map<String, Object> options = Map.of("channelId", A, "tabId", TAB,
                    "requestId", UUID.randomUUID().toString(),
                    "requestExpiresAt", runtime.status().get("armRequestExpiresAt"));
            String requestBody = Json.write(options);
            String id = body(servlet("debuggerCreate").create(requestBody)).get("id").toString();
            allowed = Set.of(B);
            for (int retry = 0; retry < 2; retry++) {
                Response response = servlet("debuggerCreate").create(requestBody);
                assertEquals(403, response.getStatus());
                assertEquals("arm_request_rejected", Json.read((String) response.getEntity()).get("code"));
                assertEquals("closed", runtime.session(OWNER, id, TAB).get("status"));
                assertEquals(0L, runtime.status().get("activeSessions"));
            }
        }
    }

    @Test void completedCommandReceiptCannotBypassChannelRevocation() {
        String id = runtime.create(OWNER, A, Map.of("tabId", TAB)).get("id").toString();
        String command = Json.write(Map.of("tabId", TAB, "action", "pause",
                "commandId", UUID.randomUUID().toString(), "commandSequence", 1,
                "expectedPauseGeneration", 0));
        Map<String, Object> receipt = body(servlet("debuggerCommand").command(id, command));
        assertEquals("completed", receipt.get("status"));
        assertEquals(receipt, body(servlet("debuggerCommand").command(id, command)));
        allowed = Set.of(B);
        assertStatus(403, () -> servlet("debuggerCommand").command(id, command));
        assertEquals("closed", runtime.session(OWNER, id, TAB).get("status"));
        assertEquals(1L, runtime.session(OWNER, id, TAB).get("lastCommandSequence"));
    }

    @ParameterizedTest @ValueSource(strings = {"restrictions", "authorizer"})
    void channelControllerFailureRejectsReadsAndReleasesOwnedSession(String failingApi) throws Exception {
        String sourceId = sharedSource();
        String id = runtime.create(OWNER, A, Map.of("tabId", TAB)).get("id").toString();
        ControllerException unavailable = new ControllerException("Channel authorization unavailable");
        if (failingApi.equals("restrictions"))
            when(authorization.doesUserHaveChannelRestrictions(anyInt(), any())).thenThrow(unavailable);
        else when(authorization.getChannelAuthorizer(anyInt(), any())).thenThrow(unavailable);
        assertStatus(500, () -> servlet("debuggerSources").sources(A));
        assertStatus(500, () -> servlet("debuggerSource").source(sourceId, A, null, null));
        assertStatus(500, () -> servlet("debuggerSession").session(id, TAB));
        assertEquals("closed", runtime.session(OWNER, id, TAB).get("status"));
    }

    private String sharedSource() {
        return sharedSource(A, B);
    }

    private String sharedSource(String... observedChannels) {
        ContextFactory factory = new ContextFactory();
        runtime.attach(factory);
        String name = "channel-access-" + UUID.randomUUID();
        cachedSources.add(name);
        String text = "var sharedValue = 42; sharedValue;";
        Script script = factory.call(cx -> {
            cx.setOptimizationLevel(-1);
            return cx.compileString(text, name, 1, null);
        });
        CompiledScriptCache.getInstance().putCompiledScript(name, script, text);
        for (String channel : observedChannels) factory.call(cx -> {
            Scriptable scope = cx.initStandardObjects();
            scope.put("channelId", scope, channel);
            assertEquals(42.0, script.exec(cx, scope));
            return null;
        });
        return runtime.sources(observedChannels[0]).get(0).get("id").toString();
    }

    private Response sessionAction(String action, String id, String tab) {
        String body = Json.write(Map.of("tabId", tab, "action", action));
        return switch (action) {
            case "session" -> servlet("debuggerSession").session(id, tab);
            case "heartbeat" -> servlet("debuggerHeartbeat").heartbeat(id, body);
            case "close" -> servlet("debuggerClose").close(id, tab);
            case "breakpoints" -> servlet("debuggerBreakpoints").breakpoints(id, body);
            default -> servlet("debuggerCommand").command(id, body);
        };
    }

    private DebuggerServlet servlet(String name) {
        DebuggerServlet servlet = new DebuggerServlet(request, null, controllers, runtime);
        MirthOperation metadata = Arrays.stream(DebuggerServletInterface.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(MirthOperation.class))
                .filter(operation -> operation.name().equals(name)).findFirst().orElseThrow();
        servlet.setOperation(new Operation(name, name, Operation.ExecuteType.ASYNC, metadata.auditable()));
        return servlet;
    }

    private static Map<String, Object> body(Response response) {
        assertEquals(200, response.getStatus());
        return Json.read((String) response.getEntity());
    }

    private static void assertStatus(int expected, Supplier<Response> action) {
        try { assertEquals(expected, action.get().getStatus()); }
        catch (WebApplicationException denied) { assertEquals(expected, denied.getResponse().getStatus()); }
    }
}
