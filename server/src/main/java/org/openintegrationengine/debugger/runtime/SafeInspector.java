package org.openintegrationengine.debugger.runtime;

import com.mirth.connect.server.controllers.ConfigurationController;
import org.mozilla.javascript.*;
import org.mozilla.javascript.xmlimpl.XMLLibImpl;
import org.w3c.dom.CharacterData;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Supplier;

/** Bounded JSON previews. Never calls user getters, conversion hooks or arbitrary Java toString. */
public final class SafeInspector {
    private static final Set<Class<?>> SAFE_SCOPES = Set.of(NativeObject.class, NativeArray.class, NativeCall.class, ImporterTopLevel.class);
    private static final Set<String> SAFE_MAPS = Set.of("java.util.HashMap", "java.util.LinkedHashMap",
            "java.util.TreeMap", "java.util.concurrent.ConcurrentHashMap");
    private static final Set<String> ENGINE_MAPS = Set.of("com.mirth.connect.server.userutil.SourceMap",
            "com.mirth.connect.server.userutil.ChannelMap", "com.mirth.connect.userutil.ResponseMap");
    private static final Object ACCESSOR = new Object();
    private final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
    private final Supplier<Map<?, ?>> currentConfiguration;
    private int remaining = 256;
    private int characters = 24_000;
    private long deadline;

    public SafeInspector() { this(() -> ConfigurationController.getInstance().getConfigurationMap()); }
    SafeInspector(Supplier<Map<?, ?>> currentConfiguration) { this.currentConfiguration = currentConfiguration; }

    public Map<String, Object> inspect(Object value) {
        // Java evaluates arguments after constructing the receiver; expression execution is not inspection time.
        deadline = System.nanoTime() + 50_000_000L;
        return preview(value, 0);
    }

    public Map<String, Object> scope(Scriptable value) { return inspect(value); }

    /** Reads an own, ordinary data property only; callers must not fall back to Scriptable.getProperty. */
    static Object ownData(Scriptable value, String name) {
        if (!(value instanceof ScriptableObject object) || !SAFE_SCOPES.contains(value.getClass()))
            return Scriptable.NOT_FOUND;
        if (accessor(object, name, 0)) return ACCESSOR;
        return object.get(name, object);
    }

    static Object unwrap(Object value) {
        if (value != null && (value.getClass() == NativeJavaObject.class || value.getClass() == NativeJavaArray.class
                || value.getClass() == NativeJavaMap.class))
            return ((Wrapper) value).unwrap();
        return value;
    }

    static boolean engineClass(Object value, String name) {
        if (value == null || !value.getClass().getName().equals(name)) return false;
        try { return value.getClass() == Class.forName(name, false, SafeInspector.class.getClassLoader()); }
        catch (ClassNotFoundException unavailable) { return false; }
    }

    private static boolean accessor(ScriptableObject object, String name, int index) {
        Object getter = object.getGetterOrSetter(name, index, false);
        Object setter = object.getGetterOrSetter(name, index, true);
        return (getter != null && getter != Undefined.instance) || (setter != null && setter != Undefined.instance);
    }

    private boolean configurationMap(Map<?, ?> map) {
        // Only the engine's current immutable snapshot is trusted. An arbitrary
        // unmodifiable wrapper can still delegate reads to user-supplied code.
        return map.getClass().getName().equals("java.util.Collections$UnmodifiableMap")
                && map == currentConfiguration.get();
    }

