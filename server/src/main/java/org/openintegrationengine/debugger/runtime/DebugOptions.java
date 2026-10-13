package org.openintegrationengine.debugger.runtime;

import java.util.*;
import java.util.concurrent.TimeUnit;

/** Input parsing and immutable request snapshots, independent of runtime locks. */
final class DebugOptions {
    private DebugOptions() {}

    static String creationRequestId(Map<String, Object> options) {
        if (!options.containsKey("requestId")) return null;
        if (!(options.get("requestId") instanceof String value))
            throw new IllegalArgumentException("requestId must be text");
        requireText(value, "requestId", 128);
        return value;
    }

    static long creationDeadline(Map<String, Object> options) {
        Object value = options.get("requestExpiresAt");
        if (!(value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long))
            throw new IllegalArgumentException(
                    "requestExpiresAt must be the integer deadline from capabilities");
        return ((Number) value).longValue();
    }

    static Map<String, Object> immutableOptions(Map<String, Object> options) {
        Map<String, Object> copy = new LinkedHashMap<>();
        options.forEach((key, value) -> copy.put(key, immutableOption(value)));
        return Collections.unmodifiableMap(copy);
    }

    static Object immutableOption(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(key, immutableOption(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list)
            return Collections.unmodifiableList(
                    list.stream().map(DebugOptions::immutableOption).toList());
        return value;
    }

    static List<String> expressions(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list) || list.size() > 16)
            throw new IllegalArgumentException("At most 16 watch expressions are permitted");
        List<String> result = new ArrayList<>();
        for (Object value : list) {
            if (!(value instanceof String text))
                throw new IllegalArgumentException("Watch expressions must be text");
            result.add(boundedExpression(text));
        }
        return List.copyOf(result);
    }

    static long integer(Object value, String name, long minimum, long maximum) {
        if (!(value instanceof Byte
                        || value instanceof Short
                        || value instanceof Integer
                        || value instanceof Long)
                || ((Number) value).longValue() < minimum
                || ((Number) value).longValue() > maximum)
            throw new IllegalArgumentException(
                    name + " must be an integer from " + minimum + " to " + maximum);
        return ((Number) value).longValue();
    }

    static long now() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }

    static String boundedExpression(String expression) {
        requireText(expression, "expression", 4096);
        return expression;
    }

    static void requireText(String value, String name, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum)
            throw new IllegalArgumentException(name + " must contain 1–" + maximum + " characters");
    }

    static String requiredString(Map<String, Object> map, String key) {
        String value = optionalString(map, key);
        if (value == null) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    static String optionalString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof String text && !text.isBlank()
                ? text
                : value instanceof Number number ? number.toString() : null;
    }

    static long number(Map<String, Object> map, String key, long fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.longValue() : fallback;
    }

    static boolean bool(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        return value instanceof Boolean bool ? bool : fallback;
    }
}
