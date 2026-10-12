package org.openintegrationengine.debugger.runtime;

import org.junit.jupiter.api.Test;
import org.mozilla.javascript.*;
import com.mirth.connect.server.userutil.SourceMap;
import com.mirth.connect.server.userutil.ChannelMap;
import com.mirth.connect.userutil.ResponseMap;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class SafeInspectorTest {
    @Test void inspectionBudgetStartsWhenInspectionBegins() throws Exception {
        SafeInspector inspector = new SafeInspector();
        Thread.sleep(100);
        assertEquals(42, inspector.inspect(42).get("value"));
    }

    @Test void skipsJavascriptGettersAndJavaToString() {
        Context cx = Context.enter();
        try {
            Scriptable scope = cx.initStandardObjects();
            Scriptable object = (Scriptable) cx.evaluateString(scope, "var calls=0; var item={plain:7}; Object.defineProperty(item,'evil',{enumerable:true,get:function(){calls++;throw 'getter';}}); item;", "inspection", 1, null);
            Map<String, Object> value = new SafeInspector().inspect(object);
            assertEquals(0.0, ScriptableObject.getProperty(scope, "calls"));
            assertEquals("accessor", ((Map<?, ?>) ((Map<?, ?>) value.get("properties")).get("evil")).get("type"));
            AtomicInteger calls = new AtomicInteger();
            Object hostile = new Object() { @Override public String toString() { calls.incrementAndGet(); throw new AssertionError("Must not stringify Java values"); } };
            assertEquals("opaque", new SafeInspector().inspect(Context.javaToJS(hostile, scope)).get("type")); assertEquals(0, calls.get());
        } finally { Context.exit(); }
    }

    @Test void boundsLargeAndCyclicValues() {
        Map<String, Object> values = new LinkedHashMap<>(); values.put("self", values); values.put("text", "x".repeat(100_000));
        for (int i = 0; i < 1000; i++) values.put("key" + i, i);
        Map<String, Object> result = new SafeInspector().inspect(values);
        Map<?, ?> properties = (Map<?, ?>) result.get("properties");
        assertTrue(properties.size() <= 64); assertEquals("circular", ((Map<?, ?>) properties.get("self")).get("type"));
        assertTrue(((Map<?, ?>) properties.get("text")).get("value").toString().length() <= 2049); assertEquals(true, result.get("truncated"));
    }

    @Test void arbitraryMapImplementationsAreOpaque() {
        Map<String, Object> hostile = new AbstractMap<>() { @Override public Set<Entry<String, Object>> entrySet() { throw new AssertionError("No arbitrary map methods"); } };
        assertEquals("opaque", new SafeInspector().inspect(hostile).get("type"));
        assertEquals("opaque", new SafeInspector().inspect(new SourceMap(hostile)).get("type"));
        assertEquals("opaque", new SafeInspector().inspect(new ChannelMap(hostile, null)).get("type"));
        assertEquals("opaque", new SafeInspector().inspect(new ResponseMap(hostile, null)).get("type"));
    }

    @Test void knownEngineMapsInspectTheirOrdinaryBackingData() {
        Map<String, Object> data = new HashMap<>(); data.put("answer", 42);
        for (Object wrapper : List.of(new SourceMap(data), new ChannelMap(data, null), new ResponseMap(data, null))) {
            Map<?, ?> properties = (Map<?, ?>) new SafeInspector().inspect(wrapper).get("properties");
            assertEquals(42, ((Map<?, ?>) properties.get("answer")).get("value"));
        }
    }

    @Test void liveRhinoMapsExposeBackingPropertiesWithoutInvokingMapMethods() {
        Context cx = Context.enter();
        try {
            Scriptable scope = cx.initStandardObjects();
            Map<String, Object> data = new HashMap<>(); data.put("answer", 42);
            for (var entry : Map.of("sourceMap", new SourceMap(data), "channelMap", new ChannelMap(data, null),
                    "responseMap", new ResponseMap(data, null), "connectorMap", data).entrySet()) {
                ScriptableObject.putProperty(scope, entry.getKey(), Context.javaToJS(entry.getValue(), scope));
                Object value = cx.evaluateString(scope, entry.getKey(), "map-inspection", 1, null);
                assertEquals(NativeJavaMap.class, value.getClass());
                Map<?, ?> properties = (Map<?, ?>) new SafeInspector().inspect(value).get("properties");
                assertEquals(42, ((Map<?, ?>) properties.get("answer")).get("value"), entry.getKey());
            }
            Map<String, Object> hostile = new AbstractMap<>() {
                @Override public Set<Entry<String, Object>> entrySet() { throw new AssertionError("Never enumerate arbitrary map implementations"); }
            };
            assertEquals("opaque", new SafeInspector().inspect(Context.javaToJS(hostile, scope)).get("type"));
            assertEquals("opaque", new SafeInspector().inspect(Context.javaToJS(new SourceMap(hostile), scope)).get("type"));
        } finally { Context.exit(); }
    }

    @Test void wrapperSubclassesCannotRunTheirUnwrapHooks() {
        Context cx = Context.enter();
        try {
            Scriptable scope = cx.initStandardObjects();
            Object map = new NativeJavaMap(scope, new HashMap<>()) {
                @Override public Object unwrap() { throw new AssertionError("Never invoke subclass unwrap"); }
            };
            Object object = new NativeJavaObject() {
                @Override public Object unwrap() { throw new AssertionError("Never invoke subclass unwrap"); }
            };
            assertEquals("opaque", new SafeInspector().inspect(map).get("type"));
            assertEquals("opaque", new SafeInspector().inspect(object).get("type"));
        } finally { Context.exit(); }
    }

    @Test void engineReadOnlySourceMapsRemainOpaqueWithoutInspectingUntrustedDelegates() {
        Context cx = Context.enter();
        try {
            Scriptable scope = cx.initStandardObjects();
            Map<String, Object> data = new HashMap<>(Map.of("answer", 42));
            Map<String, Object> hostile = new AbstractMap<>() {
                @Override public Set<Entry<String, Object>> entrySet() { throw new AssertionError("Never enumerate an untrusted read-only delegate"); }
            };
            // Channel stores an unmodifiable source map before JavaScriptScopeUtil wraps it.
            for (Map<String, Object> backing : List.of(data, hostile)) {
                Object value = Context.javaToJS(new SourceMap(Collections.unmodifiableMap(backing)), scope);
                assertEquals(NativeJavaMap.class, value.getClass());
                assertEquals("opaque", new SafeInspector(() -> null).inspect(value).get("type"));
            }
        } finally { Context.exit(); }
    }

    @Test void nestedEngineResponseMapsInScopePreserveTheUserDataDepthLimit() {
        Context cx = Context.enter();
        try {
            Scriptable standard = cx.initStandardObjects();
            Scriptable scope = cx.newObject(standard);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("answer", 42);
            data.put("child", new HashMap<>(Map.of("deep", new HashMap<>(Map.of("hidden", 7)))));
            // ImmutableConnectorMessage and JavaScriptScopeUtil each add a ResponseMap wrapper.
            ResponseMap responseMap = new ResponseMap(new ResponseMap(data, null), null);
            ScriptableObject.putProperty(scope, "responseMap", Context.javaToJS(responseMap, scope));
            Map<?, ?> globals = (Map<?, ?>) new SafeInspector().scope(scope).get("properties");
            Map<?, ?> response = (Map<?, ?>) globals.get("responseMap");
            Map<?, ?> properties = (Map<?, ?>) response.get("properties");
            assertNotNull(properties, "Engine wrappers must not consume user-data depth");
            assertEquals(42, ((Map<?, ?>) properties.get("answer")).get("value"));
            Map<?, ?> child = (Map<?, ?>) properties.get("child");
            Map<?, ?> deep = (Map<?, ?>) ((Map<?, ?>) child.get("properties")).get("deep");
            assertEquals(true, deep.get("truncated"));
            assertFalse(deep.containsKey("properties"), "Nested user maps must still stop at the depth boundary");
        } finally { Context.exit(); }
    }

    @Test void transparentEngineWrapperCyclesAndChainsRemainBounded() throws Exception {
        ResponseMap cycle = new ResponseMap(new HashMap<>(), null);
        var delegate = ResponseMap.class.getDeclaredField("delegate");
        delegate.setAccessible(true);
        delegate.set(cycle, cycle);
        assertEquals("circular", new SafeInspector().inspect(cycle).get("type"));

        Map<String, Object> chain = new HashMap<>(Map.of("hidden", 42));
        for (int i = 0; i < 300; i++) chain = new ResponseMap(chain, null);
        assertEquals("truncated", new SafeInspector().inspect(chain).get("type"), "Transparent wrappers must still consume the node budget");
    }

    @Test void onlyTheCurrentEngineConfigurationMapIsTrustedThroughRhino() {
        Map<String, String> configured = Collections.unmodifiableMap(new HashMap<>(Map.of("answer", "42")));
        AtomicReference<Map<?, ?>> current = new AtomicReference<>(configured);
        Context cx = Context.enter();
        try {
            Scriptable scope = cx.initStandardObjects();
            Object wrapped = Context.javaToJS(configured, scope);
            assertEquals(NativeJavaMap.class, wrapped.getClass());
            Map<?, ?> properties = (Map<?, ?>) new SafeInspector(current::get).inspect(wrapped).get("properties");
            assertEquals("42", ((Map<?, ?>) properties.get("answer")).get("value"));

            Map<String, String> hostile = new AbstractMap<>() {
                @Override public Set<Entry<String, String>> entrySet() { throw new AssertionError("Never enumerate arbitrary unmodifiable delegates"); }
            };
            assertEquals("opaque", new SafeInspector(current::get).inspect(Context.javaToJS(Collections.unmodifiableMap(hostile), scope)).get("type"));
            assertEquals("opaque", new SafeInspector(current::get).inspect(Collections.unmodifiableMap(new HashMap<>(configured))).get("type"));
            current.set(Collections.unmodifiableMap(new HashMap<>()));
            assertEquals("opaque", new SafeInspector(current::get).inspect(wrapped).get("type"), "A replaced configuration snapshot must not display current data");
        } finally { Context.exit(); }
    }

    @Test void configurationMapUsesTheExistingPreviewBounds() {
        Map<String, String> data = new LinkedHashMap<>(); data.put("long", "x".repeat(100_000));
        for (int i = 0; i < 1000; i++) data.put("key" + i, "value");
        Map<String, String> configured = Collections.unmodifiableMap(data);
        Map<String, Object> result = new SafeInspector(() -> configured).inspect(configured);
        Map<?, ?> properties = (Map<?, ?>) result.get("properties");
        assertEquals(64, properties.size());
        assertTrue(((Map<?, ?>) properties.get("long")).get("value").toString().length() <= 2049);
        assertEquals(true, result.get("truncated"));
    }

    @Test void inheritedArrayGettersAndConversionHooksRemainUninvoked() {
        Context cx = Context.enter();
        try {
            Scriptable scope = cx.initStandardObjects();
            Object value = cx.evaluateString(scope,
                    "var calls=0; var arr=[]; arr.length=3; Object.defineProperty(Array.prototype,'0',{get:function(){calls++;throw 'getter';}});"
                    + "Object.defineProperty(arr,'1',{get:function(){calls++;throw 'getter';}}); arr[2]={toString:function(){calls++;throw 'convert';}}; arr;",
                    "array-inspection", 1, null);
            new SafeInspector().inspect(value);
            assertEquals(0.0, ScriptableObject.getProperty(scope, "calls"));
        } finally { Context.exit(); }
    }

    @Test void giantSparseJavascriptArrayDoesNotEnumerateEntireLength() {
        Context cx = Context.enter();
        try {
            Scriptable scope = cx.initStandardObjects();
            Object array = cx.evaluateString(scope, "var huge=[]; huge.length=1000000000; huge[1]='safe'; huge;", "inspection", 1, null);
            Map<String, Object> result = new SafeInspector().inspect(array);
            assertTrue(((Map<?, ?>) result.get("properties")).size() <= 64); assertEquals(true, result.get("truncated"));
        } finally { Context.exit(); }
    }

    @Test void xmlPreviewUsesBoundedDomWithoutJavascriptConversionHooks() {
        Context cx = Context.enter();
        try {
            Scriptable scope = cx.initStandardObjects();
            Object xml = cx.evaluateString(scope,
                    "var conversions=0; XML.prototype.toString=function(){conversions++; throw 'conversion';};" +
                    "XML.prototype.toXMLString=function(){conversions++; throw 'conversion';};" +
                    "new XML('<HL7Message version=\"2.5\"><PID><PID.3><PID.3.1>12345</PID.3.1></PID.3></PID></HL7Message>');", "xml-preview", 1, null);
            Map<String, Object> preview = new SafeInspector().inspect(xml);
            assertEquals("xml", preview.get("type")); assertEquals("HL7Message", preview.get("name"));
            assertTrue(preview.toString().contains("12345")); assertTrue(preview.toString().contains("version"));
            assertEquals(0.0, ScriptableObject.getProperty(scope, "conversions"));
        } finally { Context.exit(); }
    }

    @Test void xmlListsAndLargeXmlAreBounded() {
        Context cx = Context.enter();
        try {
            Scriptable scope = cx.initStandardObjects();
            ScriptableObject.putProperty(scope, "largeXml", "<root>" + "<field>" + "x".repeat(100_000) + "</field>".repeat(1) + "<field>value</field>".repeat(1000) + "</root>");
            Object xml = cx.evaluateString(scope, "new XML(largeXml);", "xml-preview", 1, null);
            Map<String, Object> preview = new SafeInspector().inspect(xml);
            assertEquals("xml", preview.get("type")); assertTrue(preview.toString().length() < 35_000); assertEquals(true, preview.get("truncated"));
            Object list = cx.evaluateString(scope, "new XML(largeXml).field;", "xml-preview", 1, null);
            Map<String, Object> listPreview = new SafeInspector().inspect(list);
            assertEquals("xml-list", listPreview.get("type")); assertTrue(((Map<?, ?>) listPreview.get("properties")).size() <= 64); assertEquals(true, listPreview.get("truncated"));
        } finally { Context.exit(); }
    }
}
