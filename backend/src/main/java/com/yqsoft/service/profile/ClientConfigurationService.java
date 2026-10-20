package com.yqsoft.service.profile;

import com.yqsoft.dao.SqlStore;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.resource.common.Rows;
import com.yqsoft.resource.profile.ConfigurationView;
import com.yqsoft.resource.profile.ConfigurationView.FeatureView;
import com.yqsoft.resource.profile.ProfileViews.PolicyVersion;
import com.yqsoft.service.auth.AuthDeploymentSettings;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
public class ClientConfigurationService {
    private final SqlStore sql;
    private final Environment environment;
    private final AuthDeploymentSettings authentication;

    public ClientConfigurationService(
            SqlStore sql, Environment environment, AuthDeploymentSettings authentication) {
        this.sql = sql;
        this.environment = environment;
        this.authentication = authentication;
    }

    public ConfigurationView configuration(String platform, String appVersion, String osVersion) {
        if (!Set.of("ANDROID", "HARMONYOS", "IOS", "WEB").contains(platform)) {
            throw BusinessException.badRequest("UNSUPPORTED_PLATFORM", "不支持的平台");
        }
        String deployment = environment.getProperty("zhiheng.environment", "DEV");
        Map<String, Boolean> flags = new HashMap<>();
        for (Map<String, Object> row :
                sql.query(
                        "SELECT config_key,value_json FROM app_configuration WHERE environment=?"
                                + " AND scope='PUBLIC' AND is_enabled=1 AND deleted_at IS NULL AND"
                                + " effective_at<=UTC_TIMESTAMP(3)",
                        deployment)) {
            flags.put(
                    Rows.text(row, "config_key"),
                    Rows.json(row, "value_json").path("booleanValue").asBoolean(false));
        }
        List<FeatureView> features = new ArrayList<>();
        Map<String, String> featureKeys = new LinkedHashMap<>();
        featureKeys.put("AI_PLAN", "feature.ai_plan");
        featureKeys.put("AI_RECORD", "feature.ai_record");
        featureKeys.put("AI_CHAT", "feature.ai_chat");
        featureKeys.put("WEEKLY_REVIEW", "feature.weekly_review");
        featureKeys.put("LOCAL_REMINDER", "feature.local_reminder");
        featureKeys.put("WATCH_TASK", "feature.watch_task");
        featureKeys.put("EXTERNAL_HEALTH", "feature.external_health");
        featureKeys.put("VOICE", "feature.voice");
        featureKeys.put("COURSE_IMPORT", "feature.course_import");
        featureKeys.forEach(
                (code, key) -> {
                    boolean enabled = Boolean.TRUE.equals(flags.get(key));
                    // P2 adapters are not implemented: a database flag cannot fabricate capability.
                    if (Set.of("WATCH_TASK", "EXTERNAL_HEALTH", "VOICE", "COURSE_IMPORT")
                            .contains(code)) enabled = false;
                    if (Set.of("AI_PLAN", "AI_RECORD", "AI_CHAT", "WEEKLY_REVIEW").contains(code)) {
                        enabled =
                                enabled
                                        && environment.getProperty(
                                                "zhiheng.ai.enabled", Boolean.class, false)
                                        && !environment
                                                .getProperty("zhiheng.ai.api-key", "")
                                                .isBlank()
                                        && !environment
                                                .getProperty("zhiheng.ai.base-url", "")
                                                .isBlank()
                                        && !environment
                                                .getProperty("zhiheng.ai.model", "")
                                                .isBlank();
                    }
                    String purpose =
                            code.startsWith("AI_") || "WEEKLY_REVIEW".equals(code)
                                    ? "AI_PROCESSING"
                                    : "LOCAL_REMINDER".equals(code)
                                            ? "NOTIFICATION"
                                            : "EXTERNAL_HEALTH".equals(code)
                                                    ? "EXTERNAL_HEALTH"
                                                    : null;
                    features.add(
                            new FeatureView(code, enabled, purpose, enabled ? null : "尚未配置或验证此能力"));
                });
        List<PolicyVersion> policies = new ArrayList<>();
        authentication.servicePolicy().ifPresent(policies::add);
        return new ConfigurationView(
                "1",
                "0.1.0",
                Instant.now(),
                authentication.localDevelopment(),
                new ConfigurationView.AuthenticationView(
                        authentication.registrationEnabled(), authentication.verificationDelivery()),
                policies,
                features,
                List.of("SLEEP", "ACTIVITY", "MEAL", "ENERGY"),
                List.of(
                        "RECORD_CREATE",
                        "RECORD_REPLACE",
                        "RECORD_DELETE",
                        "RECORD_RESTORE",
                        "TAG_CREATE",
                        "TAG_PATCH",
                        "TAG_DELETE",
                        "RECORD_TAGS_REPLACE",
                        "TASK_COMPLETE",
                        "TASK_SKIP",
                        "TASK_REOPEN",
                        "TASK_RECORD_LINKS_REPLACE",
                        "GOAL_CREATE",
                        "GOAL_PATCH",
                        "GOAL_CLOSE",
                        "AVAILABILITY_CREATE",
                        "AVAILABILITY_REPLACE",
                        "AVAILABILITY_DELETE",
                        "EXCEPTION_PUT",
                        "EXCEPTION_DELETE"));
    }
}
