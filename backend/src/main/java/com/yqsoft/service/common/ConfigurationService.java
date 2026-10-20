package com.yqsoft.service.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.yqsoft.config.AppProperties;
import com.yqsoft.dao.SqlStore;
import com.yqsoft.resource.common.Rows;

import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
public class ConfigurationService {

    private final SqlStore store;
    private final AppProperties properties;
    private final Clock clock;

    public ConfigurationService(SqlStore store, AppProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    public JsonNode value(String key) {
        return store.find(
                        "SELECT value_json FROM app_configuration WHERE environment=? AND"
                                + " config_key=? AND is_enabled=1 AND deleted_at IS NULL AND"
                                + " effective_at<=?",
                        properties.getEnvironment(),
                        key,
                        clock.instant())
                .map(row -> Rows.json(row, "value_json"))
                .orElse(null);
    }

    public boolean enabled(String key, boolean fallback) {
        JsonNode node = value(key);
        return node == null ? fallback : node.path("booleanValue").asBoolean(fallback);
    }

    public int integer(String key, int fallback) {
        JsonNode node = value(key);
        return node == null ? fallback : node.path("numberValue").asInt(fallback);
    }
}
