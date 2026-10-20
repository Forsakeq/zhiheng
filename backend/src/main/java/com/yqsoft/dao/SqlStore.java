package com.yqsoft.dao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.persistence.SchemaMetadata;
import com.yqsoft.resource.common.Rows;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Repository
public class SqlStore {

    private final JdbcTemplate jdbc;
    private final SchemaMetadata metadata;
    private final ObjectMapper mapper;
    private final Clock clock;

    public SqlStore(JdbcTemplate jdbc, SchemaMetadata metadata, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.metadata = metadata;
        this.mapper = mapper;
        this.clock = clock;
    }

    public SchemaMetadata metadata() {
        return metadata;
    }

    public List<Map<String, Object>> query(String sql, Object... arguments) {
        return jdbc.query(
                sql,
                (result, index) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    var columns = result.getMetaData();
                    for (int column = 1; column <= columns.getColumnCount(); column++) {
                        row.put(
                                columns.getColumnLabel(column).toLowerCase(Locale.ROOT),
                                result.getObject(column));
                    }
                    return row;
                },
                normalized(arguments));
    }

    public Optional<Map<String, Object>> find(String sql, Object... arguments) {
        return query(sql, arguments).stream().findFirst();
    }

    public Map<String, Object> one(String sql, Object... arguments) {
        return find(sql, arguments).orElseThrow(BusinessException::notFound);
    }

    public long count(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, normalized(arguments));
        return value == null ? 0 : value;
    }

    public int execute(String sql, Object... arguments) {
        return jdbc.update(sql, normalized(arguments));
    }

    public long insert(String table, Map<String, Object> input) {
        Map<String, Object> values = new LinkedHashMap<>(input);
        Instant now = clock.instant();
        if (metadata.hasColumn(table, "created_at")) {
            values.putIfAbsent("created_at", now);
        }
        if (metadata.hasColumn(table, "updated_at")) {
            values.putIfAbsent("updated_at", now);
        }
        List<String> columns =
                values.keySet().stream().map(name -> metadata.column(table, name)).toList();
        String sql =
                "INSERT INTO "
                        + metadata.table(table)
                        + " ("
                        + String.join(", ", columns)
                        + ") VALUES ("
                        + String.join(", ", java.util.Collections.nCopies(columns.size(), "?"))
                        + ")";
        GeneratedKeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    var statement =
                            connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                    int index = 1;
                    for (Object value : values.values()) {
                        statement.setObject(index++, normalize(value));
                    }
                    return statement;
                },
                holder);
        if (!metadata.hasColumn(table, "id")) {
            Object key = values.get(table.equals("user_sync_clock") ? "user_id" : "record_id");
            return key instanceof Number number ? number.longValue() : 0;
        }
        Number key = holder.getKey();
        return key == null ? 0 : key.longValue();
    }

    public long update(String table, long id, long expectedVersion, Map<String, Object> input) {
        if (!metadata.hasColumn(table, "row_version")) {
            throw new IllegalArgumentException("Versioned update requires a mutable entity");
        }
        Map<String, Object> values = new LinkedHashMap<>(input);
        if (values.keySet().stream().anyMatch(SetOfImmutable.COLUMNS::contains)) {
            throw new IllegalArgumentException(
                    "Ownership, identity and version columns are immutable");
        }
        values.put("updated_at", clock.instant());
        List<Object> arguments = new ArrayList<>();
        List<String> assignments = new ArrayList<>();
        values.forEach(
                (column, value) -> {
                    assignments.add(metadata.column(table, column) + " = ?");
                    arguments.add(value);
                });
        assignments.add("row_version = row_version + 1");
        arguments.add(id);
        arguments.add(expectedVersion);
        String sql =
                "UPDATE "
                        + metadata.table(table)
                        + " SET "
                        + String.join(", ", assignments)
                        + " WHERE id = ? AND row_version = ?";
        if (execute(sql, arguments.toArray()) != 1) {
            throw BusinessException.conflict("ROW_VERSION_CONFLICT", "数据已更新，请重新读取后确认");
        }
        return Math.addExact(expectedVersion, 1);
    }

    public Map<String, Object> owned(String table, long id, long userId) {
        return ownedRow(table, id, userId, false, false);
    }

    public Map<String, Object> ownedIncludingDeleted(String table, long id, long userId) {
        return ownedRow(table, id, userId, true, false);
    }

    public Map<String, Object> lockOwned(String table, long id, long userId) {
        return ownedRow(table, id, userId, false, true);
    }

    private Map<String, Object> ownedRow(
            String table, long id, long userId, boolean deleted, boolean lock) {
        metadata.column(table, "user_id");
        String sql = "SELECT * FROM " + metadata.table(table) + " WHERE id = ? AND user_id = ?";
        if (!deleted && metadata.hasColumn(table, "deleted_at")) {
            sql += " AND deleted_at IS NULL";
        }
        return one(sql + (lock ? " FOR UPDATE" : ""), id, userId);
    }

    private Object[] normalized(Object[] values) {
        Object[] result = new Object[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = normalize(values[index]);
        }
        return result;
    }

    private Object normalize(Object value) {
        if (value instanceof Instant instant) {
            return Rows.utc(instant);
        }
        if (value instanceof JsonNode || value instanceof Map<?, ?> || value instanceof List<?>) {
            try {
                return mapper.writeValueAsString(value);
            } catch (Exception exception) {
                throw new IllegalArgumentException("Cannot serialize a database JSON value");
            }
        }
        return value;
    }

    private static final class SetOfImmutable {
        private static final java.util.Set<String> COLUMNS =
                java.util.Set.of("id", "user_id", "created_at", "row_version");
    }
}
