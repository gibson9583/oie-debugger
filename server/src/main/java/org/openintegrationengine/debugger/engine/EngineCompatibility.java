package org.openintegrationengine.debugger.engine;

import java.lang.reflect.Field;
import java.util.Map;

import org.mozilla.javascript.ContextFactory;

import com.mirth.connect.server.util.javascript.JavaScriptScopeUtil;
import com.mirth.connect.server.util.javascript.MirthContextFactory;

/** Checks the level actually cached by the engine, not a possibly edited properties file. */
public final class EngineCompatibility {
    private EngineCompatibility() { }

    public static String status() {
        try {
            Field level = JavaScriptScopeUtil.class.getDeclaredField("rhinoOptimizationLevel");
            level.setAccessible(true);
            if (!Integer.valueOf(-1).equals(level.get(null))) {
                return "The engine must start with rhino.optimizationlevel=-1; restart after changing it.";
            }
            return null;
        } catch (Throwable unavailable) {
            // ExtensionLoader defaults to accept when a condition throws. Never let a missing
            // field, class-initialization failure or restricted reflection activate this override.
            return "Unable to verify the engine's Rhino optimization level; debugger interception is disabled.";
        }
    }

    public static boolean canAttach(ContextFactory factory) {
        return factory != null && !factory.isSealed()
                && (!(factory instanceof MirthContextFactory)
                        || !Boolean.TRUE.equals(((MirthContextFactory) factory).isDebug()))
                && status() == null;
    }

    static String nativeDebuggerStatus() {
        try {
            Class<?> nativeDebugger = Class.forName("org.mozilla.javascript.tools.debugger.MirthMain", false,
                    EngineCompatibility.class.getClassLoader());
            Field instances = nativeDebugger.getDeclaredField("mainInstanceMap");
            instances.setAccessible(true);
            Object value = instances.get(null);
            if (!(value instanceof Map)) {
                return "Unable to verify native debugger state; debugger interception is disabled.";
            }
            return ((Map<?, ?>) value).isEmpty() ? null : "The native Swing debugger is already active.";
        } catch (Throwable unavailable) {
            return "Unable to verify native debugger state; debugger interception is disabled.";
        }
    }
}
