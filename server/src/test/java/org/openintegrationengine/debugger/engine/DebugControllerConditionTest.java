package org.openintegrationengine.debugger.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mirth.connect.model.PluginClass;
import com.mirth.connect.model.PluginMetaData;
import com.mirth.connect.server.controllers.DefaultChannelController;
import com.mirth.connect.server.controllers.DefaultContextFactoryController;

class DebugControllerConditionTest {
    @Test void declinesEveryEnabledCompetingControllerRegardlessOfWeightOrCondition() {
        PluginMetaData other = plugin("other", DefaultContextFactoryController.class.getName());
        other.getControllerClasses().get(0).setWeight(-100);
        other.getControllerClasses().get(0).setConditionClass("missing.condition.ThatMustNotBeInvoked");
        assertTrue(DebugControllerCondition.competingController(List.of(other), name -> true).contains("other"));
    }

    @Test void ignoresDisabledControllersAndOwnOverrideAndOtherControllerTypes() {
        PluginMetaData disabled = plugin("disabled", DefaultContextFactoryController.class.getName());
        PluginMetaData own = plugin("debugger", DebugContextFactoryController.class.getName());
        PluginMetaData otherType = plugin("channel", DefaultChannelController.class.getName());
        assertNull(DebugControllerCondition.competingController(List.of(disabled, own, otherType),
                name -> !name.equals("disabled")));
    }

    @Test void unverifiableEnabledControllerFailsClosed() {
        assertNotNull(DebugControllerCondition.competingController(List.of(plugin("unknown", "missing.Unknown")), name -> true));
    }

    private static PluginMetaData plugin(String name, String controller) {
        PluginClass candidate = new PluginClass();
        candidate.setName(controller);
        PluginMetaData plugin = new PluginMetaData();
        plugin.setName(name);
        plugin.setControllerClasses(List.of(candidate));
        return plugin;
    }
}
