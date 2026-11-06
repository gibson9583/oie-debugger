package org.openintegrationengine.debugger.api;

import com.mirth.connect.model.ConnectorMetaData;
import com.mirth.connect.model.PluginMetaData;
import com.mirth.connect.server.api.servlets.ExtensionServlet;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.ExtensionController;

import java.util.Map;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.FeatureContext;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExtensionPropertiesGuardTest {
    @Test void bindsBothActualEnginePropertyHandlersOnly() {
        ExtensionPropertiesGuard guard = new ExtensionPropertiesGuard();
        for (var method : ExtensionServlet.class.getMethods()) {
            ResourceInfo resource = mock(ResourceInfo.class);
            doReturn(ExtensionServlet.class).when(resource).getResourceClass();
            when(resource.getResourceMethod()).thenReturn(method);
            FeatureContext context = mock(FeatureContext.class);
            guard.configure(resource, context);
            if (method.getName().equals("getPluginProperties") || method.getName().equals("setPluginProperties"))
                verify(context).register(ExtensionPropertiesGuard.CanonicalNameFilter.class);
            else verifyNoInteractions(context);
        }
    }

    @Test void leavesDebuggerAndOtherPluginHandlersAlone() throws Exception {
        ResourceInfo resource = mock(ResourceInfo.class);
        doReturn(DebuggerServlet.class).when(resource).getResourceClass();
        when(resource.getResourceMethod()).thenReturn(DebuggerServlet.class.getMethod("capabilities"));
        FeatureContext context = mock(FeatureContext.class);
        new ExtensionPropertiesGuard().configure(resource, context);
        verifyNoInteractions(context);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "JavaScript Debugger ", "JavaScript Debugger  ", " JavaScript Debugger",
            "javascript debugger", "JavaScript Debugger\t", "JavaScript  Debugger",
            "JavaScript%20Debugger", "JavaScript Debugger\u00a0", "Unknown plugin"})
    void rejectsUnknownIdentitiesBeforeTheyReachAuthorizationOrSql(String name) {
        try (Harness h = new Harness(name)) {
            new ExtensionPropertiesGuard.CanonicalNameFilter().filter(h.request);
            verify(h.request).abortWith(argThat(response -> response.getStatus() == 404));
            verify(h.controllers, never()).createAuthorizationController();
            verify(h.controllers, never()).createConfigurationController();
            verify(h.extensions, never()).getPluginProperties(any(), any());
        } catch (com.mirth.connect.client.core.ControllerException e) {
            throw new AssertionError(e);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"JavaScript Debugger", "Other plugin", "Database Reader"})
    void preservesCanonicalPluginAndConnectorRequestsForNormalEngineAuthorization(String name) {
        try (Harness h = new Harness(name)) {
            new ExtensionPropertiesGuard.CanonicalNameFilter().filter(h.request);
            verify(h.request, never()).abortWith(any(Response.class));
            verify(h.controllers, never()).createAuthorizationController();
        }
    }

    @Test void registryFailureCannotPermitAnAlias() {
        try (Harness h = new Harness("JavaScript Debugger ")) {
            when(h.extensions.getPluginMetaData()).thenThrow(new IllegalStateException("registry unavailable"));
            assertThrows(IllegalStateException.class,
                    () -> new ExtensionPropertiesGuard.CanonicalNameFilter().filter(h.request));
            verify(h.controllers, never()).createConfigurationController();
        }
    }

    private static final class Harness implements AutoCloseable {
        final ControllerFactory controllers = mock(ControllerFactory.class);
        final ExtensionController extensions = mock(ExtensionController.class);
        final ContainerRequestContext request = mock(ContainerRequestContext.class);
        final MockedStatic<ControllerFactory> factories = mockStatic(ControllerFactory.class);

        Harness(String name) {
            factories.when(ControllerFactory::getFactory).thenReturn(controllers);
            when(controllers.createExtensionController()).thenReturn(extensions);
            when(extensions.getPluginMetaData()).thenReturn(Map.of(
                    "JavaScript Debugger", new PluginMetaData(), "Other plugin", new PluginMetaData()));
            when(extensions.getConnectorMetaData()).thenReturn(Map.of("Database Reader", new ConnectorMetaData()));
            UriInfo uri = mock(UriInfo.class);
            var parameters = new MultivaluedHashMap<String, String>();
            if (name != null) parameters.add("extensionName", name);
            when(uri.getPathParameters()).thenReturn(parameters);
            when(request.getUriInfo()).thenReturn(uri);
        }

        public void close() { factories.close(); }
    }
}
