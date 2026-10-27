package org.openintegrationengine.debugger.api;

import com.mirth.connect.client.core.Operation;
import com.mirth.connect.client.core.Operation.ExecuteType;
import com.mirth.connect.client.core.api.Param;
import com.mirth.connect.model.ServerEvent.Outcome;
import com.mirth.connect.server.api.DontCheckAuthorized;
import com.mirth.connect.server.api.MirthServlet;
import com.mirth.connect.server.controllers.ControllerFactory;

import org.openintegrationengine.debugger.DebuggerPlugin;
import org.openintegrationengine.debugger.engine.AuthoredSourceMapper;
import org.openintegrationengine.debugger.runtime.DebugRuntime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

/** HTTP threads authorize and enqueue; live Rhino scopes never leave the worker. */
public class DebuggerServlet extends MirthServlet implements DebuggerServletInterface {
    private final DebugRuntime runtime;

    public DebuggerServlet(@Context HttpServletRequest request, @Context SecurityContext sc) {
        this(request, sc, ControllerFactory.getFactory(), DebugRuntime.getInstance());
    }

    DebuggerServlet(
            HttpServletRequest request,
            SecurityContext sc,
            ControllerFactory controllers,
            DebugRuntime runtime) {
        super(request, null, sc, PLUGIN_POINT, true, controllers);
        this.runtime = runtime;
    }

    // Auditable endpoints use @DontCheckAuthorized. Every prerequisite is checked
    // without auditing; json() records one final request outcome after all checks.
    @Override
    protected boolean isUserAuthorized() {
        return super.isUserAuthorized(false);
    }

    private String owner() {
        HttpSession session = request.getSession(false);
        if (session == null)
            throw new SecurityException("An authenticated engine session is required");
        return getCurrentUserId() + ":" + session.getId();
    }

    private void access() {
        checkUserAuthorized();
        if (!DebuggerPlugin.policy().allows(getCurrentUserId()))
            throw new SecurityException(
                    "Debugger is disabled or this user is excluded by an explicit installation"
                        + " restriction");
    }

    private void auditTarget(String channelId) {
        // OIE resolves channelId parameters to real channels when encoding events.
        // A scalar target survives that adapter, including the virtual global target.
        parameterMap.put("debugTarget", channelId);
        if (!"global".equals(channelId)) parameterMap.put("channelId", channelId);
    }

    private void channel(String channelId) {
        auditTarget(channelId);
        access();
        if ("global".equals(channelId)) {
            requireGlobalAccess();
        } else {
            if (!DebuggerPlugin.policy().channel(channelId))
                throw new SecurityException(
                        "This channel is not enabled in the debugger access policy");
            parameterMap.put("channelId", channelId);
            checkUserAuthorized(channelId);
            if (controllerFactory.createChannelController().getChannelById(channelId) == null)
                throw new IllegalArgumentException("Channel does not exist");
        }
    }

    private void evaluation() {
        if (!DebuggerPlugin.policy().evaluates(getCurrentUserId()))
            throw new SecurityException(
                    "Evaluation is disabled or this user is excluded by an explicit installation"
                        + " restriction");
        requireOperation("debuggerEvaluate");
    }

    private void requireOperation(String name) {
        Operation original = operation;
        try {
            setOperation(new Operation(name, name, ExecuteType.ASYNC, false));
            checkUserAuthorized();
        } finally {
            operation = original;
        }
    }

    private boolean permitsOperation(String name) {
        Operation original = operation;
        try {
            setOperation(new Operation(name, name, ExecuteType.ASYNC, false));
            return isUserAuthorized(false);
        } catch (RuntimeException denied) {
            return false;
        } finally {
            operation = original;
        }
    }

    private boolean globalAllowed() {
        if (!DebuggerPlugin.policy().global(getCurrentUserId())
                || !permitsOperation("debuggerCommand")) return false;
        try {
            return !controllerFactory
                    .createAuthorizationController()
                    .doesUserHaveChannelRestrictions(getCurrentUserId(), operation);
        } catch (com.mirth.connect.client.core.ControllerException | RuntimeException unavailable) {
            return false;
        }
    }

    private void requireGlobalAccess() {
        if (!globalAllowed())
            throw new SecurityException(
                    "Global and lifecycle debugging requires unrestricted debugger control access");
    }

