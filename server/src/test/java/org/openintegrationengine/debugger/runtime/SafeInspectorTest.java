package org.openintegrationengine.debugger.runtime;

import org.junit.jupiter.api.Test;
import org.mozilla.javascript.*;
import com.mirth.connect.server.userutil.SourceMap;
import com.mirth.connect.server.userutil.ChannelMap;
import com.mirth.connect.userutil.ResponseMap;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
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
