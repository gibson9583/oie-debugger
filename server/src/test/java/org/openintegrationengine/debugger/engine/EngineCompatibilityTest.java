package org.openintegrationengine.debugger.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mozilla.javascript.ContextFactory;

import com.mirth.connect.model.PluginClass;
import com.mirth.connect.server.util.javascript.JavaScriptScopeUtil;
import com.mirth.connect.server.util.javascript.MirthContextFactory;
import com.mirth.connect.util.PropertyLoader;

class EngineCompatibilityTest {
    @Test void readsActualCachedEngineLevelAndRejectsOptimizedCompilationBeforeSelection() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("rhino.optimizationlevel", "-1");
        try (MockedStatic<PropertyLoader> loader = mockStatic(PropertyLoader.class)) {
            loader.when(() -> PropertyLoader.loadProperties("mirth")).thenReturn(properties);
            Field level = JavaScriptScopeUtil.class.getDeclaredField("rhinoOptimizationLevel");
            level.setAccessible(true);
            Object original = level.get(null);
            try {
                level.set(null, -1);
                assertNull(EngineCompatibility.status());
                assertTrue(EngineCompatibility.canAttach(new ContextFactory()));
                properties.setProperty("rhino.optimizationlevel", "0");
                assertNull(EngineCompatibility.status(), "Changing a file does not change the running engine's cached level");
                level.set(null, 0);
                assertNotNull(EngineCompatibility.status());
                assertFalse(EngineCompatibility.canAttach(new ContextFactory()));
                assertFalse(new DebugControllerCondition().accept(new PluginClass()));
                assertTrue(DebugControllerCondition.getUnavailableReason().contains("optimizationlevel=-1"));
                level.set(null, null);
                assertNotNull(EngineCompatibility.status());
            } finally { level.set(null, original); }
        }
    }

    @Test void excludesNativeAndSealedFactoriesWithoutMutatingThem() {
        MirthContextFactory nativeFactory = mock(MirthContextFactory.class);
        when(nativeFactory.isDebug()).thenReturn(true);
        assertFalse(EngineCompatibility.canAttach(nativeFactory));
        ContextFactory sealed = new ContextFactory();
        sealed.seal();
        assertFalse(EngineCompatibility.canAttach(sealed));
        assertFalse(EngineCompatibility.canAttach(null));
    }
}
