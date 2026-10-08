package org.openintegrationengine.debugger.engine;

import java.util.Collection;
import java.util.function.Predicate;

import com.mirth.connect.model.PluginClass;
import com.mirth.connect.model.PluginClassCondition;
import com.mirth.connect.model.PluginMetaData;
import com.mirth.connect.server.ExtensionLoader;
import com.mirth.connect.server.controllers.ContextFactoryController;
import com.mirth.connect.server.extprops.ExtensionStatuses;

/** A fail-closed selection condition, evaluated before the controller override is installed. */
public final class DebugControllerCondition implements PluginClassCondition {
    private static volatile String unavailableReason;

    @Override
    public boolean accept(PluginClass pluginClass) {
        try {
            String reason = EngineCompatibility.status();
            if (reason == null) reason = EngineCompatibility.nativeDebuggerStatus();
            if (reason == null) {
                reason = competingController(ExtensionLoader.getInstance().getPluginMetaData().values(),
                        ExtensionStatuses.getInstance()::isEnabled);
            }
            unavailableReason = reason;
            return reason == null;
        } catch (Throwable unavailable) {
            unavailableReason = "Unable to inspect enabled controller extensions; debugger interception is disabled.";
            return false;
        }
    }

    public static String getUnavailableReason() { return unavailableReason; }

    static String competingController(Collection<PluginMetaData> plugins, Predicate<String> enabled) {
        for (PluginMetaData plugin : plugins) {
            if (!enabled.test(plugin.getName()) || plugin.getControllerClasses() == null) continue;
            for (PluginClass candidate : plugin.getControllerClasses()) {
                if (DebugContextFactoryController.class.getName().equals(candidate.getName())) continue;
                try {
                    Class<?> type = Class.forName(candidate.getName(), false,
                            DebugControllerCondition.class.getClassLoader());
                    if (ContextFactoryController.class.isAssignableFrom(type)) {
                        // Do not call another plugin's condition: it can recurse through this loader.
                        return "An enabled plugin supplies a context factory controller: " + plugin.getName();
                    }
                } catch (Throwable unknownController) {
                    return "Unable to inspect controller extension from enabled plugin: " + plugin.getName();
                }
            }
        }
        return null;
    }
}
