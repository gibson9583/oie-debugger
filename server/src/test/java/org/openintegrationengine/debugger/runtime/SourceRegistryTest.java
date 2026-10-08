package org.openintegrationengine.debugger.runtime;

import org.junit.jupiter.api.Test;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.debug.DebuggableScript;
import java.lang.reflect.Field;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourceRegistryTest {
    @Test void liveCompiledSourcesSurviveTheRecentTextBudget() throws Exception {
        SourceRegistry registry = new SourceRegistry(); ContextFactory factory = new ContextFactory();
        List<DebuggableScript> retained = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            DebuggableScript script = script("source-" + i); retained.add(script);
            registry.register(factory, script, "x".repeat(900_000));
        }
        SourceRegistry.Source first = registry.compiled(retained.get(0));
        assertNotNull(first); assertNotNull(registry.compiled(retained.get(29)));
        assertEquals("x".repeat(900_000), registry.detail(first.id).get("text"));
        Field field = SourceRegistry.class.getDeclaredField("sources"); field.setAccessible(true);
        assertTrue(((Map<?, ?>) field.get(registry)).size() <= 17, "Only extra recent retention is bounded; live scripts keep their exact sources");
        Field characters = SourceRegistry.class.getDeclaredField("characters"); characters.setAccessible(true);
        assertTrue((long) characters.get(registry) <= 16_000_000);
    }

    @Test void liveSourcesRemainDiscoverableAndInvalidatableAfterRecentCountEviction() throws Exception {
        SourceRegistry registry = new SourceRegistry(); ContextFactory factory = new ContextFactory();
        DebuggableScript firstScript = script("first"); registry.register(factory, firstScript, "1;");
        SourceRegistry.Source first = registry.compiled(firstScript); registry.observe(first, "channel");
        for (int i = 0; i < SourceRegistry.MAX_SOURCES; i++)
            assertTrue(registry.register(factory, script("validation-" + i), "2;").isEmpty());
        assertSame(first, registry.compiled(firstScript)); assertSame(first, registry.require(first.id));
        assertEquals(first.id, registry.list("channel").get(0).get("id"));
        Field field = SourceRegistry.class.getDeclaredField("sources"); field.setAccessible(true);
        assertEquals(SourceRegistry.MAX_SOURCES, ((Map<?, ?>) field.get(registry)).size());

        registry.invalidate("channel"); registry.observe(first, "channel");
        assertTrue(registry.list("channel").isEmpty());
        registry.observe(first, "another-channel"); assertEquals(1, registry.list("another-channel").size());
        registry.clear(); assertFalse(first.valid); assertNull(registry.compiled(firstScript));
        assertThrows(IllegalArgumentException.class, () -> registry.require(first.id));
    }

    @Test void recompilationInvalidatesLivePredecessorOutsideTheRecentCache() throws Exception {
        SourceRegistry registry = new SourceRegistry(); ContextFactory factory = new ContextFactory();
        DebuggableScript firstScript = script("same-name"); registry.register(factory, firstScript, "1;");
        SourceRegistry.Source first = registry.compiled(firstScript);
        for (int i = 0; i < SourceRegistry.MAX_SOURCES; i++) registry.register(factory, script("validation-" + i), "2;");
        DebuggableScript replacement = script("same-name");
        assertEquals(Set.of(first.id), registry.register(factory, replacement, "3;"));
        assertFalse(first.valid); assertNull(registry.compiled(firstScript));
        assertThrows(IllegalArgumentException.class, () -> registry.require(first.id));
        assertNotNull(registry.compiled(replacement));

        for (int i = 0; i < 20; i++) registry.register(factory, script("same-name"), "4;");
        Field field = SourceRegistry.class.getDeclaredField("sources"); field.setAccessible(true);
        Collection<?> recent = ((Map<?, ?>) field.get(registry)).values();
        assertTrue(recent.stream().allMatch(value -> ((SourceRegistry.Source) value).valid), "Invalid replacements must not consume recent-source capacity");
    }

    @Test void dynamicMetadataComesFromRhinoRatherThanTheSourceName() {
        SourceRegistry registry = new SourceRegistry(); ContextFactory factory = new ContextFactory();
        DebuggableScript generated = script("any-name"); when(generated.isGeneratedScript()).thenReturn(true);
        registry.register(factory, generated, "1;");
        assertEquals(true, registry.detail(registry.compiled(generated).id).get("dynamic"));
        DebuggableScript ordinary = script("looks-like#1(eval)"); registry.register(factory, ordinary, "2;");
        assertEquals(false, registry.detail(registry.compiled(ordinary).id).get("dynamic"));
    }

    @Test void dynamicInvocationsWithTheSameNameNeverInvalidateEachOtherOrDeployedScripts() {
        SourceRegistry registry = new SourceRegistry(); ContextFactory factory = new ContextFactory();
        DebuggableScript deployed = script("same-name"); registry.register(factory, deployed, "1;");
        DebuggableScript first = script("same-name"), second = script("same-name");
        when(first.isGeneratedScript()).thenReturn(true); when(second.isGeneratedScript()).thenReturn(true);
        assertTrue(registry.register(factory, first, "2;").isEmpty());
        assertTrue(registry.register(factory, second, "3;").isEmpty());
        SourceRegistry.Source firstSource = registry.compiled(first), secondSource = registry.compiled(second);
        assertNotNull(firstSource); assertNotNull(secondSource); assertNotEquals(firstSource.id, secondSource.id);
        DebuggableScript replacement = script("same-name");
        assertEquals(Set.of(registry.compiled(deployed).id), registry.register(factory, replacement, "4;"));
        assertSame(firstSource, registry.compiled(first)); assertSame(secondSource, registry.compiled(second));
    }

    @Test void sameSourceNameInIndependentFactoriesDoesNotInvalidateEachOther() {
        SourceRegistry registry = new SourceRegistry(); ContextFactory first = new ContextFactory(), second = new ContextFactory();
        DebuggableScript a = script("shared-name"), b = script("shared-name");
        registry.register(first, a, "1;"); registry.register(second, b, "2;");
        assertNotNull(registry.compiled(a)); assertNotNull(registry.compiled(b));
    }

    private DebuggableScript script(String name) {
        DebuggableScript script = mock(DebuggableScript.class); when(script.isTopLevel()).thenReturn(true);
        when(script.getSourceName()).thenReturn(name); when(script.getLineNumbers()).thenReturn(new int[]{1}); return script;
    }
}
