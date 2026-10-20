package com.yqsoft.service.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yqsoft.dao.SqlStore;
import com.yqsoft.resource.common.Rows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@SpringBootTest(
        properties = {
            "zhiheng.environment=DEV",
            "zhiheng.auth.registration-enabled=true",
            "zhiheng.auth.service-policy-version=test-service-v1",
            "zhiheng.auth.consent-policy-version=test-consent-v1",
            "zhiheng.jobs-enabled=false"
        })
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named = "ZH_TEST_DB_URL", matches = ".+")
class AuthProfileMySqlIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("ZH_TEST_DB_URL"));
        registry.add(
                "spring.datasource.username",
                () -> System.getenv().getOrDefault("ZH_TEST_DB_USERNAME", "root"));
        registry.add(
                "spring.datasource.password",
                () -> System.getenv().getOrDefault("ZH_TEST_DB_PASSWORD", ""));
    }

    @Autowired MockMvc mvc;
    @Autowired SqlStore sql;
    @Autowired ObjectMapper mapper;
    @MockBean VerificationSender sender;
    private final Map<String, String> codes = new ConcurrentHashMap<>();

    @BeforeEach
    void enableTestOnlyRegistration() {
        sql.execute(
                "UPDATE app_configuration SET value_json=JSON_OBJECT('booleanValue',true) "
                        + "WHERE environment='DEV' AND config_key='auth.registration_enabled'");
        doAnswer(
                        invocation -> {
                            codes.put(invocation.getArgument(0), invocation.getArgument(1));
                            return null;
                        })
                .when(sender)
                .send(anyString(), anyString(), anyString());
    }

    @Test
    void realRegistrationAndRefreshRotationDetectReuse() throws Exception {
        JsonNode tokens = register();
        String refresh = tokens.path("refreshToken").asText();
        String key = tokens.path("testInstallationKey").asText();
        JsonNode rotated =
                json(
                        mvc.perform(
                                        post("/api/v1/auth/refresh")
                                                .contentType("application/json")
                                                .content(
                                                        mapper.writeValueAsString(
                                                                Rows.map(
                                                                        "refreshToken",
                                                                        refresh,
                                                                        "installationKey",
                                                                        key,
                                                                        "clientAudience",
                                                                        "MOBILE"))))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        assertEquals("OK", rotated.path("code").asText());
        assertEquals("2", rotated.path("data").path("generation").asText());
        var reused =
                mvc.perform(
                                post("/api/v1/auth/refresh")
                                        .contentType("application/json")
                                        .content(
                                                mapper.writeValueAsString(
                                                        Rows.map(
                                                                "refreshToken",
                                                                refresh,
                                                                "installationKey",
                                                                key,
                                                                "clientAudience",
                                                                "MOBILE"))))
                        .andReturn();
        assertEquals(401, reused.getResponse().getStatus());
        assertEquals(
                "REFRESH_REUSED",
                json(reused.getResponse().getContentAsString()).path("code").asText());
        var denied =
                mvc.perform(
                                get("/api/v1/me")
                                        .header(
                                                "Authorization",
                                                "Bearer "
                                                        + rotated.path("data")
                                                                .path("accessToken")
                                                                .asText()))
                        .andReturn();
        assertEquals(401, denied.getResponse().getStatus());
    }

    @Test
    void wrongCodePersistsAttemptAndNeverReturnsPlaintextCode() throws Exception {
        String email = email();
        JsonNode challenge = challenge(email);
        assertFalse(challenge.has("verificationCode"));
        assertFalse(challenge.has("code"));
        String wrong = "000000".equals(codes.get(email)) ? "111111" : "000000";
        var response =
                mvc.perform(
                                post("/api/v1/auth/register")
                                        .contentType("application/json")
                                        .content(
                                                mapper.writeValueAsString(
                                                        registration(
                                                                email,
                                                                challenge
                                                                        .path("verificationId")
                                                                        .asText(),
                                                                wrong,
                                                                UUID.randomUUID().toString()))))
                        .andReturn();
        assertEquals(401, response.getResponse().getStatus());
        Map<String, Object> stored =
                sql.one(
                        "SELECT attempt_count,consumed_at FROM auth_verification WHERE id=?",
                        Long.parseLong(challenge.path("verificationId").asText()));
        assertEquals(1, Rows.integer(stored, "attempt_count"));
        assertNull(stored.get("consumed_at"));
    }

    @Test
    void anotherAccountCannotModifyGoal() throws Exception {
        JsonNode owner = register();
        JsonNode other = register();
        long definition =
                Rows.number(sql.one("SELECT id FROM goal_definition WHERE code='CUSTOM'"), "id");
        var created =
                mvc.perform(
                                post("/api/v1/me/goals")
                                        .contentType("application/json")
                                        .header(
                                                "Authorization",
                                                "Bearer " + owner.path("accessToken").asText())
                                        .header("Idempotency-Key", UUID.randomUUID())
                                        .header("X-Data-Epoch", "1")
                                        .content(
                                                mapper.writeValueAsString(
                                                        Rows.map(
                                                                "definitionId",
                                                                Long.toString(definition),
                                                                "title",
                                                                "记录生活",
                                                                "targetValue",
                                                                null,
                                                                "unit",
                                                                null,
                                                                "startDate",
                                                                "2026-10-18"))))
                        .andReturn();
        assertEquals(
                201, created.getResponse().getStatus(), created.getResponse().getContentAsString());
        String id =
                json(created.getResponse().getContentAsString()).path("data").path("id").asText();
        var denied =
                mvc.perform(
                                patch("/api/v1/me/goals/" + id)
                                        .contentType("application/json")
                                        .header(
                                                "Authorization",
                                                "Bearer " + other.path("accessToken").asText())
                                        .header("Idempotency-Key", UUID.randomUUID())
                                        .header("X-Data-Epoch", "1")
                                        .content(
                                                "{\"expectedRowVersion\":\"1\",\"title\":\"覆盖他人\"}"))
                        .andReturn();
        assertEquals(404, denied.getResponse().getStatus());
    }

    @Test
    void dateExceptionPersistsExplicitCrossMidnight() throws Exception {
        JsonNode owner = register();
        var result =
                mvc.perform(
                                put("/api/v1/me/availability-exceptions/2026-10-18")
                                        .contentType("application/json")
                                        .header(
                                                "Authorization",
                                                "Bearer " + owner.path("accessToken").asText())
                                        .header("Idempotency-Key", UUID.randomUUID())
                                        .header("X-Data-Epoch", "1")
                                        .content(
                                                "{\"expectedRowVersion\":\"0\",\"timeZone\":\"Asia/Shanghai\","
                                                    + "\"mode\":\"REPLACE_DAY\",\"slots\":[{\"kind\":\"REST\",\"startTime\":\"23:00\","
                                                    + "\"endTime\":\"07:00\",\"endDayOffset\":1}]}"))
                        .andReturn();
        assertEquals(
                200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        var listed =
                mvc.perform(
                                get("/api/v1/me/availability-exceptions")
                                        .param("from", "2026-10-18")
                                        .param("to", "2026-10-18")
                                        .header(
                                                "Authorization",
                                                "Bearer " + owner.path("accessToken").asText()))
                        .andReturn();
        JsonNode slots =
                json(listed.getResponse().getContentAsString())
                        .path("data")
                        .path("items")
                        .get(0)
                        .path("slots");
        assertEquals(1, slots.get(0).path("endDayOffset").asInt());
    }

    @Test
    void adminLoginGuardConfigurationAndAuditFormACompleteFlow() throws Exception {
        JsonNode owner = register();
        long userId = Long.parseLong(owner.path("userId").asText());
        long systemRole =
                Rows.number(
                        sql.one("SELECT id FROM sys_role WHERE role_code='SYSTEM_ADMIN'"), "id");
        sql.insert(
                "user_role_assignment",
                Rows.map(
                        "user_id",
                        userId,
                        "role_id",
                        systemRole,
                        "status",
                        "ACTIVE",
                        "valid_from",
                        java.time.Instant.now().minusSeconds(30),
                        "granted_by_user_id",
                        userId,
                        "grant_reason",
                        "专用测试库内受控建立测试管理员"));
        var login =
                mvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType("application/json")
                                        .content(
                                                mapper.writeValueAsString(
                                                        Rows.map(
                                                                "account",
                                                                owner.path("testAccount").asText(),
                                                                "password",
                                                                "Test-only-long-passphrase-2026",
                                                                "installationKey",
                                                                UUID.randomUUID().toString(),
                                                                "clientName",
                                                                "集成测试管理端",
                                                                "clientAudience",
                                                                "ADMIN",
                                                                "platform",
                                                                "WEB"))))
                        .andReturn();
        assertEquals(
                200, login.getResponse().getStatus(), login.getResponse().getContentAsString());
        String adminToken =
                json(login.getResponse().getContentAsString())
                        .path("data")
                        .path("accessToken")
                        .asText();
        assertEquals(
                403,
                mvc.perform(
                                get("/api/admin/v1/users")
                                        .header(
                                                "Authorization",
                                                "Bearer " + owner.path("accessToken").asText()))
                        .andReturn()
                        .getResponse()
                        .getStatus());
        assertEquals(
                403,
                mvc.perform(get("/api/v1/records").header("Authorization", "Bearer " + adminToken))
                        .andReturn()
                        .getResponse()
                        .getStatus());
        var configuration =
                sql.one(
                        "SELECT id,row_version FROM app_configuration WHERE environment='DEV' AND"
                                + " config_key='feature.ai_plan'");
        var configured =
                mvc.perform(
                                put("/api/admin/v1/configurations/feature.ai_plan")
                                        .contentType("application/json")
                                        .header("Authorization", "Bearer " + adminToken)
                                        .header("Idempotency-Key", UUID.randomUUID())
                                        .content(
                                                mapper.writeValueAsString(
                                                        Rows.map(
                                                                "expectedRowVersion",
                                                                Long.toString(
                                                                        Rows.number(
                                                                                configuration,
                                                                                "row_version")),
                                                                "environment",
                                                                "DEV",
                                                                "scope",
                                                                "PUBLIC",
                                                                "valueType",
                                                                "BOOLEAN",
                                                                "value",
                                                                Rows.map("booleanValue", false),
                                                                "description",
                                                                "测试环境关闭模型计划",
                                                                "enabled",
                                                                true,
                                                                "reason",
                                                                "验证配置schema与审计闭环"))))
                        .andReturn();
        assertEquals(
                200,
                configured.getResponse().getStatus(),
                configured.getResponse().getContentAsString());
        long version =
                Rows.number(
                        sql.one("SELECT row_version FROM app_user WHERE id=?", userId),
                        "row_version");
        var lastAdmin =
                mvc.perform(
                                post("/api/admin/v1/users/" + userId + "/lock")
                                        .contentType("application/json")
                                        .header("Authorization", "Bearer " + adminToken)
                                        .header("Idempotency-Key", UUID.randomUUID())
                                        .content(
                                                mapper.writeValueAsString(
                                                        Rows.map(
                                                                "expectedRowVersion",
                                                                Long.toString(version),
                                                                "reason",
                                                                "尝试锁定最后管理员应被拒绝",
                                                                "userConfirmed",
                                                                true))))
                        .andReturn();
        assertEquals(
                409,
                lastAdmin.getResponse().getStatus(),
                lastAdmin.getResponse().getContentAsString());
        assertEquals(
                "LAST_ADMIN_PROTECTED",
                json(lastAdmin.getResponse().getContentAsString()).path("code").asText());
        var audit =
                mvc.perform(
                                get("/api/admin/v1/audit-logs")
                                        .param(
                                                "from",
                                                java.time.Instant.now()
                                                        .minusSeconds(3600)
                                                        .toString())
                                        .param(
                                                "to",
                                                java.time.Instant.now().plusSeconds(60).toString())
                                        .param("actionCode", "CONFIGURATION_PUT")
                                        .header("Authorization", "Bearer " + adminToken))
                        .andReturn();
        assertEquals(
                200, audit.getResponse().getStatus(), audit.getResponse().getContentAsString());
        assertTrue(
                json(audit.getResponse().getContentAsString()).path("data").path("items").size()
                        > 0);
        assertFalse(audit.getResponse().getContentAsString().contains("email_cipher"));
        assertFalse(audit.getResponse().getContentAsString().contains("password_hash"));
    }

    private JsonNode register() throws Exception {
        String email = email(), key = UUID.randomUUID().toString();
        JsonNode challenge = challenge(email);
        var response =
                mvc.perform(
                                post("/api/v1/auth/register")
                                        .contentType("application/json")
                                        .content(
                                                mapper.writeValueAsString(
                                                        registration(
                                                                email,
                                                                challenge
                                                                        .path("verificationId")
                                                                        .asText(),
                                                                codes.get(email),
                                                                key))))
                        .andReturn();
        assertEquals(
                201,
                response.getResponse().getStatus(),
                response.getResponse().getContentAsString());
        var data =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        json(response.getResponse().getContentAsString()).path("data");
        data.put("testInstallationKey", key);
        data.put("testAccount", email);
        return data;
    }

    private JsonNode challenge(String email) throws Exception {
        var response =
                mvc.perform(
                                post("/api/v1/auth/verifications")
                                        .contentType("application/json")
                                        .content(
                                                mapper.writeValueAsString(
                                                        Rows.map(
                                                                "destination",
                                                                email,
                                                                "channel",
                                                                "EMAIL",
                                                                "purpose",
                                                                "REGISTER",
                                                                "clientAudience",
                                                                "MOBILE"))))
                        .andReturn();
        assertEquals(
                201,
                response.getResponse().getStatus(),
                response.getResponse().getContentAsString());
        return json(response.getResponse().getContentAsString()).path("data");
    }

    private Map<String, Object> registration(
            String email, String challenge, String code, String installation) {
        return Rows.map(
                "account",
                email,
                "password",
                "Test-only-long-passphrase-2026",
                "verificationId",
                challenge,
                "verificationCode",
                code,
                "adultConfirmed",
                true,
                "termsAccepted",
                true,
                "policyVersion",
                "test-service-v1",
                "installationKey",
                installation,
                "timeZone",
                "Asia/Shanghai",
                "platform",
                "ANDROID");
    }

    private String email() {
        return "test-" + UUID.randomUUID() + "@example.invalid";
    }

    private JsonNode json(String content) throws Exception {
        return mapper.readTree(content);
    }
}
