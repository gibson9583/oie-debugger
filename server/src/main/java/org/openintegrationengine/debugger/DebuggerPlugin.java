package org.openintegrationengine.debugger;

import java.util.Properties;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.ExtensionPermission;
import com.mirth.connect.model.ServerEventContext;
import com.mirth.connect.plugins.ChannelPlugin;
import com.mirth.connect.plugins.ServicePlugin;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EventController;
import org.openintegrationengine.debugger.api.AccessPolicy;
import org.openintegrationengine.debugger.api.DebuggerServletInterface;
import org.openintegrationengine.debugger.engine.DebugContextFactoryController;
import org.openintegrationengine.debugger.engine.DebugControllerCondition;
import org.openintegrationengine.debugger.engine.DebugLifecycleListener;
import org.openintegrationengine.debugger.engine.EngineCompatibility;
import org.openintegrationengine.debugger.runtime.DebugRuntime;

public final class DebuggerPlugin implements ServicePlugin, ChannelPlugin {
    private static volatile AccessPolicy policy = new AccessPolicy(new Properties());
    private static volatile String unavailable = "Debugger plugin has not started";
    private DebugLifecycleListener listener;
    public static AccessPolicy policy() { return policy; }
    public static String unavailableReason() {
        if (unavailable != null) return unavailable;
        if (!policy.enabled) return "Debugger is disabled in plugin settings";
        return EngineCompatibility.status();
    }
    @Override public String getPluginPointName() { return DebuggerServletInterface.PLUGIN_POINT; }
    @Override public void init(Properties properties) { configure(properties, false); }
    @Override public void update(Properties properties) { configure(properties, true); }
    private synchronized void configure(Properties properties, boolean closeSessions) {
        AccessPolicy updated = new AccessPolicy(properties);
        policy = updated;
        // Publish restrictions before fencing both existing sessions and Arms still authorizing.
        if (closeSessions) DebugRuntime.getInstance().closeAll("Debugger access policy changed; arm a new session");
        DebugRuntime.getInstance().setAttachmentGuard(EngineCompatibility::canAttach);
        DebugRuntime.getInstance().configure(updated.enabled, updated.evaluationEnabled);
    }
    @Override public synchronized void start() {
        if (!(ControllerFactory.getFactory().createContextFactoryController() instanceof DebugContextFactoryController)) {
            unavailable = DebugControllerCondition.getUnavailableReason();
            if (unavailable == null) unavailable = "Debugger context-factory controller is not active";
            DebugRuntime.getInstance().configure(false, false);
            return;
        }
        try {
            DebugRuntime.getInstance().configure(policy.enabled, policy.evaluationEnabled);
            if (listener == null) {
                listener = new DebugLifecycleListener();
                EventController.getInstance().addListener(listener);
            }
            unavailable = EngineCompatibility.status();
        } catch (RuntimeException e) {
            unavailable = "Unable to register debugger lifecycle notifications";
            stop();
            throw e;
        }
    }
    @Override public synchronized void stop() {
        DebugRuntime.getInstance().configure(false, false);
        DebugRuntime.getInstance().closeAll("Debugger plugin stopped");
        if (listener != null) {
            EventController.getInstance().removeListener(listener);
            listener.shutdown();
            listener = null;
        }
        if (unavailable == null) unavailable = "Debugger plugin stopped";
    }
    @Override public Properties getDefaultProperties() {
        Properties p = new Properties();
        p.setProperty("enabled", "true"); p.setProperty("allowedUserIds", "");
        p.setProperty("allowedChannelIds", ""); p.setProperty("evaluationEnabled", "true");
        p.setProperty("evaluationUserIds", ""); p.setProperty("globalUserIds", "");
        return p;
    }
    @Override public ExtensionPermission[] getExtensionPermissions() {
        return new ExtensionPermission[] {
            new ExtensionPermission(getPluginPointName(), DebuggerServletInterface.VIEW, "Read permitted debugger sources and owned sessions",
                new String[]{"debuggerCapabilities", "debuggerSources", "debuggerSource", "debuggerSession"}, new String[]{"doShowDebugger"}),
            new ExtensionPermission(getPluginPointName(), DebuggerServletInterface.CONTROL, "Arm, pause, step and resume permitted JavaScript executions",
                new String[]{"debuggerCreate", "debuggerHeartbeat", "debuggerClose", "debuggerBreakpoints", "debuggerCommand"}, new String[]{"doControlDebugger"}),
            new ExtensionPermission(getPluginPointName(), DebuggerServletInterface.EVALUATE, "Execute expressions, conditional breakpoints, watches and value changes",
                new String[]{"debuggerEvaluate"}, new String[]{"doEvaluateDebugger"})
        };
    }
    @Override public void save(Channel channel, ServerEventContext context) { /* Draft edits do not change compiled runtime sources. */ }
    @Override public void remove(Channel channel, ServerEventContext context) { DebugRuntime.getInstance().invalidate(channel.getId(), "Channel removed"); }
    @Override public void deploy(Channel channel, ServerEventContext context) { /* Compilation and early state events fence generations. */ }
    @Override public void deploy(ServerEventContext context) {}
    @Override public void undeploy(String channelId, ServerEventContext context) { /* Early stop events close existing sessions; lifecycle debugging requires a new session. */ }
    @Override public void undeploy(ServerEventContext context) {}
}