    private Map<String, Object> authorizedSession(String id, String tabId) {
        if (tabId == null || tabId.isBlank() || tabId.length() > 128)
            throw new IllegalArgumentException("tabId is required");
        Map<String, Object> session = runtime.session(owner(), id, tabId);
        // Verify ownership before releasing a session whose permissions were revoked.
        // These endpoints authorize here rather than in the engine's pre-invocation check.
        parameterMap.put("debugSessionId", id);
        auditTarget(Objects.toString(session.get("channelId"), ""));
        try {
            access();
            requireOperation("debuggerSession");
            requireOperation("debuggerCommand");
            channel(Objects.toString(session.get("channelId"), ""));
            if (Boolean.TRUE.equals(session.get("includeLifecycle"))) requireGlobalAccess();
            if (Boolean.TRUE.equals(session.get("evaluationEnabled"))) evaluation();
        } catch (RuntimeException denied) {
            runtime.close(owner(), id, tabId);
            throw denied;
        }
        return session;
    }

    private Response json(Supplier<Object> action) {
        Outcome outcome = Outcome.FAILURE;
        try {
            Response response =
                    Response.ok(Json.write(action.get()), "application/json")
                            .header("Cache-Control", "no-store")
                            .build();
            outcome = Outcome.SUCCESS;
            return response;
        } catch (DebugRuntime.ArmRequestRejected e) {
            RuntimeException cause = e.getCause();
            int status =
                    cause instanceof SecurityException
                            ? 403
                            : cause instanceof IllegalArgumentException
                                    ? 400
                                    : cause instanceof IllegalStateException
                                            ? 409
                                            : cause instanceof WebApplicationException web
                                                    ? web.getResponse().getStatus()
                                                    : 500;
            return failure(
                    status,
                    Map.of(
                            "error",
                            Objects.toString(e.getMessage(), "Debugger request failed"),
                            "code",
                            "arm_request_rejected",
                            "requestId",
                            e.requestId()));
        } catch (SecurityException e) {
            return failure(403, e.getMessage());
        } catch (DebuggerPlugin.SettingWriteException e) {
            return failure(500, e.getMessage());
        } catch (IllegalArgumentException e) {
            return failure(400, e.getMessage());
        } catch (IllegalStateException e) {
            return failure(409, e.getMessage());
        } catch (WebApplicationException e) {
            throw e;
        } finally {
            if (operation != null && operation.isAuditable()) auditAuthorizationRequest(outcome);
        }
    }

    private static Response failure(int status, String message) {
        return failure(
                status, Map.of("error", Objects.toString(message, "Debugger request failed")));
    }

    private static Response failure(int status, Map<String, ?> body) {
        return Response.status(status)
                .entity(Json.write(body))
                .type("application/json")
                .header("Cache-Control", "no-store")
                .build();
    }

    private static String text(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (!(value instanceof String)
                || ((String) value).isBlank()
                || ((String) value).length() > 256)
            throw new IllegalArgumentException("Missing or invalid " + key);
        return (String) value;
    }

    @Override
    public Response capabilities() {
        return json(() -> {
            checkUserAuthorized();
            return capabilitiesData();
        });
    }

    private Map<String, Object> capabilitiesData() {
        AccessPolicy policy = DebuggerPlugin.policy();
        boolean userAllowed = policy.permitsUser(getCurrentUserId());
        Map<String, Object> result = new LinkedHashMap<>(runtime.status());
        // Excluded users can see the setting, but cannot inspect runtime diagnostics.
        if (!userAllowed) result.clear();
        String unavailable = DebuggerPlugin.unavailableReason();
        if (!userAllowed) unavailable = "Your account is excluded by the debugger's installation settings.";
        if (Boolean.TRUE.equals(result.get("debuggerConflict")))
            unavailable =
                    "Another Rhino debugger owns an observed context; resolve the"
                        + " conflict and restart the plugin";
        if (!Boolean.TRUE.equals(result.get("enabled")) && unavailable == null)
            unavailable = "Debugger runtime is disabled";
        result.put("available", unavailable == null);
        result.put("configuredEnabled", policy.enabled);
        result.put("userAllowed", userAllowed);
        result.put("managementAllowed", permitsOperation("debuggerSetEnabled"));
        result.put("reason", unavailable);
        result.put("apiVersion", 1);
        result.put("controlAllowed", policy.enabled && userAllowed && permitsOperation("debuggerCommand"));
        result.put(
                "evaluationAllowed",
                DebuggerPlugin.policy().evaluates(getCurrentUserId())
                        && permitsOperation("debuggerEvaluate"));
        result.put("globalAllowed", globalAllowed());
        result.put("sourceMode", "compiled-generated");
        return result;
    }

