package org.openintegrationengine.debugger.api;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.core.Response;
import com.mirth.connect.client.core.ExtensionOperation;
import com.mirth.connect.client.core.Operation;
import com.mirth.connect.client.core.api.MirthApiException;
import com.mirth.connect.client.core.api.servlets.ExtensionServletInterface;
import com.mirth.connect.model.ExtensionPermission;
import com.mirth.connect.server.controllers.*;
import org.junit.jupiter.api.*;
import org.openintegrationengine.debugger.DebuggerPlugin;
import org.openintegrationengine.debugger.runtime.DebugRuntime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DebuggerSettingsTest {
    private static final String POINT = DebuggerServletInterface.PLUGIN_POINT;
    private final DebugRuntime runtime = DebugRuntime.getInstance();
    private final DebuggerPlugin plugin = new DebuggerPlugin();
    private final ControllerFactory controllers = mock(ControllerFactory.class);
    private final ConfigurationController settings = mock(ConfigurationController.class);
    private final AuthorizationController authorization = mock(AuthorizationController.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private Properties stored;
    private Set<String> denied = Set.of();

    @BeforeEach void setup() throws Exception {
        runtime.shutdown();
        stored = plugin.getDefaultProperties();
        plugin.init(stored);
        when(controllers.createConfigurationController()).thenReturn(settings);
        when(controllers.createAuthorizationController()).thenReturn(authorization);
        ExtensionController extensions = mock(ExtensionController.class);
        when(controllers.createExtensionController()).thenReturn(extensions);
        when(extensions.getServicePlugins()).thenReturn(Map.of(POINT, plugin));
        when(settings.getPropertiesForGroup(POINT)).thenAnswer(call -> stored.clone());
        doAnswer(call -> { stored.setProperty(call.getArgument(1), call.getArgument(2)); return null; })
                .when(settings).saveProperty(eq(POINT), anyString(), anyString());
        when(authorization.isUserAuthorized(anyInt(), any(), anyMap(), any(), anyBoolean()))
                .thenAnswer(call -> !denied.contains(((Operation) call.getArgument(1)).getName()));
        HttpSession session = mock(HttpSession.class);
        when(request.getSession()).thenReturn(session);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("authorized")).thenReturn(true);
        when(session.getAttribute("user")).thenReturn(7);
        when(session.getId()).thenReturn("settings-session");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
    }

    @AfterEach void cleanup() { runtime.shutdown(); }

    private DebuggerServlet servlet(String name) {
        DebuggerServlet servlet = new DebuggerServlet(request, null, controllers, runtime);
        servlet.setOperation(new Operation(name, name, Operation.ExecuteType.ASYNC, !name.equals("debuggerCapabilities")));
        return servlet;
    }
    private Response change(String json) { return servlet("debuggerSetEnabled").setEnabled(json); }
    private Map<String, Object> body(Response response) {
        assertEquals(200, response.getStatus(), String.valueOf(response.getEntity()));
        return Json.read((String) response.getEntity());
    }
    private String arm(String owner) {
        return runtime.create(owner, "channel-a", Map.of("tabId", "tab-a")).get("id").toString();
    }
    private void rbac(String... granted) throws Exception {
        Map<String, String> required = new HashMap<>();
        for (ExtensionPermission permission : plugin.getExtensionPermissions())
            for (String name : permission.getOperationNames())
                required.put(permission.getExtensionName() + "#" + name, permission.getDisplayName());
        Set<String> grants = Set.of(granted);
        doAnswer(call -> {
            String permission = required.get(((Operation) call.getArgument(1)).getName());
            if (permission == null || grants.contains(permission)) return true;
            throw new MirthApiException(Response.Status.FORBIDDEN);
        }).when(authorization).isUserAuthorized(anyInt(), any(), anyMap(), any(), anyBoolean());
    }
    private boolean engineSettings(String name) throws Exception {
        Operation operation = new ExtensionOperation(POINT, new Operation(name, name, Operation.ExecuteType.SYNC, true));
        return authorization.isUserAuthorized(7, operation, Map.of(), "127.0.0.1", false);
    }

    @Test void defaultIsEnabledAndExplicitSavedFalseIsRespected() {
        assertTrue(DebuggerPlugin.policy().enabled);
        stored.setProperty("enabled", "false"); plugin.init(stored);
        Map<String, Object> status = body(servlet("debuggerCapabilities").capabilities());
        assertEquals(false, status.get("configuredEnabled"));
        assertEquals(true, status.get("managementAllowed"));
        assertEquals(false, status.get("available"));
        assertEquals(false, status.get("controlAllowed"));
        assertEquals(true, body(change("{\"enabled\":true}")).get("configuredEnabled"));
    }

    @Test void managePermissionIsRequiredEvenWithDebuggerViewAndControl() {
        denied = Set.of(POINT + "#debuggerSetEnabled");
        assertEquals(false, body(servlet("debuggerCapabilities").capabilities()).get("managementAllowed"));
        assertEquals(403, change("{\"enabled\":false}").getStatus());
        verify(settings, never()).saveProperty(anyString(), anyString(), anyString());
        assertTrue(DebuggerPlugin.policy().enabled);
    }

    @Test void rbacRejectsTheSettingWithoutManagePermission() throws Exception {
        rbac(DebuggerServletInterface.VIEW, DebuggerServletInterface.CONTROL, DebuggerServletInterface.EVALUATE);
        assertEquals(false, body(servlet("debuggerCapabilities").capabilities()).get("managementAllowed"));
        assertEquals(403, change("{\"enabled\":false}").getStatus());
        assertThrows(MirthApiException.class, () -> engineSettings(ExtensionServletInterface.OPERATION_PLUGIN_PROPERTIES_SET));
        assertTrue(engineSettings(ExtensionServletInterface.OPERATION_PLUGIN_PROPERTIES_GET));
        verify(settings, never()).saveProperty(anyString(), anyString(), anyString());
        assertTrue(DebuggerPlugin.policy().enabled);
    }

    @Test void rbacRequiresViewAndManagePermissionsForTheSetting() throws Exception {
        rbac(DebuggerServletInterface.MANAGE);
        assertThrows(MirthApiException.class, () -> change("{\"enabled\":false}"));
        assertThrows(MirthApiException.class, () -> engineSettings(ExtensionServletInterface.OPERATION_PLUGIN_PROPERTIES_GET));
        assertTrue(engineSettings(ExtensionServletInterface.OPERATION_PLUGIN_PROPERTIES_SET));
        verify(settings, never()).saveProperty(anyString(), anyString(), anyString());
        rbac(DebuggerServletInterface.VIEW, DebuggerServletInterface.MANAGE);
        assertEquals(true, body(servlet("debuggerCapabilities").capabilities()).get("managementAllowed"));
        assertEquals(false, body(change("{\"enabled\":false}")).get("configuredEnabled"));
        assertEquals("false", stored.getProperty("enabled"));
    }

    @Test void permissionsAreRecheckedAfterRenderingTheControl() {
        assertEquals(true, body(servlet("debuggerCapabilities").capabilities()).get("managementAllowed"));
        denied = Set.of(POINT + "#debuggerSetEnabled");
        assertEquals(403, change("{\"enabled\":false}").getStatus());
        assertEquals("true", stored.getProperty("enabled"));
    }

    @Test void togglePreservesAllIndependentInstallationRestrictions() {
        stored.setProperty("allowedUserIds", "7");
        stored.setProperty("allowedChannelIds", "channel-a");
        stored.setProperty("evaluationEnabled", "false");
        stored.setProperty("evaluationUserIds", "8");
        stored.setProperty("globalUserIds", "9");
        stored.setProperty("futureProperty", "preserve me");
        Properties original = (Properties) stored.clone(); plugin.init(stored);
        body(change("{\"enabled\":false}")); body(change("{\"enabled\":true}"));
        assertEquals(original, stored);
        assertTrue(DebuggerPlugin.policy().allows(7));
        assertFalse(DebuggerPlugin.policy().allows(8));
        assertFalse(DebuggerPlugin.policy().channel("channel-b"));
        assertFalse(DebuggerPlugin.policy().evaluates(7));
        verify(settings).saveProperty(POINT, "enabled", "false");
        verify(settings).saveProperty(POINT, "enabled", "true");
    }

    @Test void disablingClosesSessionsAcrossOwnersAndCanBeReenabled() {
        String first = arm("7:first"), second = arm("8:second");
        assertEquals(true, body(change("{\"enabled\":false}")).get("configurationChanged"));
        assertEquals("closed", runtime.session("7:first", first, "tab-a").get("status"));
        assertEquals("closed", runtime.session("8:second", second, "tab-a").get("status"));
        body(change("{\"enabled\":true}"));
        assertEquals("armed", runtime.session("7:first", arm("7:first"), "tab-a").get("status"));
    }

    @Test void repeatingTheCurrentStateDoesNotCloseNewSessions() {
        String id = arm("7:first");
        assertEquals(false, body(change("{\"enabled\":true}")).get("configurationChanged"));
        assertEquals("armed", runtime.session("7:first", id, "tab-a").get("status"));
        verify(settings, never()).saveProperty(anyString(), anyString(), anyString());
    }

    @Test void silentPersistenceFailureIsNotReportedAsSuccess() {
        doNothing().when(settings).saveProperty(anyString(), anyString(), anyString());
        assertEquals(500, change("{\"enabled\":false}").getStatus());
        assertTrue(DebuggerPlugin.policy().enabled);
        assertEquals(true, runtime.status().get("enabled"));
    }

    @Test void onlyAnExplicitBooleanEnableFlagIsAccepted() {
        for (String json : new String[] {"{}", "{\"enabled\":\"false\"}", "{\"enabled\":null}",
                "{\"enabled\":false,\"allowedUserIds\":\"\"}"})
            assertEquals(400, change(json).getStatus(), json);
        verify(settings, never()).saveProperty(anyString(), anyString(), anyString());
    }
}
