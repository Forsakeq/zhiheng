package com.yqsoft.resource.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Rows {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private Rows() {}

    public static Map<String, Object> map(Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("Key/value pairs are required");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put((String) pairs[index], pairs[index + 1]);
        }
        return result;
    }

    public static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }

    public static long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null
                ? 0
                : value instanceof Number number
                        ? number.longValue()
                        : Long.parseLong(value.toString());
    }

    public static int integer(Map<String, Object> row, String key) {
        return Math.toIntExact(number(row, key));
    }

    public static boolean bool(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Boolean bool ? bool : number(row, key) != 0;
    }

    public static Instant instant(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
        }
        if (value instanceof LocalDateTime local) {
            return local.toInstant(ZoneOffset.UTC);
        }
        return Instant.parse(value.toString());
    }

    public static LocalDate date(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        return value instanceof LocalDate date ? date : LocalDate.parse(value.toString());
    }

    public static JsonNode json(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof JsonNode node) {
                return node;
            }
            String text =
                    value instanceof byte[] bytes
                            ? new String(bytes, StandardCharsets.UTF_8)
                            : value.toString();
            return JSON.readTree(text);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored JSON is invalid");
        }
    }

    public static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
