package org.openintegrationengine.debugger.api;

import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import com.mirth.connect.client.core.Operation;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.ServerEvent;
import com.mirth.connect.server.api.DontCheckAuthorized;
import com.mirth.connect.server.controllers.*;
import com.mirth.connect.server.util.CompiledScriptCache;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openintegrationengine.debugger.DebuggerPlugin;
import org.openintegrationengine.debugger.runtime.DebugRuntime;
import org.mockito.MockedStatic;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DebuggerServletTest {
    private static final String OWNER = "7:browser-session", TAB = "tab-a";
    private DebugRuntime runtime;
    private ControllerFactory controllers;
    private AuthorizationController authorization;
    private HttpServletRequest request;
    private Properties policy;
    private String id;

    @BeforeEach void setup() throws Exception {
        runtime = new DebugRuntime(); runtime.configure(true, true);
        controllers = mock(ControllerFactory.class);
        authorization = mock(AuthorizationController.class);
        when(controllers.createAuthorizationController()).thenReturn(authorization);
        ChannelController channels = mock(ChannelController.class);
        when(controllers.createChannelController()).thenReturn(channels);
        when(channels.getChannelById("channel-a")).thenReturn(new Channel());
        when(authorization.isUserAuthorized(anyInt(), any(), anyMap(), any(), anyBoolean())).thenReturn(true);
        request = mock(HttpServletRequest.class);
        HttpSession httpSession = mock(HttpSession.class);
        when(request.getSession()).thenReturn(httpSession); when(request.getSession(false)).thenReturn(httpSession);
        when(httpSession.getAttribute("authorized")).thenReturn(true); when(httpSession.getAttribute("user")).thenReturn(7);
        when(httpSession.getId()).thenReturn("browser-session");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        policy = enabledProperties(); applyPolicy();
        id = createSession("channel-a", Map.of("tabId", TAB, "evaluationEnabled", true));
    }

    @AfterEach void cleanup() {
        runtime.shutdown(); new DebuggerPlugin().init(enabledProperties()); DebugRuntime.getInstance().configure(false, false);
    }

    @ParameterizedTest @ValueSource(strings = {"allowedUserIds", "allowedChannelIds", "evaluationEnabled", "enabled"})
    void policyUpdateClosesAnArmAlreadyPastItsAuthorizationCheck(String restriction) throws Exception {
        DebuggerPlugin plugin = new DebuggerPlugin();
        DebugRuntime runtime = DebugRuntime.getInstance();
        plugin.init(enabledProperties());
        var workers = Executors.newFixedThreadPool(2);
        var validated = new CountDownLatch(1);
        var finishValidation = new CountDownLatch(1);
        try {
            AccessPolicy originalPolicy = DebuggerPlugin.policy();
            Map<String,Object> options = Map.of("tabId", "review-tab", "evaluationEnabled", true,
                    "requestId", UUID.randomUUID().toString(), "requestExpiresAt", runtime.status().get("armRequestExpiresAt"));
            Runnable authorize = () -> {
                AccessPolicy current = DebuggerPlugin.policy();
                if (!current.allows(7) || !current.channel("review-channel") || !current.evaluates(7))
                    throw new SecurityException("Denied");
            };
            var creating = workers.submit(() -> runtime.create("7:review-browser", "review-channel",
                    options, authorize,
                    () -> { validated.countDown(); try { assertTrue(finishValidation.await(3, TimeUnit.SECONDS)); }
                        catch (InterruptedException e) { throw new RuntimeException(e); } }));
            assertTrue(validated.await(3, TimeUnit.SECONDS));
            Properties revoked = enabledProperties();
            revoked.setProperty(restriction, restriction.equals("allowedUserIds") ? "8"
                    : restriction.equals("allowedChannelIds") ? "another-channel" : "false");
            var updating = workers.submit(() -> plugin.update(revoked));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (DebuggerPlugin.policy() == originalPolicy && System.nanoTime() < deadline) Thread.sleep(1);
            assertNotSame(originalPolicy, DebuggerPlugin.policy(), "New policy must be published before release");
            finishValidation.countDown();
            String id = creating.get(3, TimeUnit.SECONDS).get("id").toString();
            updating.get(3, TimeUnit.SECONDS);
            Map<String, Object> finalSession = runtime.session("7:review-browser", id, "review-tab");
            assertEquals("closed", finalSession.get("status"), "An old-policy Arm must not survive a completed settings change");
            assertThrows(DebugRuntime.ArmRequestRejected.class, () -> runtime.create("7:review-browser", "review-channel",
                    options, authorize, () -> fail("A denied recovery cannot validate source")));
        } finally {
            finishValidation.countDown();
            runtime.shutdown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
            plugin.init(enabledProperties());
            runtime.shutdown();
        }
    }

    @Test void overlappingPolicyUpdatesPublishInTheSameOrderAsRuntimeConfiguration() throws Exception {
        DebuggerPlugin plugin = new DebuggerPlugin();
        DebugRuntime runtime = DebugRuntime.getInstance();
        plugin.init(enabledProperties());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Properties first = enabledProperties(); first.setProperty("enabled", "false");
        Properties second = enabledProperties(); second.setProperty("evaluationEnabled", "false");
        AtomicReference<Thread> secondThread = new AtomicReference<>();
        CountDownLatch secondStarted = new CountDownLatch(1);
        try {
            Future<?> firstUpdate, secondUpdate;
            synchronized (runtime) {
                // Hold only the runtime application phase so the first policy is already visible.
                firstUpdate = workers.submit(() -> plugin.update(first));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (DebuggerPlugin.policy().enabled && System.nanoTime() < deadline) Thread.sleep(1);
                AccessPolicy firstPolicy = DebuggerPlugin.policy();
                assertFalse(firstPolicy.enabled);
                secondUpdate = workers.submit(() -> {
                    secondThread.set(Thread.currentThread()); secondStarted.countDown(); plugin.update(second);
                });
                assertTrue(secondStarted.await(3, TimeUnit.SECONDS));
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (secondThread.get().getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1);
                assertEquals(Thread.State.BLOCKED, secondThread.get().getState());
                assertSame(firstPolicy, DebuggerPlugin.policy(), "The next update must wait before publishing its policy");
            }
            firstUpdate.get(3, TimeUnit.SECONDS); secondUpdate.get(3, TimeUnit.SECONDS);
            assertTrue(DebuggerPlugin.policy().enabled); assertFalse(DebuggerPlugin.policy().evaluationEnabled);
            assertEquals(DebuggerPlugin.policy().enabled, runtime.status().get("enabled"));
            assertEquals(DebuggerPlugin.policy().evaluationEnabled, runtime.status().get("evaluationEnabled"));
        } finally {
            workers.shutdownNow(); assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
            plugin.init(enabledProperties()); runtime.shutdown();
        }
    }

    private static Properties enabledProperties() {
        Properties properties = new Properties();
        properties.setProperty("enabled", "true");
        return properties;
    }

    private static final java.util.concurrent.atomic.AtomicLong SEQUENCES = new java.util.concurrent.atomic.AtomicLong(100);

    private void applyPolicy() { new DebuggerPlugin().init(policy); }
    private String createSession(String channel, Map<String,Object> options) { return runtime.create(OWNER, channel, options).get("id").toString(); }
    private DebuggerServlet servlet(String operation) {
        DebuggerServlet servlet = new DebuggerServlet(request, null, controllers, runtime);
        var metadata = java.util.Arrays.stream(DebuggerServletInterface.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(com.mirth.connect.client.core.api.MirthOperation.class))
                .filter(annotation -> annotation != null && annotation.name().equals(operation)).findFirst().orElseThrow();
        servlet.setOperation(new Operation(operation, operation, Operation.ExecuteType.ASYNC, metadata.auditable()));
        return servlet;
    }
    private void denyOperations(String... names) throws Exception {
        Set<String> denied = Set.of(names);
        when(authorization.isUserAuthorized(anyInt(), any(), anyMap(), any(), anyBoolean())).thenAnswer(inv -> {
            String name = ((Operation) inv.getArgument(1)).getName();
            return denied.stream().noneMatch(deniedName -> name.equals(DebuggerServletInterface.PLUGIN_POINT + "#" + deniedName));
        });
    }
    private void nativeChannelRestriction(boolean permitsSelectedChannel) throws Exception {
        when(authorization.doesUserHaveChannelRestrictions(anyInt(), any())).thenReturn(true);
        when(authorization.getChannelAuthorizer(anyInt(), any())).thenReturn(channel -> permitsSelectedChannel);
    }
    private static Map<String,Object> body(Response response) { assertEquals(200, response.getStatus()); return Json.read((String) response.getEntity()); }
    private static void forbidden(Supplier<Response> action) {
        try { assertEquals(403, action.get().getStatus()); }
        catch (WebApplicationException denied) { assertEquals(403, denied.getResponse().getStatus()); }
    }
    private static Map<String,Object> rejectedArm(Response response, int status, Object requestId) {
        assertEquals(status, response.getStatus());
        Map<String,Object> result = Json.read((String) response.getEntity());
        assertEquals("arm_request_rejected", result.get("code")); assertEquals(requestId, result.get("requestId"));
        return result;
    }
    private static void forbiddenWithoutArmProof(Supplier<Response> action) {
        try {
            Response response = action.get(); assertEquals(403, response.getStatus());
            assertFalse(Json.read((String) response.getEntity()).containsKey("code"));
        } catch (WebApplicationException denied) {
            assertEquals(403, denied.getResponse().getStatus());
            assertFalse(String.valueOf(denied.getResponse().getEntity()).contains("arm_request_rejected"));
        }
    }
    private void assertClosed(String sessionId) { assertEquals("closed", runtime.session(OWNER, sessionId, TAB).get("status")); }
    private void assertArmed(String sessionId) { assertEquals("armed", runtime.session(OWNER, sessionId, TAB).get("status")); }
    private Response sessionAction(String operation, String sessionId) {
        DebuggerServlet api = servlet(operation);
        return switch (operation) {
            case "debuggerSession" -> api.session(sessionId, TAB);
            case "debuggerHeartbeat" -> api.heartbeat(sessionId, "{\"tabId\":\"tab-a\"}");
            case "debuggerClose" -> api.close(sessionId, TAB);
            case "debuggerCommand" -> api.command(sessionId, "{\"tabId\":\"tab-a\",\"commandId\":\"command-a\",\"commandSequence\":1,\"expectedPauseGeneration\":0,\"action\":\"pause\"}");
            case "debuggerBreakpoints" -> api.breakpoints(sessionId, "{\"tabId\":\"tab-a\",\"sourceId\":\"unknown-source\",\"lines\":[]}");
            default -> throw new IllegalArgumentException(operation);
        };
    }

    @Test void defaultPolicyAdminUsesNativePermissionsWithoutExtraAllowlistConfiguration() throws Exception {
        Map<String,Object> capabilities = body(servlet("debuggerCapabilities").capabilities());
        assertEquals(true, capabilities.get("controlAllowed")); assertEquals(true, capabilities.get("evaluationAllowed")); assertEquals(true, capabilities.get("globalAllowed"));
        assertEquals(200, servlet("debuggerSession").session(id, TAB).getStatus());
        verify(authorization, atLeastOnce()).isUserAuthorized(eq(7), argThat(op -> op.getName().equals(DebuggerServletInterface.PLUGIN_POINT + "#debuggerCommand")), anyMap(), any(), anyBoolean());
        verify(authorization, atLeastOnce()).isUserAuthorized(eq(7), argThat(op -> op.getName().equals(DebuggerServletInterface.PLUGIN_POINT + "#debuggerEvaluate")), anyMap(), any(), anyBoolean());
    }

    @Test void persistedEmptyListsKeepAdminCapabilitiesAndSessionAccess() {
        policy.setProperty("allowedUserIds", ""); policy.setProperty("evaluationUserIds", " , ");
        policy.setProperty("globalUserIds", ""); policy.setProperty("allowedChannelIds", " "); applyPolicy();
        Map<String,Object> capabilities = body(servlet("debuggerCapabilities").capabilities());
        assertEquals(true, capabilities.get("controlAllowed")); assertEquals(true, capabilities.get("evaluationAllowed")); assertEquals(true, capabilities.get("globalAllowed"));
        assertEquals(200, servlet("debuggerSession").session(id, TAB).getStatus());
    }

    @Test void defaultPolicyCanArmThroughAuthenticatedNativeAuthorization() {
        try (MockedStatic<DebuggerPlugin> plugin = mockStatic(DebuggerPlugin.class, CALLS_REAL_METHODS)) {
            plugin.when(DebuggerPlugin::unavailableReason).thenReturn(null);
            Response response = servlet("debuggerCreate").create("{\"channelId\":\"channel-a\",\"tabId\":\"new-tab\",\"evaluationEnabled\":true}");
            assertEquals("armed", body(response).get("status"));
        }
    }

    private Map<String,Object> armRequest() {
        return new java.util.LinkedHashMap<>(Map.of("channelId", "channel-a", "tabId", TAB,
                "requestId", UUID.randomUUID().toString(), "requestExpiresAt", runtime.status().get("armRequestExpiresAt")));
    }

    @Test void armRecoveryReturnsSameSessionAndRejectsChangedRequest() {
        try (MockedStatic<DebuggerPlugin> plugin = mockStatic(DebuggerPlugin.class, CALLS_REAL_METHODS)) {
            plugin.when(DebuggerPlugin::unavailableReason).thenReturn(null);
            Map<String,Object> options = armRequest();
            DebuggerServlet api = servlet("debuggerCreate");
            String recoveredId = body(api.create(Json.write(options))).get("id").toString();
            assertEquals(recoveredId, body(api.create(Json.write(options))).get("id"));
            options.put("breakOnEntry", false);
            Response changed = api.create(Json.write(options)); assertEquals(409, changed.getStatus());
            assertFalse(Json.read((String) changed.getEntity()).containsKey("code"));
            options.remove("breakOnEntry");
            runtime.close(OWNER, recoveredId, TAB);
            assertEquals("closed", body(api.create(Json.write(options))).get("status"));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"debuggerCreate", "debuggerSession", "debuggerCommand", "debuggerEvaluate"})
    void armRecoveryRevocationClosesOnlyTheMatchingOwnedLease(String operation) throws Exception {
        try (MockedStatic<DebuggerPlugin> plugin = mockStatic(DebuggerPlugin.class, CALLS_REAL_METHODS)) {
            plugin.when(DebuggerPlugin::unavailableReason).thenReturn(null);
            Map<String,Object> options = armRequest(); options.put("evaluationEnabled", true);
            DebuggerServlet api = servlet("debuggerCreate");
            String recoveredId = body(api.create(Json.write(options))).get("id").toString();
            denyOperations(operation);
            rejectedArm(api.create(Json.write(options)), 403, options.get("requestId"));
            assertClosed(recoveredId); assertArmed(id);
            Map<String,Object> fresh = armRequest(); fresh.put("evaluationEnabled", true);
            forbiddenWithoutArmProof(() -> api.create(Json.write(fresh)));
            assertEquals(1L, runtime.status().get("activeSessions"));
        }
    }

    @Test void armRecoveryDeniedForeignOwnerOrTabCannotCloseOriginalLease() throws Exception {
        try (MockedStatic<DebuggerPlugin> plugin = mockStatic(DebuggerPlugin.class, CALLS_REAL_METHODS)) {
            plugin.when(DebuggerPlugin::unavailableReason).thenReturn(null);
            Map<String,Object> options = armRequest();
            String recoveredId = body(servlet("debuggerCreate").create(Json.write(options))).get("id").toString();
            denyOperations("debuggerCreate");
            when(request.getSession().getId()).thenReturn("foreign-browser");
            forbiddenWithoutArmProof(() -> servlet("debuggerCreate").create(Json.write(options)));
            assertArmed(recoveredId);
            when(request.getSession().getId()).thenReturn("browser-session");
            options.put("tabId", "foreign-tab");
            forbiddenWithoutArmProof(() -> servlet("debuggerCreate").create(Json.write(options)));
            assertArmed(recoveredId);
        }
    }

    @Test void expiredLoginRejectsRecoveryBeforeItsOwnedLeaseCanBeClosed() {
        Map<String,Object> options = armRequest();
        String recoveredId = createSession("channel-a", options);
        when(request.getSession().getAttribute("authorized")).thenReturn(false);
        WebApplicationException denied = assertThrows(WebApplicationException.class,
                () -> servlet("debuggerCreate").create(Json.write(options)));
        assertEquals(401, denied.getResponse().getStatus());
        assertFalse(String.valueOf(denied.getResponse().getEntity()).contains("arm_request_rejected"));
        assertArmed(recoveredId); // The client must retain an uncertain Arm after this pre-invocation failure.
    }

    @ParameterizedTest @ValueSource(longs = {Long.MIN_VALUE, Long.MAX_VALUE})
    void armRejectionProofRequiresTheOriginalUnchangedRequest(long changedDeadline) throws Exception {
        try (MockedStatic<DebuggerPlugin> plugin = mockStatic(DebuggerPlugin.class, CALLS_REAL_METHODS)) {
            plugin.when(DebuggerPlugin::unavailableReason).thenReturn(null);
            Map<String,Object> options = armRequest();
            String recoveredId = body(servlet("debuggerCreate").create(Json.write(options))).get("id").toString();
            options.put("requestExpiresAt", changedDeadline);
            denyOperations("debuggerCreate");
            forbiddenWithoutArmProof(() -> servlet("debuggerCreate").create(Json.write(options)));
            assertClosed(recoveredId);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"debuggerCreate", "debuggerSession", "debuggerCommand", "debuggerEvaluate", "deleted-channel", "unavailable"})
    void expiredAbsentArmReturnsProofAfterPrerequisiteFailure(String failure) throws Exception {
        try (MockedStatic<DebuggerPlugin> plugin = mockStatic(DebuggerPlugin.class, CALLS_REAL_METHODS)) {
            plugin.when(DebuggerPlugin::unavailableReason).thenReturn(null);
            Map<String,Object> options = armRequest(); options.put("requestExpiresAt", Long.MIN_VALUE); options.put("evaluationEnabled", true);
            int status = 403;
            if (failure.equals("deleted-channel")) {
                when(controllers.createChannelController().getChannelById("channel-a")).thenReturn(null); status = 400;
            } else if (failure.equals("unavailable")) {
                plugin.when(DebuggerPlugin::unavailableReason).thenReturn("Debugger unavailable"); status = 409;
            } else denyOperations(failure);
            rejectedArm(servlet("debuggerCreate").create(Json.write(options)), status, options.get("requestId"));
            verify(authorization, atLeastOnce()).isUserAuthorized(eq(7), any(), anyMap(), any(), eq(false));
            verify(authorization).auditAuthorizationRequest(eq(7), any(), anyMap(),
                    eq(com.mirth.connect.model.ServerEvent.Outcome.FAILURE), any());
            assertArmed(id); assertEquals(1L, runtime.status().get("activeSessions"));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void expiredEvictedArmReturnsProofAfterRevocationOrChannelDeletion(boolean deletedChannel) throws Exception {
        try (MockedStatic<DebuggerPlugin> plugin = mockStatic(DebuggerPlugin.class, CALLS_REAL_METHODS)) {
            plugin.when(DebuggerPlugin::unavailableReason).thenReturn(null);
            Map<String,Object> options = armRequest();
            long expiry = TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) + 1_000;
            options.put("requestExpiresAt", expiry);
            String recoveredId = body(servlet("debuggerCreate").create(Json.write(options))).get("id").toString();
            runtime.close(OWNER, recoveredId, TAB);
            Thread.sleep(Math.max(1, expiry - TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) + 1));
            var sessions = DebugRuntime.class.getDeclaredField("sessions"); sessions.setAccessible(true);
            Object retained = ((Map<?,?>) sessions.get(runtime)).get(recoveredId);
            var closedAt = retained.getClass().getDeclaredField("closedAt"); closedAt.setAccessible(true);
            synchronized (retained) { closedAt.setLong(retained, TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - 60_001); }
            if (deletedChannel) when(controllers.createChannelController().getChannelById("channel-a")).thenReturn(null);
            else denyOperations("debuggerCommand");
            rejectedArm(servlet("debuggerCreate").create(Json.write(options)), deletedChannel ? 400 : 403, options.get("requestId"));
            assertThrows(SecurityException.class, () -> runtime.session(OWNER, recoveredId, TAB));
            assertArmed(id); assertEquals(1L, runtime.status().get("activeSessions"));
        }
    }

    @Test void armRecoveryRechecksChannelAndLifecycleRestrictions() throws Exception {
        try (MockedStatic<DebuggerPlugin> plugin = mockStatic(DebuggerPlugin.class, CALLS_REAL_METHODS)) {
            plugin.when(DebuggerPlugin::unavailableReason).thenReturn(null);
            Map<String,Object> options = armRequest(); options.put("includeLifecycle", true);
            String recoveredId = body(servlet("debuggerCreate").create(Json.write(options))).get("id").toString();
            nativeChannelRestriction(true);
            String lifecycleBody = Json.write(options);
            forbidden(() -> servlet("debuggerCreate").create(lifecycleBody)); assertClosed(recoveredId);
            options = armRequest();
            String channelSession = body(servlet("debuggerCreate").create(Json.write(options))).get("id").toString();
            nativeChannelRestriction(false);
            String channelBody = Json.write(options);
            forbidden(() -> servlet("debuggerCreate").create(channelBody)); assertClosed(channelSession);
        }
    }

    @Test void armReceiptSurvivesSourceInvalidationButFreshCreateStillValidatesSource() throws Exception {
        try (PausedScript paused = new PausedScript(false);
                MockedStatic<DebuggerPlugin> plugin = mockStatic(DebuggerPlugin.class, CALLS_REAL_METHODS)) {
            plugin.when(DebuggerPlugin::unavailableReason).thenReturn(null);
            Map<String,Object> options = armRequest(); options.put("sourceId", paused.sourceId);
            String recoveredId = body(servlet("debuggerCreate").create(Json.write(options))).get("id").toString();
            CompiledScriptCache.getInstance().removeCompiledScript(paused.sourceName);
            runtime.invalidate("channel-a", "Source replaced");
            Map<String,Object> recovered = body(servlet("debuggerCreate").create(Json.write(options)));
            assertEquals(recoveredId, recovered.get("id")); assertEquals("closed", recovered.get("status"));
            options.put("requestId", UUID.randomUUID().toString());
            assertNotEquals(200, servlet("debuggerCreate").create(Json.write(options)).getStatus());
            assertEquals(0L, runtime.status().get("activeSessions"));
        }
    }

    @Test void armRejectsMalformedOrExpiredRecoveryFieldsWithoutCreating() {
        try (MockedStatic<DebuggerPlugin> plugin = mockStatic(DebuggerPlugin.class, CALLS_REAL_METHODS)) {
            plugin.when(DebuggerPlugin::unavailableReason).thenReturn(null);
            Map<String,Object> options = armRequest(); options.put("requestId", 42);
            Response malformed = servlet("debuggerCreate").create(Json.write(options)); assertEquals(400, malformed.getStatus());
            assertFalse(Json.read((String) malformed.getEntity()).containsKey("code"));
            options.put("requestId", "request"); options.remove("requestExpiresAt");
            assertEquals(400, servlet("debuggerCreate").create(Json.write(options)).getStatus());
            options.put("requestExpiresAt", 0);
            rejectedArm(servlet("debuggerCreate").create(Json.write(options)), 409, "request");
            options.put("requestExpiresAt", Long.MAX_VALUE);
            Response future = servlet("debuggerCreate").create(Json.write(options)); assertEquals(409, future.getStatus());
            assertFalse(Json.read((String) future.getEntity()).containsKey("code"));
            assertEquals(1L, runtime.status().get("activeSessions"));
        }
    }

    @Test void explicitPluginUserRestrictionStillOverridesPermissiveNativeAuthorization() {
        policy.setProperty("allowedUserIds", "8"); applyPolicy();
        forbidden(() -> servlet("debuggerCapabilities").capabilities());
        forbidden(() -> servlet("debuggerSession").session(id, TAB)); assertClosed(id);
    }

    @Test void nativeViewDenialIsNotBypassedByEmptyPolicy() throws Exception {
        denyOperations("debuggerCapabilities", "debuggerSources");
        forbidden(() -> servlet("debuggerCapabilities").capabilities());
        forbidden(() -> servlet("debuggerSources").sources("channel-a")); assertArmed(id);
    }

    @ParameterizedTest @ValueSource(strings = {"debuggerSession", "debuggerHeartbeat", "debuggerClose", "debuggerCommand", "debuggerBreakpoints"})
    void revokingTheRequestedNativeOperationImmediatelyClosesOnlyOwnedSession(String operation) throws Exception {
        denyOperations(operation); forbidden(() -> sessionAction(operation, id)); assertClosed(id);
    }

    @ParameterizedTest @ValueSource(strings = {"debuggerSession", "debuggerHeartbeat", "debuggerClose"})
    void readHeartbeatAndCloseAlsoRequireCurrentNativeControlPermission(String operation) throws Exception {
        denyOperations("debuggerCommand"); forbidden(() -> sessionAction(operation, id)); assertClosed(id);
    }

    @ParameterizedTest @ValueSource(strings = {"debuggerHeartbeat", "debuggerCommand"})
    void retainingControlCannotRenewOrOperateSessionAfterViewPermissionRevocation(String operation) throws Exception {
        denyOperations("debuggerSession"); forbidden(() -> sessionAction(operation, id)); assertClosed(id);
    }

    @Test void controlOnlyUserCannotArmSessionWithoutNativeViewPermission() throws Exception {
        denyOperations("debuggerSession");
        try (MockedStatic<DebuggerPlugin> plugin = mockStatic(DebuggerPlugin.class, CALLS_REAL_METHODS)) {
            plugin.when(DebuggerPlugin::unavailableReason).thenReturn(null);
            forbidden(() -> servlet("debuggerCreate").create("{\"channelId\":\"channel-a\",\"tabId\":\"new-tab\"}"));
        }
        assertEquals(1L, runtime.status().get("activeSessions"));
    }

    @Test void nativeControlDenialDisablesControlAndGlobalCapability() throws Exception {
        denyOperations("debuggerCommand");
        Map<String,Object> capabilities = body(servlet("debuggerCapabilities").capabilities());
        assertEquals(false, capabilities.get("controlAllowed")); assertEquals(false, capabilities.get("globalAllowed"));
    }

    @Test void nativeEvaluationDenialKeepsReadOnlyControlAvailable() throws Exception {
        denyOperations("debuggerEvaluate");
        Map<String,Object> capabilities = body(servlet("debuggerCapabilities").capabilities());
        assertEquals(true, capabilities.get("controlAllowed")); assertEquals(false, capabilities.get("evaluationAllowed"));
        String readOnly = createSession("channel-a", Map.of("tabId", TAB));
        assertEquals(200, servlet("debuggerSession").session(readOnly, TAB).getStatus());
        try (MockedStatic<DebuggerPlugin> plugin = mockStatic(DebuggerPlugin.class, CALLS_REAL_METHODS)) {
            plugin.when(DebuggerPlugin::unavailableReason).thenReturn(null);
            forbidden(() -> servlet("debuggerCreate").create("{\"channelId\":\"channel-a\",\"tabId\":\"new-tab\",\"evaluationEnabled\":true}"));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"debuggerSession", "debuggerHeartbeat"})
    void evaluationPermissionRevocationReleasesExistingElevatedSession(String operation) throws Exception {
        denyOperations("debuggerEvaluate"); forbidden(() -> sessionAction(operation, id)); assertClosed(id);
    }

    @Test void explicitEvaluationKillSwitchPreservesBasicControlAndReleasesElevatedSession() {
        policy.setProperty("evaluationEnabled", "false"); applyPolicy();
        Map<String,Object> capabilities = body(servlet("debuggerCapabilities").capabilities());
        assertEquals(true, capabilities.get("controlAllowed")); assertEquals(false, capabilities.get("evaluationAllowed"));
        forbidden(() -> servlet("debuggerSession").session(id, TAB)); assertClosed(id);
        String readOnly = createSession("channel-a", Map.of("tabId", TAB));
        assertEquals(200, servlet("debuggerSession").session(readOnly, TAB).getStatus());
    }

    @Test void pluginChannelRestrictionRevocationClosesOwnedSession() {
        policy.setProperty("allowedChannelIds", "channel-b"); applyPolicy();
        forbidden(() -> servlet("debuggerSession").session(id, TAB)); assertClosed(id);
    }

    @Test void nativeChannelRestrictionRevocationClosesOwnedSession() throws Exception {
        nativeChannelRestriction(false); forbidden(() -> servlet("debuggerSession").session(id, TAB)); assertClosed(id);
    }

    @Test void nativeChannelRestrictedUserCannotArmGlobalOrLifecycleEvenForPermittedChannel() throws Exception {
        nativeChannelRestriction(true);
        Map<String,Object> capabilities = body(servlet("debuggerCapabilities").capabilities());
        assertEquals(true, capabilities.get("controlAllowed")); assertEquals(false, capabilities.get("globalAllowed"));
        try (MockedStatic<DebuggerPlugin> plugin = mockStatic(DebuggerPlugin.class, CALLS_REAL_METHODS)) {
            plugin.when(DebuggerPlugin::unavailableReason).thenReturn(null);
            forbidden(() -> servlet("debuggerCreate").create("{\"channelId\":\"global\",\"tabId\":\"new-tab\",\"includeGlobal\":true}"));
            forbidden(() -> servlet("debuggerCreate").create("{\"channelId\":\"channel-a\",\"tabId\":\"new-tab\",\"includeLifecycle\":true}"));
        }
    }

    @Test void explicitGlobalRestrictionDisablesGlobalCapabilityAndLifecycleCreation() {
        policy.setProperty("globalUserIds", "8"); applyPolicy();
        assertEquals(false, body(servlet("debuggerCapabilities").capabilities()).get("globalAllowed"));
        try (MockedStatic<DebuggerPlugin> plugin = mockStatic(DebuggerPlugin.class, CALLS_REAL_METHODS)) {
            plugin.when(DebuggerPlugin::unavailableReason).thenReturn(null);
            forbidden(() -> servlet("debuggerCreate").create("{\"channelId\":\"channel-a\",\"tabId\":\"new-tab\",\"includeLifecycle\":true}"));
        }
    }

    @Test void pluginChannelAllowlistDoesNotReplaceIndependentGlobalPolicy() {
        policy.setProperty("allowedChannelIds", "channel-a"); applyPolicy();
        assertEquals(true, body(servlet("debuggerCapabilities").capabilities()).get("globalAllowed"));
    }

    @ParameterizedTest @ValueSource(strings = {"global", "channel-a"})
    void globalPolicyRevocationIsRecheckedForExistingGlobalAndLifecycleSessions(String channel) {
        String sessionId = createSession(channel, Map.of("tabId", TAB, "includeGlobal", true, "includeLifecycle", true));
        policy.setProperty("globalUserIds", "8"); applyPolicy();
        forbidden(() -> servlet("debuggerHeartbeat").heartbeat(sessionId, "{\"tabId\":\"tab-a\"}")); assertClosed(sessionId);
    }

    @ParameterizedTest @ValueSource(strings = {"global", "channel-a"})
    void nativeChannelRestrictionsAreRecheckedForExistingGlobalAndLifecycleSessions(String channel) throws Exception {
        String sessionId = createSession(channel, Map.of("tabId", TAB, "includeGlobal", true, "includeLifecycle", true));
        nativeChannelRestriction(true); forbidden(() -> servlet("debuggerSession").session(sessionId, TAB)); assertClosed(sessionId);
    }

    @Test void sessionNeedsMatchingBrowserAndTabEvenForReads() {
        assertEquals(400, servlet("debuggerSession").session(id, null).getStatus());
        forbidden(() -> servlet("debuggerSession").session(id, "another-tab")); assertArmed(id);
        assertEquals(200, servlet("debuggerSession").session(id, TAB).getStatus());
        when(request.getSession().getId()).thenReturn("another-browser");
        forbidden(() -> servlet("debuggerSession").session(id, TAB)); assertArmed(id);
    }

    @Test void deniedForeignUserOrTabCannotCloseAnotherOwnersSession() throws Exception {
        denyOperations("debuggerSession", "debuggerCommand");
        forbidden(() -> servlet("debuggerSession").session(id, "another-tab")); assertArmed(id);
        when(request.getSession().getAttribute("user")).thenReturn(8);
        forbidden(() -> servlet("debuggerSession").session(id, TAB)); assertArmed(id);
    }

    @Test void ownedSessionMethodsReachTheirOwnRevocationCleanupBeforeEngineAuthorizationShortCircuit() throws Exception {
        // The engine inspects the concrete matching server method for this annotation.
        for (var method : DebuggerServletInterface.class.getDeclaredMethods()) {
            if (Set.of("create", "session", "heartbeat", "close", "breakpoints", "command").contains(method.getName()))
                assertNotNull(DebuggerServlet.class.getMethod(method.getName(), method.getParameterTypes()).getAnnotation(DontCheckAuthorized.class), method.getName());
        }
    }

    @Test void allRestOperationsHaveAnExplicitRegisteredPermission() {
        Set<String> mapped = new java.util.HashSet<>();
        for (var permission : new DebuggerPlugin().getExtensionPermissions()) mapped.addAll(java.util.Arrays.asList(permission.getOperationNames()));
        for (var method : DebuggerServletInterface.class.getDeclaredMethods()) {
            var operation = method.getAnnotation(com.mirth.connect.client.core.api.MirthOperation.class);
            assertNotNull(operation); assertTrue(mapped.contains(operation.name()), operation.name());
        }
        assertTrue(mapped.contains("debuggerEvaluate"));
    }

    @Test void commandAndSourceBodiesNeverUseEngineXstreamWrapping() {
        Response response = sessionAction("debuggerCommand", id);
        assertEquals("completed", body(response).get("status")); assertEquals("no-store", response.getHeaderString("Cache-Control"));
    }

    @ParameterizedTest @ValueSource(strings = {"eval", "function"})
    void dynamicSourceRequiresTheOwningLivePauseAndDoesNotEnterTheChannelSourceList(String kind) throws Exception {
        try (PausedScript paused = new PausedScript(kind)) {
            assertEquals(true, runtime.source(paused.sourceId).get("dynamic"));
            assertEquals(409, servlet("debuggerSource").source(paused.sourceId, "channel-a", null, null).getStatus());
            Map<String,Object> source = body(servlet("debuggerSource").source(paused.sourceId, "channel-a", paused.sessionId, TAB));
            assertTrue(source.get("text").toString().contains("dynamicValue"));
            assertEquals(List.of(), source.get("authoredMappings"));
            assertFalse(servlet("debuggerSources").sources("channel-a").getEntity().toString().contains(paused.sourceId));

            forbidden(() -> servlet("debuggerSource").source(paused.sourceId, "channel-a", paused.sessionId, "another-tab"));
            assertEquals("paused", runtime.session(OWNER, paused.sessionId, TAB).get("status"));
            String unrelated = createSession("channel-a", Map.of("tabId", TAB));
            assertEquals(409, servlet("debuggerSource").source(paused.sourceId, "channel-a", unrelated, TAB).getStatus());
            when(request.getSession().getId()).thenReturn("another-browser");
            forbidden(() -> servlet("debuggerSource").source(paused.sourceId, "channel-a", paused.sessionId, TAB));
            when(request.getSession().getId()).thenReturn("browser-session");
            paused.resume();
            assertEquals(409, servlet("debuggerSource").source(paused.sourceId, "channel-a", paused.sessionId, TAB).getStatus());
        }
    }

    @Test void dynamicSourcePermissionRevocationClosesItsOwnedPause() throws Exception {
        try (PausedScript paused = new PausedScript(true)) {
            denyOperations("debuggerCommand");
            forbidden(() -> servlet("debuggerSource").source(paused.sourceId, "channel-a", paused.sessionId, TAB));
            assertClosed(paused.sessionId);
            assertEquals(5.0, paused.result.get(2, TimeUnit.SECONDS));
        }
    }

    @Test void dynamicBreakpointsUseTheCommandReceiptAndRequireItsLiveOwnedPause() throws Exception {
        try (PausedScript paused = new PausedScript(true)) {
            Map<String,Object> source = runtime.source(paused.sourceId);
            Map<String,Object> update = new java.util.LinkedHashMap<>(Map.of("tabId", TAB,
                    "commandId", UUID.randomUUID().toString(), "commandSequence", SEQUENCES.incrementAndGet(), "action", "setBreakpoints",
                    "expectedPauseGeneration", paused.snapshot.get("pauseGeneration"), "sourceId", paused.sourceId,
                    "sourceHash", source.get("hash"), "sourceGeneration", source.get("generation"), "lines", List.of()));
            Map<String,Object> receipt = body(servlet("debuggerCommand").command(paused.sessionId, Json.write(update)));
            assertEquals("completed", receipt.get("status"));
            assertEquals(paused.sourceId, ((Map<?,?>)receipt.get("result")).get("sourceId"));
            assertEquals(receipt, body(servlet("debuggerCommand").command(paused.sessionId, Json.write(update))));
            Map<String,Object> original = Map.copyOf(update);
            update.put("commandId", UUID.randomUUID().toString()); update.put("commandSequence", SEQUENCES.incrementAndGet());
            Map<String,Object> legacy = Map.copyOf(update);
            Map<String,Object> legacyReceipt = body(servlet("debuggerBreakpoints").breakpoints(paused.sessionId, Json.write(legacy)));
            assertEquals("completed", legacyReceipt.get("status"));
            paused.resume();
            assertEquals(receipt, body(servlet("debuggerCommand").command(paused.sessionId, Json.write(original))),
                    "A lost applied response remains reconcilable after the dynamic frame resumes");
            assertEquals(legacyReceipt, body(servlet("debuggerBreakpoints").breakpoints(paused.sessionId, Json.write(legacy))));
            update.put("commandId", UUID.randomUUID().toString()); update.put("commandSequence", SEQUENCES.incrementAndGet());
            assertEquals(409, servlet("debuggerCommand").command(paused.sessionId, Json.write(update)).getStatus());
            assertEquals(409, servlet("debuggerBreakpoints").breakpoints(paused.sessionId, Json.write(update)).getStatus());
        }
    }

    @Test void pausedDeployedSourceCannotBypassAReplacementCacheIdentity() throws Exception {
        try (PausedScript paused = new PausedScript(false)) {
            assertEquals(false, runtime.source(paused.sourceId).get("dynamic"));
            assertEquals(200, servlet("debuggerSource").source(paused.sourceId, "channel-a", null, null).getStatus());
            Map<String,Object> source = runtime.source(paused.sourceId);
            Map<String,Object> update = new java.util.LinkedHashMap<>(Map.of("tabId", TAB,
                    "commandId", UUID.randomUUID().toString(), "commandSequence", SEQUENCES.incrementAndGet(), "action", "setBreakpoints",
                    "expectedPauseGeneration", paused.snapshot.get("pauseGeneration"), "sourceId", paused.sourceId,
                    "sourceHash", source.get("hash"), "sourceGeneration", source.get("generation"), "lines", List.of()));
            Map<String,Object> applied = body(servlet("debuggerCommand").command(paused.sessionId, Json.write(update)));
            Script replacement = new ContextFactory().call(cx -> { cx.setOptimizationLevel(-1); return cx.compileString("2;", paused.sourceName, 1, null); });
            CompiledScriptCache.getInstance().putCompiledScript(paused.sourceName, replacement, "2;");
            assertEquals(409, servlet("debuggerSource").source(paused.sourceId, "channel-a", paused.sessionId, TAB).getStatus());
            assertEquals(applied, body(servlet("debuggerCommand").command(paused.sessionId, Json.write(update))),
                    "An applied receipt survives replacement of the source it modified");
            update.put("commandId", UUID.randomUUID().toString()); update.put("commandSequence", SEQUENCES.incrementAndGet());
            assertEquals(409, servlet("debuggerCommand").command(paused.sessionId, Json.write(update)).getStatus());
            CompiledScriptCache.getInstance().removeCompiledScript(paused.sourceName);
            assertEquals(409, servlet("debuggerSource").source(paused.sourceId, "channel-a", paused.sessionId, TAB).getStatus());
            paused.resume();
        }
    }

    private final class PausedScript implements AutoCloseable {
        final ExecutorService workers = Executors.newSingleThreadExecutor();
        final String sourceName = "api-source-" + UUID.randomUUID();
        final String sessionId;
        final Future<Object> result;
        final Map<String,Object> snapshot;
        final String sourceId;

        PausedScript(boolean dynamic) throws Exception { this(dynamic ? "eval" : "cached"); }

        PausedScript(String kind) throws Exception {
            runtime.close(OWNER, id, TAB);
            ContextFactory factory = new ContextFactory();
            runtime.attach(factory);
            String text = switch (kind) {
                case "eval" -> "eval(\"var dynamicValue=4;\\ndebugger;\\ndynamicValue+1;\");";
                case "function" -> "(new Function(\"var dynamicValue=4;\\ndebugger;\\nreturn dynamicValue+1;\"))();";
                default -> "debugger;\n4;";
            };
            Script script = factory.call(cx -> { cx.setOptimizationLevel(-1); return cx.compileString(text, sourceName, 1, null); });
            CompiledScriptCache.getInstance().putCompiledScript(sourceName, script, text);
            sessionId = createSession("channel-a", Map.of("tabId", TAB, "breakOnEntry", false, "includeLifecycle", true));
            result = workers.submit(() -> factory.call(cx -> {
                cx.setOptimizationLevel(-1);
                Scriptable scope = cx.initStandardObjects();
                scope.put("channelId", scope, "channel-a");
                return script.exec(cx, scope);
            }));
            Map<String,Object> current;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            do {
                current = runtime.session(OWNER, sessionId, TAB);
                if ("paused".equals(current.get("status")) && !((List<?>)current.get("frames")).isEmpty()) break;
                Thread.sleep(5);
            } while (System.nanoTime() < deadline);
            snapshot = current;
            if (!"paused".equals(snapshot.get("status")) || ((List<?>)snapshot.get("frames")).isEmpty()) {
                close();
                fail("Expected a live script pause: " + snapshot);
            }
            sourceId = ((Map<?,?>)((List<?>)snapshot.get("frames")).get(0)).get("sourceId").toString();
        }

        void resume() throws Exception {
            runtime.command(OWNER, sessionId, Map.of("tabId", TAB, "commandId", UUID.randomUUID().toString(), "commandSequence", SEQUENCES.incrementAndGet(),
                    "action", "continue", "expectedPauseGeneration", snapshot.get("pauseGeneration")));
            result.get(2, TimeUnit.SECONDS);
        }

        @Override public void close() throws Exception {
            runtime.close(OWNER, sessionId, TAB);
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS), "Owned script worker must exit");
            CompiledScriptCache.getInstance().removeCompiledScript(sourceName);
        }
    }

    @Test void commandAndReceiptRetryAuditOnceEachWithActionAndWithoutPayload() throws Exception {
        List<Map<String, Object>> events = new java.util.ArrayList<>();
        doAnswer(invocation -> {
            events.add(new java.util.LinkedHashMap<>(invocation.getArgument(2)));
            assertEquals(com.mirth.connect.model.ServerEvent.Outcome.SUCCESS, invocation.getArgument(3));
            return null;
        }).when(authorization).auditAuthorizationRequest(anyInt(), any(), anyMap(), any(), any());
        String body = "{\"tabId\":\"tab-a\",\"commandId\":\"audit-command\",\"commandSequence\":1,"
                + "\"expectedPauseGeneration\":0,\"action\":\"pause\",\"expression\":\"PRIVATE MESSAGE\"}";
        assertEquals(200, servlet("debuggerCommand").command(id, body).getStatus());
        assertEquals(200, servlet("debuggerCommand").command(id, body).getStatus());
        assertEquals(2, events.size());
        for (Map<String, Object> event : events) {
            assertEquals("pause", event.get("action"));
            assertEquals(id, event.get("debugSessionId"));
            assertEquals("channel-a", event.get("channelId"));
            assertFalse(event.toString().contains("PRIVATE"));
            assertFalse(event.containsKey("tabId"));
            assertFalse(event.containsKey("body"));
        }
        verify(authorization, never()).isUserAuthorized(anyInt(), any(), anyMap(), any(), eq(true));
    }

    @ParameterizedTest @ValueSource(strings = {"continue", "inspect", "evaluate", "setValue", "setWatches", "stepOver", "PRIVATE UNKNOWN ACTION"})
    void failedCommandsAuditTheirAllowlistedActionOnce(String action) throws Exception {
        String body = Json.write(Map.of("tabId", TAB, "commandId", "invalid", "commandSequence", 1,
                "expectedPauseGeneration", 0, "action", action, "expressions", List.of(7)));
        assertTrue(servlet("debuggerCommand").command(id, body).getStatus() >= 400);
        verify(authorization).auditAuthorizationRequest(eq(7), any(), argThat(parameters ->
                (DebugRuntime.ACTIONS.contains(action) ? action : "invalid").equals(parameters.get("action"))
                && !parameters.toString().contains("PRIVATE")), eq(com.mirth.connect.model.ServerEvent.Outcome.FAILURE), any());
    }

    @Test void policyDenialAuditsFailureOnceAndClosesOnlyTheOwnedSession() throws Exception {
        policy.setProperty("allowedUserIds", "8"); applyPolicy();
        assertEquals(403, servlet("debuggerCommand").command(id, Json.write(Map.of("tabId", TAB,
                "commandId", "denied", "commandSequence", 1, "expectedPauseGeneration", 0, "action", "pause"))).getStatus());
        verify(authorization).auditAuthorizationRequest(eq(7), any(), anyMap(),
                eq(com.mirth.connect.model.ServerEvent.Outcome.FAILURE), any());
        assertClosed(id);
    }

    @ParameterizedTest @ValueSource(strings = {"global", "channel-a"})
    void targetsSurviveTheEngineAuditEncoderForArmCommandRetryAndClose(String target) throws Exception {
        runtime.close(OWNER, id, TAB);
        List<ServerEvent> events = captureEncodedAuditEvents();
        String options = Json.write(Map.of("channelId", target, "tabId", TAB, "includeGlobal", target.equals("global"),
                "requestId", UUID.randomUUID().toString(), "requestExpiresAt", runtime.status().get("armRequestExpiresAt")));
        String sessionId;
        try (MockedStatic<DebuggerPlugin> plugin = mockStatic(DebuggerPlugin.class, CALLS_REAL_METHODS)) {
            plugin.when(DebuggerPlugin::unavailableReason).thenReturn(null);
            sessionId = body(servlet("debuggerCreate").create(options)).get("id").toString();
            assertEquals(sessionId, body(servlet("debuggerCreate").create(options)).get("id"));
        }
        String command = Json.write(Map.of("tabId", TAB, "commandId", "audit-command", "commandSequence", 1,
                "expectedPauseGeneration", 0, "action", "pause", "expression", "PRIVATE MESSAGE"));
        assertEquals(200, servlet("debuggerCommand").command(sessionId, command).getStatus());
        assertEquals(200, servlet("debuggerCommand").command(sessionId, command).getStatus());
        assertEquals(200, servlet("debuggerClose").close(sessionId, TAB).getStatus());
        assertEquals(200, servlet("debuggerClose").close(sessionId, TAB).getStatus());
        assertEquals(List.of("arm", "arm", "pause", "pause", "close", "close"), events.stream()
                .map(event -> event.getAttributes().get("action").strip()).toList());
        for (ServerEvent event : events) assertAuditTarget(event, target, ServerEvent.Outcome.SUCCESS);
        assertEquals(sessionId, events.get(2).getAttributes().get("debugSessionId").strip());
    }

    @ParameterizedTest @ValueSource(strings = {"global", "channel-a"})
    void failedBreakpointCommandsRetainTheirEncodedTarget(String target) throws Exception {
        List<ServerEvent> events = captureEncodedAuditEvents();
        String sessionId = createSession(target, Map.of("tabId", TAB, "includeGlobal", target.equals("global")));
        String command = Json.write(Map.of("tabId", TAB, "commandId", "invalid", "commandSequence", 1,
                "expectedPauseGeneration", 0, "action", "setBreakpoints", "sourceId", "missing", "lines", List.of()));
        assertEquals(400, servlet("debuggerBreakpoints").breakpoints(sessionId, command).getStatus());
        assertEquals(400, servlet("debuggerCommand").command(sessionId, command).getStatus());
        assertEquals(2, events.size());
        for (ServerEvent event : events) {
            assertEquals("setBreakpoints", event.getAttributes().get("action").strip());
            assertAuditTarget(event, target, ServerEvent.Outcome.FAILURE);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"enabled", "debuggerSession", "debuggerCommand", "global"})
    void revokedGlobalPermissionsRetainTheEncodedTarget(String restriction) throws Exception {
        List<ServerEvent> events = captureEncodedAuditEvents();
        String sessionId = createSession("global", Map.of("tabId", TAB, "includeGlobal", true));
        if (restriction.equals("enabled")) { policy.setProperty("enabled", "false"); applyPolicy(); }
        else if (restriction.equals("global")) nativeChannelRestriction(true);
        else denyOperations(restriction);
        forbidden(() -> servlet("debuggerCommand").command(sessionId, Json.write(Map.of("tabId", TAB,
                "commandId", "denied", "commandSequence", 1, "expectedPauseGeneration", 0, "action", "pause"))));
        assertClosed(sessionId);
        assertEquals(1, events.size());
        assertAuditTarget(events.get(0), "global", ServerEvent.Outcome.FAILURE);
    }

    @Test void deniedGlobalArmRetainsItsEncodedTarget() throws Exception {
        List<ServerEvent> events = captureEncodedAuditEvents();
        nativeChannelRestriction(true);
        forbidden(() -> servlet("debuggerCreate").create(Json.write(Map.of("channelId", "global", "tabId", TAB,
                "includeGlobal", true))));
        assertEquals(1, events.size());
        assertAuditTarget(events.get(0), "global", ServerEvent.Outcome.FAILURE);
    }

    @Test void foreignSessionDenialDoesNotDiscloseItsTarget() throws Exception {
        List<ServerEvent> events = captureEncodedAuditEvents();
        String foreign = runtime.create("another-owner", "global", Map.of("tabId", TAB, "includeGlobal", true)).get("id").toString();
        forbidden(() -> servlet("debuggerClose").close(foreign, TAB));
        assertEquals(1, events.size());
        assertFalse(events.get(0).getAttributes().containsKey("debugTarget"));
        assertEquals("armed", runtime.session("another-owner", foreign, TAB).get("status"));
    }

    private List<ServerEvent> captureEncodedAuditEvents() throws Exception {
        List<ServerEvent> events = new java.util.ArrayList<>();
        EventController eventController = mock(EventController.class);
        doAnswer(call -> { events.add(call.getArgument(0)); return null; }).when(eventController).dispatchEvent(any());
        // Exercise the installed engine's encoder, including its special channelId lookup.
        for (var entry : Map.<String, Object>of("eventController", eventController,
                "channelController", controllers.createChannelController(), "serverId", "test-server").entrySet()) {
            var field = AuthorizationController.class.getDeclaredField(entry.getKey());
            field.setAccessible(true); field.set(authorization, entry.getValue());
        }
        doCallRealMethod().when(authorization).auditAuthorizationRequest(anyInt(), any(), anyMap(), any(), any());
        doAnswer(call -> {
            if ((boolean) call.getArgument(4)) authorization.auditAuthorizationRequest(call.getArgument(0),
                    call.getArgument(1), call.getArgument(2), ServerEvent.Outcome.SUCCESS, call.getArgument(3));
            return true;
        }).when(authorization).isUserAuthorized(anyInt(), any(), anyMap(), any(), anyBoolean());
        return events;
    }

    private void assertAuditTarget(ServerEvent event, String target, ServerEvent.Outcome outcome) throws Exception {
        assertEquals(outcome, event.getOutcome());
        assertEquals(target, event.getAttributes().getOrDefault("debugTarget", "").strip());
        assertFalse(event.getAttributes().toString().contains("PRIVATE"));
        assertFalse(event.getAttributes().containsKey("body"));
        assertFalse(event.getAttributes().containsKey("tabId"));
        verify(authorization, never()).isUserAuthorized(anyInt(), any(), anyMap(), any(), eq(true));
        if (target.equals("global")) assertFalse(event.getAttributes().containsKey("channel"));
        else assertTrue(event.getAttributes().containsKey("channel"), "Keep native channel auditing for real channels");
    }

    @Test void engineInvocationAuditsExcludeBodiesAndTabTokens() {
        for (var method : DebuggerServletInterface.class.getDeclaredMethods()) {
            java.lang.reflect.Method implementation;
            try { implementation = DebuggerServlet.class.getMethod(method.getName(), method.getParameterTypes()); }
            catch (NoSuchMethodException e) { throw new AssertionError(e); }
            for (var parameter : java.util.stream.Stream.concat(java.util.Arrays.stream(method.getParameters()), java.util.Arrays.stream(implementation.getParameters())).toList()) {
                var audit = parameter.getAnnotation(com.mirth.connect.client.core.api.Param.class);
                assertNotNull(audit, method.getName() + " must explicitly classify every argument for engine audit");
                if (audit.value().equals("body") || audit.value().equals("tabId")) assertTrue(audit.excludeFromAudit(), method.getName());
            }
        }
    }
}
