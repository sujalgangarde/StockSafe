package com.stocksafe.core.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/** Tiny JSON helper for jsonb columns. */
public final class Json {
    private static final ObjectMapper OM = new ObjectMapper();

    private Json() {}

    public static String write(Object o) {
        try {
            return OM.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readMap(String s) {
        if (s == null || s.isBlank()) return Map.of();
        try {
            return OM.readValue(s, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
