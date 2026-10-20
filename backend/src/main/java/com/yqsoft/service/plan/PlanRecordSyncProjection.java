package com.yqsoft.service.plan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yqsoft.dao.SqlStore;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.persistence.SyncProjectionProvider;
import com.yqsoft.resource.common.Rows;
import com.yqsoft.security.AuthContext;
import com.yqsoft.service.record.RecordService;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class PlanRecordSyncProjection implements SyncProjectionProvider {
    private final RecordService records;
    private final PlanService plans;
    private final SqlStore store;
    private final ObjectMapper mapper;

    public PlanRecordSyncProjection(
            RecordService records, PlanService plans, SqlStore store, ObjectMapper mapper) {
        this.records = records;
        this.plans = plans;
        this.store = store;
        this.mapper = mapper;
    }

    public Set<String> entityTypes() {
        return Set.of("RECORD", "RECORD_TAG", "PLAN", "PLAN_VERSION", "TASK");
    }

    public Map<String, Object> project(String type, long id) {
        Object view =
                switch (type) {
                    case "RECORD" -> records.get(id);
                    case "RECORD_TAG" -> records.tag(id);
                    case "PLAN" -> plans.getPlan(id).plan();
                    case "PLAN_VERSION" ->
                            plans.getVersion(
                                    Rows.number(
                                            store.owned("plan_version", id, AuthContext.userId()),
                                            "plan_id"),
                                    id);
                    case "TASK" -> plans.task(id);
                    default ->
                            throw BusinessException.badRequest("UNKNOWN_ENTITY_TYPE", "同步对象不受支持");
                };
        return mapper.convertValue(view, new TypeReference<>() {});
    }
}
