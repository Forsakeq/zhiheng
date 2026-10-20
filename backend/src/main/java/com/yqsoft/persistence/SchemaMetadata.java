package com.yqsoft.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Component
public class SchemaMetadata {

    private final Map<String, Set<String>> columns = new LinkedHashMap<>();

    public SchemaMetadata(ObjectMapper mapper) throws IOException {
        try (var input = new ClassPathResource("db/schema-metadata.json").getInputStream()) {
            JsonNode root = mapper.readTree(input);
            root.fields()
                    .forEachRemaining(
                            entry -> {
                                Set<String> names = new LinkedHashSet<>();
                                entry.getValue()
                                        .get("columns")
                                        .forEach(column -> names.add(column.get("name").asText()));
                                columns.put(entry.getKey(), Set.copyOf(names));
                            });
        }
    }

    public Set<String> tables() {
        return Set.copyOf(columns.keySet());
    }

    public Set<String> columns(String table) {
        Set<String> names = columns.get(table);
        if (names == null) {
            throw new IllegalArgumentException("Unregistered database table");
        }
        return names;
    }

    public boolean hasColumn(String table, String column) {
        return columns(table).contains(column);
    }

    public String table(String table) {
        columns(table);
        return "`" + table + "`";
    }

    public String column(String table, String column) {
        if (!hasColumn(table, column)) {
            throw new IllegalArgumentException("Unregistered database column");
        }
        return "`" + column + "`";
    }
}
