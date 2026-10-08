package org.openintegrationengine.debugger.api;

import java.util.Map;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private Json() {}
    static Map<String, Object> read(String body) {
        if (body == null || body.length() > 65536) throw new IllegalArgumentException("Request must be a JSON object of at most 64 KiB");
        try {
            int depth = 0;
            try (JsonParser parser = MAPPER.createParser(body)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) throw new IllegalArgumentException("Expected a JSON object");
                depth++;
                for (JsonToken token; (token = parser.nextToken()) != null;) {
                    if (token.isStructStart() && ++depth > 24) throw new IllegalArgumentException("JSON nesting exceeds 24 levels");
                    if (token.isStructEnd()) depth--;
                }
            }
            return MAPPER.readValue(body, new TypeReference<Map<String, Object>>() {});
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("Invalid JSON request"); }
    }
    static String write(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Unable to encode debugger response", e); }
    }
}