    @Override
    @DontCheckAuthorized
    public Response setEnabled(@Param(value="body", excludeFromAudit=true) String body) {
        return json(() -> {
            requireOperation("debuggerCapabilities");
            parameterMap.put("extensionName", PLUGIN_POINT);
            if (!permitsOperation("debuggerSetEnabled"))
                throw new SecurityException("Manage JavaScript Debugger permission is required to enable or disable debugging");
            Map<String, Object> input = Json.read(body);
            if (input.size() != 1 || !(input.get("enabled") instanceof Boolean enabled))
                throw new IllegalArgumentException("Provide only an enabled boolean");
            parameterMap.put("enabled", enabled);
            var plugin = controllerFactory.createExtensionController().getServicePlugins().get(PLUGIN_POINT);
            if (!(plugin instanceof DebuggerPlugin debugger))
                throw new IllegalStateException("The debugger service is not running");
            boolean changed = debugger.setEnabled(controllerFactory.createConfigurationController(), enabled);
            Map<String, Object> result = capabilitiesData();
            result.put("configurationChanged", changed);
            return result;
        });
    }

    private boolean currentSource(Map<String, Object> source) {
        return AuthoredSourceMapper.isCurrentSource(
                (String) source.get("id"), (String) source.get("name"), runtime);
    }

    private Map<String, Object> sourceForChannel(Map<String, Object> source, String channelId) {
        // Shared compiled scripts retain every observed channel internally. Only the
        // requested target has been authorized; never expose other channel associations.
        Map<String, Object> result = new LinkedHashMap<>(source);
        result.put("channels", List.of(channelId));
        return result;
    }

    private void requireCurrentSource(String id) {
        if (!currentSource(runtime.source(id)))
            throw new IllegalStateException(
                    "Compiled source was replaced or is no longer cached; refresh sources and arm a"
                        + " new session");
    }

    private void requireAccessibleSource(
            String sourceId,
            String sessionId,
            String tabId,
            String channelId,
            Map<String, Object> authorized) {
        Map<String, Object> source = runtime.source(sourceId);
        if (currentSource(source)) return;
        // Rhino eval/Function sources have no engine cache entry. Their exact captured
        // text is accessible only through the current pause owned by this browser/tab.
        // A replaced deployed source must never use this exception to the cache fence.
        if (Boolean.TRUE.equals(source.get("dynamic"))
                && sessionId != null
                && !sessionId.isBlank()) {
            Map<String, Object> session =
                    authorized != null ? authorized : authorizedSession(sessionId, tabId);
            if (Objects.equals(channelId, session.get("channelId"))
                    && runtime.isPausedSource(owner(), sessionId, tabId, sourceId)) return;
        }
        throw new IllegalStateException(
                "Source is no longer current; dynamic sources require their owning paused session");
    }

    @Override
    public Response sources(@Param("channelId") String channelId) {
        return json(
                () -> {
                    channel(channelId);
                    return runtime.sources(channelId).stream()
                            .filter(this::currentSource)
                            .map(source -> sourceForChannel(source, channelId))
                            .toList();
                });
    }

    @Override
    public Response source(
            @Param("debugSourceId") String id,
            @Param("channelId") String channelId,
            @Param("debugSessionId") String debugSessionId,
            @Param(value = "tabId", excludeFromAudit = true) String tabId) {
        return json(
                () -> {
                    channel(channelId);
                    boolean visible =
                            runtime.sources(channelId).stream()
                                    .anyMatch(s -> id.equals(s.get("id")));
                    if (!visible)
                        throw new SecurityException(
                                "Source is unavailable for the selected channel");
                    requireAccessibleSource(id, debugSessionId, tabId, channelId, null);
                    Map<String, Object> result = sourceForChannel(runtime.source(id), channelId);
                    result.put(
                            "authoredMappings",
                            AuthoredSourceMapper.map(
                                    channelId,
                                    id,
                                    (String) result.get("name"),
                                    (String) result.get("text"),
                                    runtime));
                    return result;
                });
    }

