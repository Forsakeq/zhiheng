package com.yqsoft.service.auth;

import com.yqsoft.dao.SqlStore;
import com.yqsoft.resource.common.Rows;
import com.yqsoft.resource.profile.ProfileViews.PolicyVersion;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

/** Deployment gates shared by registration and the public client configuration. */
@Component
public class AuthDeploymentSettings {
    public static final String LOCAL_SERVICE_POLICY_PATH =
            "/api/v1/development/policies/service";

    private final SqlStore sql;
    private final Environment environment;

    public AuthDeploymentSettings(SqlStore sql, Environment environment) {
        this.sql = sql;
        this.environment = environment;
    }

    public boolean localDevelopment() {
        return isLocalDevelopment(environment);
    }

    public static boolean isLocalDevelopment(Environment environment) {
        return environment.acceptsProfiles(Profiles.of("local"))
                && !environment.acceptsProfiles(Profiles.of("prod"))
                && "DEV".equalsIgnoreCase(environment.getProperty("zhiheng.environment", "DEV"));
    }

    public static String verificationDelivery(Environment environment) {
        String delivery =
                environment
                        .getProperty("zhiheng.auth.verification-delivery", "SMTP")
                        .trim()
                        .toUpperCase(Locale.ROOT);
        if (!"SMTP".equals(delivery) && !"CONSOLE".equals(delivery)) {
            throw new IllegalStateException("验证码发送方式只支持 SMTP 或 CONSOLE");
        }
        if ("CONSOLE".equals(delivery) && !isLocalDevelopment(environment)) {
            throw new IllegalStateException("CONSOLE 验证码仅允许 local profile 与 DEV 环境同时启用");
        }
        return delivery;
    }

    public String verificationDelivery() {
        return verificationDelivery(environment);
    }

    public Optional<PolicyVersion> servicePolicy() {
        String version = environment.getProperty("zhiheng.auth.service-policy-version", "").trim();
        if (version.isEmpty()) {
            return Optional.empty();
        }
        String url = environment.getProperty("zhiheng.auth.service-policy-url", "").trim();
        if (validHttpsDocument(url)) {
            return Optional.of(new PolicyVersion("SERVICE", version, "服务说明", url));
        }
        // The local document is a real JSON resource rendered by the App, never a fabricated URL.
        if (localDevelopment() && url.isEmpty()) {
            return Optional.of(
                    new PolicyVersion("SERVICE", version, "知衡本地服务说明", LOCAL_SERVICE_POLICY_PATH));
        }
        return Optional.empty();
    }

    public boolean registrationEnabled() {
        if (!environment.getProperty("zhiheng.auth.registration-enabled", Boolean.class, false)
                || servicePolicy().isEmpty()) {
            return false;
        }
        // This override never changes database flags and cannot apply to TEST or PROD.
        if (localDevelopment()
                && environment.getProperty(
                        "zhiheng.auth.local-development-registration", Boolean.class, false)) {
            return true;
        }
        return sql.find(
                        "SELECT value_json FROM app_configuration WHERE environment=? AND"
                                + " config_key=? AND is_enabled=1 AND deleted_at IS NULL AND"
                                + " effective_at<=UTC_TIMESTAMP(3)",
                        environment.getProperty("zhiheng.environment", "DEV"),
                        "auth.registration_enabled")
                .map(row -> Rows.json(row, "value_json").path("booleanValue").asBoolean(false))
                .orElse(false);
    }

    private boolean validHttpsDocument(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getUserInfo() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
