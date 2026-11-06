package org.openintegrationengine.debugger.api;

import com.mirth.connect.client.core.api.servlets.ExtensionServletInterface;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.ExtensionController;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.DynamicFeature;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.FeatureContext;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.Provider;

/** Keep the engine's authorization identity identical to its property namespace. */
@Provider
public final class ExtensionPropertiesGuard implements DynamicFeature {
    @Override
    public void configure(ResourceInfo resource, FeatureContext context) {
        if (ExtensionServletInterface.class.isAssignableFrom(resource.getResourceClass())) {
            String method = resource.getResourceMethod().getName();
            if (ExtensionServletInterface.OPERATION_PLUGIN_PROPERTIES_GET.equals(method)
                    || ExtensionServletInterface.OPERATION_PLUGIN_PROPERTIES_SET.equals(method))
                context.register(CanonicalNameFilter.class);
        }
    }

    public static final class CanonicalNameFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext request) {
            // Use the same once-decoded path parameter as ExtensionServlet. Never trim,
            // case-fold or guess the database collation: an unknown spelling must not
            // reach either RBAC's allow-unknown branch or configuration SQL.
            String name = request.getUriInfo().getPathParameters().getFirst("extensionName");
            ExtensionController extensions = ControllerFactory.getFactory().createExtensionController();
            if (name == null || !(extensions.getPluginMetaData().containsKey(name)
                    || extensions.getConnectorMetaData().containsKey(name)))
                request.abortWith(Response.status(Response.Status.NOT_FOUND).build());
            // Canonical names continue through the engine's normal authentication,
            // per-extension authorization, auditing and property application.
        }
    }
}