    @Override
    @DontCheckAuthorized
    public Response create(@Param(value = "body", excludeFromAudit = true) String body) {
        return json(
                () -> {
                    parameterMap.put("action", "arm");
                    Map<String, Object> options = Json.read(body);
                    String channelId = text(options, "channelId");
                    auditTarget(channelId);
                    // Authorize inside the creation lookup so a denied recovery can release only
                    // this owner/tab's original lease, just like the owned-session endpoints.
                    return runtime.create(
                            owner(),
                            channelId,
                            options,
                            () -> {
                                channel(channelId);
                                requireOperation("debuggerSession");
                                requireOperation("debuggerCommand");
                                if (DebuggerPlugin.unavailableReason() != null)
                                    throw new IllegalStateException(
                                            DebuggerPlugin.unavailableReason());
                                if (Boolean.TRUE.equals(options.get("evaluationEnabled"))
                                        || options.get("watches")
                                                        instanceof java.util.List<?> watches
                                                && !watches.isEmpty()) evaluation();
                                if (Boolean.TRUE.equals(options.get("includeLifecycle")))
                                    requireGlobalAccess();
                            },
                            () -> {
                                if (options.get("sourceId") instanceof String sourceId
                                        && !sourceId.isBlank()) requireCurrentSource(sourceId);
                            });
                });
    }

    @Override
    @DontCheckAuthorized
    public Response session(
            @Param("debugSessionId") String id,
            @Param(value = "tabId", excludeFromAudit = true) String tabId) {
        return json(() -> authorizedSession(id, tabId));
    }

    @Override
    @DontCheckAuthorized
    public Response heartbeat(
            @Param("debugSessionId") String id,
            @Param(value = "body", excludeFromAudit = true) String body) {
        return json(
                () -> {
                    String tabId = text(Json.read(body), "tabId");
                    authorizedSession(id, tabId);
                    return runtime.heartbeat(owner(), id, tabId);
                });
    }

    @Override
    @DontCheckAuthorized
    public Response close(
            @Param("debugSessionId") String id,
            @Param(value = "tabId", excludeFromAudit = true) String tabId) {
        return json(
                () -> {
                    parameterMap.put("action", "close");
                    authorizedSession(id, tabId);
                    runtime.close(owner(), id, tabId);
                    return Map.of("status", "closed");
                });
    }

    @Override
    @DontCheckAuthorized
    public Response breakpoints(
            @Param("debugSessionId") String id,
            @Param(value = "body", excludeFromAudit = true) String body) {
        return json(
                () -> {
                    parameterMap.put("action", "setBreakpoints");
                    Map<String, Object> request = Json.read(body);
                    Map<String, Object> session = authorizedSession(id, text(request, "tabId"));
                    // A session with expression features always retains the elevated permission.
                    if (Boolean.TRUE.equals(session.get("evaluationEnabled"))) evaluation();
                    return runtime.breakpoints(
                            owner(),
                            id,
                            request,
                            () ->
                                    requireAccessibleSource(
                                            text(request, "sourceId"),
                                            id,
                                            text(request, "tabId"),
                                            Objects.toString(session.get("channelId"), ""),
                                            session));
                });
    }

    @Override
    @DontCheckAuthorized
    public Response command(
            @Param("debugSessionId") String id,
            @Param(value = "body", excludeFromAudit = true) String body) {
        return json(
                () -> {
                    parameterMap.put("action", "invalid");
                    Map<String, Object> request = Json.read(body);
                    if (request.get("action") instanceof String requestedAction
                            && DebugRuntime.ACTIONS.contains(requestedAction))
                        parameterMap.put("action", requestedAction);
                    Object sequence = request.get("commandSequence");
                    if (sequence instanceof Integer || sequence instanceof Long)
                        parameterMap.put("commandSequence", sequence);
                    Map<String, Object> session = authorizedSession(id, text(request, "tabId"));
                    String action = text(request, "action");
                    if (action.equals("evaluate")
                            || action.equals("setValue")
                            || action.equals("setWatches")) evaluation();
                    return runtime.command(
                            owner(),
                            id,
                            request,
                            () ->
                                    requireAccessibleSource(
                                            text(request, "sourceId"),
                                            id,
                                            text(request, "tabId"),
                                            Objects.toString(session.get("channelId"), ""),
                                            session));
                });
    }
}
