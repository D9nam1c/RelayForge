package io.relayforge.dispatch;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Optional deliberately-small filter language: a JSON object of top-level exact field matches. */
final class PayloadFilter {
    private final ObjectMapper mapper;

    PayloadFilter(ObjectMapper mapper) { this.mapper = mapper; }

    boolean matches(String filterJson, byte[] payload) {
        if (filterJson == null || filterJson.isBlank() || "{}".equals(filterJson.trim())) return true;
        try {
            JsonNode filter = mapper.readTree(filterJson);
            JsonNode value = mapper.readTree(payload);
            if (!filter.isObject() || !value.isObject()) return false;
            var fields = filter.properties().iterator();
            while (fields.hasNext()) {
                var expected = fields.next();
                if (!expected.getValue().equals(value.get(expected.getKey()))) return false;
            }
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