    private Map<String, Object> preview(Object raw, int depth) {
        if (--remaining < 0 || characters <= 0 || System.nanoTime() > deadline) return marker("truncated");
        if (raw == ACCESSOR) return marker("accessor");
        Object value = unwrap(raw);
        if (value == null) return marker("null");
        if (value == Undefined.instance || value == Scriptable.NOT_FOUND) return marker("undefined");
        if (value instanceof String string) return scalar("string", clipped(string));
        if (value instanceof Boolean bool) return scalar("boolean", bool);
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte)
            return scalar("number", value);
        if (value instanceof Double number) return scalar("number", Double.isFinite(number) ? number : number.toString());
        if (value instanceof Float number) return scalar("number", Float.isFinite(number) ? number : number.toString());
        if (value instanceof Character character) return scalar("string", character.toString());
        if (value instanceof ConsString string) return string.length() > 2048 ? Map.of("type", "string", "truncated", true, "length", string.length()) : scalar("string", clipped(string.toString()));
        if (seen.put(value, Boolean.TRUE) != null) return marker("circular");
        String className = value.getClass().getName();
        if (engineClass(value, "org.mozilla.javascript.xmlimpl.XML")) {
            // Rhino exposes its DOM directly; do not invoke XML.toString/toXMLString or JavaScript hooks.
            return xmlNode(XMLLibImpl.toDomNode(value), 0);
        }
        if (engineClass(value, "org.mozilla.javascript.xmlimpl.XMLList")) {
            Scriptable list = (Scriptable) value;
            Map<String, Object> items = new LinkedHashMap<>();
            int index = 0;
            while (room(index) && list.has(index, list)) { items.put(Integer.toString(index), preview(list.get(index, list), depth + 1)); index++; }
            return Map.of("type", "xml-list", "properties", items, "truncated", list.has(index, list));
        }
        if (depth >= 3) return Map.of("type", "object", "className", className, "truncated", true);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "object");
        result.put("className", className);
        Map<String, Object> properties = new LinkedHashMap<>();
        result.put("properties", properties);
        try {
            if (value.getClass() == NativeArray.class) {
                NativeArray array = (NativeArray) value;
                result.put("length", array.getLength());
                for (int i = 0; i < array.getLength() && room(i); i++)
                    properties.put(Integer.toString(i), preview(accessor(array, null, i) ? ACCESSOR : array.get(i, array), depth + 1));
                if (array.getLength() > properties.size()) result.put("truncated", true);
            } else if (value instanceof ScriptableObject object && SAFE_SCOPES.contains(value.getClass())) {
                // Avoid getIds allocating an unbounded full key array for large user objects.
                if (object.size() > 4096) { result.put("truncated", true); return result; }
                // getIds is non-invoking for these exact Rhino implementations. Inherited library globals are excluded.
                int count = 0;
                for (Object id : object.getIds()) {
                    if (!room(count++)) { result.put("truncated", true); break; }
                    if (id instanceof String name) {
                        properties.put(clipped(name), preview(accessor(object, name, 0) ? ACCESSOR : object.get(name, object), depth + 1));
                    } else if (id instanceof Integer index) {
                        properties.put(index.toString(), preview(accessor(object, null, index) ? ACCESSOR : object.get(index, object), depth + 1));
                    }
                }
            } else if (value instanceof Map<?, ?> map && (SAFE_MAPS.contains(className) || configurationMap(map))) {
                int count = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!room(count++)) { result.put("truncated", true); break; }
                    Object key = entry.getKey();
                    if (key instanceof String name) properties.put(clipped(name), preview(entry.getValue(), depth + 1));
                    else if (key instanceof Integer || key instanceof Long) properties.put(key.toString(), preview(entry.getValue(), depth + 1));
                }
            } else if (ENGINE_MAPS.contains(className) && engineClass(value, className)) {
                // Public constructors accept arbitrary Maps. Read the field, then reapply the exact-type allowlist.
                Field delegate = value.getClass().getDeclaredField("delegate");
                delegate.setAccessible(true);
                return preview(delegate.get(value), depth);
            } else if (engineClass(value, "com.mirth.connect.server.util.GlobalVariableStore") ||
                    engineClass(value, "com.mirth.connect.server.util.GlobalChannelVariableStore")) {
                String field = className.endsWith("GlobalChannelVariableStore") ? "globalChannelVariableMap" : "globalVariableMap";
                // These engine-owned public fields hold ConcurrentHashMap; no reflective method invocation.
                return preview(value.getClass().getField(field).get(value), depth + 1);
            } else if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                result.put("length", length);
                for (int i = 0; i < length && room(i); i++) properties.put(Integer.toString(i), preview(Array.get(value, i), depth + 1));
                if (length > properties.size()) result.put("truncated", true);
            } else {
                result.put("type", value instanceof Function ? "function" : "opaque");
                result.remove("properties");
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            result.put("truncated", true);
            result.put("notice", "Value changed or is unavailable during inspection");
        }
        return result;
    }

    private Map<String, Object> xmlNode(Node node, int depth) {
        if (node == null) return marker("null");
        // A user can wrap arbitrary DOM implementations in E4X. Only inspect the JDK implementation.
        if (!"java.xml".equals(node.getClass().getModule().getName())) return Map.of("type", "opaque", "className", node.getClass().getName());
        if (--remaining < 0 || characters <= 0 || System.nanoTime() > deadline) return marker("truncated");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "xml"); result.put("name", clipped(node.getNodeName()));
        if (node instanceof CharacterData data) {
            int length = Math.min(data.getLength(), Math.min(2048, Math.max(0, characters)));
            result.put("value", clipped(data.substringData(0, length)));
            if (length < data.getLength()) result.put("truncated", true);
            return result;
        }
        if (depth >= 3) {
            // A direct text leaf remains useful for HL7 components at the depth boundary.
            Node first = node.getFirstChild();
            if (first != null && "java.xml".equals(first.getClass().getModule().getName()) && first instanceof CharacterData && first.getNextSibling() == null)
                result.put("text", xmlNode(first, depth + 1));
            else if (first != null) result.put("truncated", true);
            return result;
        }
        NamedNodeMap attributes = node.getAttributes();
        if (attributes != null && attributes.getLength() > 0) {
            List<Map<String, Object>> values = new ArrayList<>();
            for (int i = 0; i < attributes.getLength() && room(i); i++) values.add(xmlNode(attributes.item(i), depth + 1));
            result.put("attributes", values);
            if (values.size() < attributes.getLength()) result.put("truncated", true);
        }
        List<Map<String, Object>> children = new ArrayList<>();
        Node child = node.getFirstChild();
        while (child != null && room(children.size())) {
            children.add(xmlNode(child, depth + 1));
            if (!"java.xml".equals(child.getClass().getModule().getName())) { child = null; break; }
            child = child.getNextSibling();
        }
        if (!children.isEmpty()) result.put("children", children);
        if (child != null) result.put("truncated", true);
        return result;
    }

    private boolean room(int count) { return count < 64 && remaining > 0 && characters > 0 && System.nanoTime() <= deadline; }
    private String clipped(String value) {
        int length = Math.min(value.length(), Math.min(2048, Math.max(0, characters)));
        characters -= length;
        return value.substring(0, length) + (length < value.length() ? "…" : "");
    }
    private static Map<String, Object> marker(String type) { return Map.of("type", type); }
    private static Map<String, Object> scalar(String type, Object value) { return Map.of("type", type, "value", value); }
}
