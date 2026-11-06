package org.openintegrationengine.debugger.api;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import com.mirth.connect.client.core.api.BaseServletInterface;
import com.mirth.connect.client.core.api.MirthOperation;
import com.mirth.connect.client.core.api.Param;
import com.mirth.connect.client.core.Operation.ExecuteType;
import io.swagger.annotations.Api;

@Path("/extensions/debugger")
@Api("JavaScript Debugger")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface DebuggerServletInterface extends BaseServletInterface {
    String PLUGIN_POINT = "JavaScript Debugger";
    String VIEW = "View JavaScript Debugger";
    String CONTROL = "Control JavaScript Debugger";
    String EVALUATE = "Evaluate JavaScript Debugger";
    String MANAGE = "Manage JavaScript Debugger";

    @GET @Path("/capabilities")
    @MirthOperation(name="debuggerCapabilities", display="Debugger capabilities", permission=VIEW, type=ExecuteType.ASYNC, auditable=false)
    Response capabilities();
    @PUT @Path("/enabled")
    @MirthOperation(name="debuggerSetEnabled", display="Enable or disable debugger", permission=MANAGE, type=ExecuteType.ASYNC)
    Response setEnabled(@Param(value="body", excludeFromAudit=true) String body);
    @GET @Path("/sources")
    @MirthOperation(name="debuggerSources", display="Debugger sources", permission=VIEW, type=ExecuteType.ASYNC, auditable=false)
    Response sources(@Param("channelId") @QueryParam("channelId") String channelId);
    @GET @Path("/sources/{id}")
    @MirthOperation(name="debuggerSource", display="Debugger source", permission=VIEW, type=ExecuteType.ASYNC, auditable=false)
    Response source(@Param("debugSessionOrSourceId") @PathParam("id") String id, @Param("channelId") @QueryParam("channelId") String channelId,
            @Param("debugSessionId") @QueryParam("debugSessionId") String debugSessionId,
            @Param(value="tabId", excludeFromAudit=true) @QueryParam("tabId") String tabId);
    @POST @Path("/sessions")
    @MirthOperation(name="debuggerCreate", display="Arm debugger", permission=CONTROL, type=ExecuteType.ASYNC)
    Response create(@Param(value="body", excludeFromAudit=true) String body);
    @GET @Path("/sessions/{id}")
    @MirthOperation(name="debuggerSession", display="Debugger session", permission=VIEW, type=ExecuteType.ASYNC, auditable=false)
    Response session(@Param("debugSessionOrSourceId") @PathParam("id") String id, @Param(value="tabId", excludeFromAudit=true) @QueryParam("tabId") String tabId);
    @POST @Path("/sessions/{id}/heartbeat")
    @MirthOperation(name="debuggerHeartbeat", display="Renew debugger lease", permission=CONTROL, type=ExecuteType.ASYNC, auditable=false)
    Response heartbeat(@Param("debugSessionOrSourceId") @PathParam("id") String id, @Param(value="body", excludeFromAudit=true) String body);
    @DELETE @Path("/sessions/{id}")
    @MirthOperation(name="debuggerClose", display="Disarm debugger", permission=CONTROL, type=ExecuteType.ASYNC)
    Response close(@Param("debugSessionOrSourceId") @PathParam("id") String id, @Param(value="tabId", excludeFromAudit=true) @QueryParam("tabId") String tabId);
    @PUT @Path("/sessions/{id}/breakpoints")
    @MirthOperation(name="debuggerBreakpoints", display="Set debugger breakpoints", permission=CONTROL, type=ExecuteType.ASYNC)
    Response breakpoints(@Param("debugSessionOrSourceId") @PathParam("id") String id, @Param(value="body", excludeFromAudit=true) String body);
    @POST @Path("/sessions/{id}/commands")
    @MirthOperation(name="debuggerCommand", display="Control debugger execution", permission=CONTROL, type=ExecuteType.ASYNC)
    Response command(@Param("debugSessionOrSourceId") @PathParam("id") String id, @Param(value="body", excludeFromAudit=true) String body);
}
