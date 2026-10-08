package org.openintegrationengine.debugger.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mozilla.javascript.ContextFactory;
import org.openintegrationengine.debugger.runtime.DebugRuntime;

import com.mirth.connect.server.controllers.DefaultContextFactoryController;
import com.mirth.connect.server.controllers.ExtensionController;
import com.mirth.connect.server.util.javascript.MirthContextFactory;

class DebugContextFactoryControllerTest {
    @Test void normalGlobalAndSharedFactoriesAttachAndCheckEachRetrieval() throws Exception {
        MirthContextFactory factory = mock(MirthContextFactory.class);
        DebugRuntime runtime = mock(DebugRuntime.class);
        try (MockedStatic<ContextFactory> contexts = mockStatic(ContextFactory.class);
                MockedStatic<DebugRuntime> runtimes = mockStatic(DebugRuntime.class);
                MockedStatic<EngineCompatibility> compatibility = mockStatic(EngineCompatibility.class)) {
            contexts.when(ContextFactory::getGlobal).thenReturn(factory);
            runtimes.when(DebugRuntime::getInstance).thenReturn(runtime);
            compatibility.when(() -> EngineCompatibility.canAttach(factory)).thenReturn(true);
            DebugContextFactoryController controller = new DebugContextFactoryController(mock(ExtensionController.class));
            assertSame(factory, controller.getGlobalContextFactory());
            assertSame(factory, controller.getContextFactory(Set.of()));
            assertSame(factory, controller.getGlobalScriptContextFactory());
            verify(runtime, atLeast(3)).attach(factory);
            compatibility.when(() -> EngineCompatibility.canAttach(factory)).thenReturn(false);
            clearInvocations(runtime);
            assertSame(factory, controller.getContextFactory(Set.of()));
            verify(runtime, never()).attach(any());
        }
    }

    @Test void nativeFactoryIsExcludedEvenBeforeItsDebugFlagIsSet() throws Exception {
        DebugContextFactoryController controller = new DebugContextFactoryController(mock(ExtensionController.class));
        MirthContextFactory nativeFactory = mock(MirthContextFactory.class);
        when(nativeFactory.isDebug()).thenReturn(false);
        Field field = DefaultContextFactoryController.class.getDeclaredField("debugContextFactoryMap");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") Map<Set<String>, MirthContextFactory> factories = (Map<Set<String>, MirthContextFactory>) field.get(controller);
        factories.put(Set.of("channel", "script"), nativeFactory);
        try (MockedStatic<DebugRuntime> runtimes = mockStatic(DebugRuntime.class)) {
            assertSame(nativeFactory, controller.getDebugContextFactory(Set.of(), "channel", "script"));
            runtimes.verifyNoInteractions();
        }
    }

    @Test void libraryReloadReleasesSessionsBeforeReplacingResources() throws Exception {
        DebugContextFactoryController controller = new DebugContextFactoryController(mock(ExtensionController.class));
        DebugRuntime runtime = mock(DebugRuntime.class);
        try (MockedStatic<DebugRuntime> runtimes = mockStatic(DebugRuntime.class)) {
            runtimes.when(DebugRuntime::getInstance).thenReturn(runtime);
            controller.reloadResource("removed-resource");
            verify(runtime).closeAll(contains("reloading"));
        }
    }
}
