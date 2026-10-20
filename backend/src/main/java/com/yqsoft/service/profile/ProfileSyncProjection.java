package com.yqsoft.service.profile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yqsoft.dao.SqlStore;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.persistence.SyncProjectionProvider;
import com.yqsoft.security.AuthContext;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class ProfileSyncProjection implements SyncProjectionProvider {
    private final ProfileService service;
    private final SqlStore sql;
    private final ObjectMapper mapper;

    public ProfileSyncProjection(ProfileService service, SqlStore sql, ObjectMapper mapper) {
        this.service = service;
        this.sql = sql;
        this.mapper = mapper;
    }

    @Override
    public Set<String> entityTypes() {
        return Set.of(
                "PROFILE", "PREFERENCE", "GOAL", "AVAILABILITY_RULE", "AVAILABILITY_EXCEPTION");
    }

    public boolean supports(String entityType) {
        return Set.of(
                        "PROFILE",
                        "PREFERENCE",
                        "GOAL",
                        "AVAILABILITY_RULE",
                        "AVAILABILITY_EXCEPTION")
                .contains(entityType);
    }

    @Override
    public Map<String, Object> project(String type, long id) {
        long user = AuthContext.userId();
        Object view =
                switch (type) {
                    case "PROFILE" -> service.profileView(sql.owned("user_profile", id, user));
                    case "PREFERENCE" ->
                            service.preferenceView(sql.owned("user_preference", id, user));
                    case "GOAL" -> service.goalView(sql.owned("user_goal", id, user));
                    case "AVAILABILITY_RULE" ->
                            service.ruleView(sql.owned("availability_rule", id, user));
                    case "AVAILABILITY_EXCEPTION" ->
                            service.exceptionView(sql.owned("availability_exception", id, user));
                    default -> throw BusinessException.badRequest("UNSUPPORTED_TYPE", "不支持的同步投影");
                };
        return mapper.convertValue(view, new TypeReference<Map<String, Object>>() {});
    }
}
