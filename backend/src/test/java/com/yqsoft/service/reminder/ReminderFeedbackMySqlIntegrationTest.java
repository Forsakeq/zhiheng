package com.yqsoft.service.reminder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yqsoft.dao.SqlStore;
import com.yqsoft.resource.common.Rows;
import com.yqsoft.service.auth.VerificationSender;
import com.yqsoft.service.common.CryptoService;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Executes against the complete checked MySQL schema, never an in-memory substitute. */
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
class ReminderFeedbackMySqlIntegrationTest {
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
    @Autowired ObjectMapper mapper;
    @Autowired SqlStore sql;
    @Autowired CryptoService crypto;
    @Autowired ReminderService reminders;
    @MockBean VerificationSender sender;
    private final Map<String, String> codes = new ConcurrentHashMap<>();

    @BeforeEach
    void setup() {
        sql.execute(
                "UPDATE app_configuration SET value_json=JSON_OBJECT('booleanValue',true) WHERE"
                        + " environment='DEV' AND config_key='auth.registration_enabled'");
        sql.execute(
                "UPDATE app_configuration SET value_json=JSON_OBJECT('booleanValue',false) WHERE"
                        + " environment='DEV' AND config_key='feature.local_reminder'");
        doAnswer(
                        call -> {
                            codes.put(call.getArgument(0), call.getArgument(1));
                            return null;
                        })
                .when(sender)
                .send(anyString(), anyString(), anyString());
    }

    @Test
    void localRegistrationRequiresCapabilityAndNeverClaimsDelivered() throws Exception {
        JsonNode user = register();
        grant(user);
        long rule = rule(user, "LOCAL_SYSTEM");
        reminders.handleEvent(
                sql.one(
                        "SELECT * FROM outbox_event WHERE aggregate_id=? AND"
                                + " event_type='REMINDER_RULE_CHANGED'",
                        rule));
        assertEquals(0, sql.count("SELECT COUNT(*) FROM reminder_delivery WHERE rule_id=?", rule));
        enableLocal(user);
        reminders.refreshDaily();
        Map<String, Object> delivery =
                sql.one("SELECT * FROM reminder_delivery WHERE rule_id=?", rule);
        long id = Rows.number(delivery, "id");
        assertEquals(200, receipt(user, id, "1", "REGISTERED", 1).getResponse().getStatus());
        Map<String, Object> registered = sql.one("SELECT * FROM reminder_delivery WHERE id=?", id);
        assertEquals("REGISTERED", registered.get("status"));
        assertNotNull(registered.get("registered_at"));
        assertNull(registered.get("delivered_at"));
        MvcResult fakeDelivered = receipt(user, id, "2", "DELIVERED", 1);
        assertEquals(403, fakeDelivered.getResponse().getStatus());
        assertEquals("DELIVERY_OBSERVATION_UNVERIFIED", json(fakeDelivered).path("code").asText());
        MvcResult withdrawn =
                mutate(
                        user,
                        put("/api/v1/me/consents/NOTIFICATION"),
                        Rows.map(
                                "expectedRowVersion",
                                "1",
                                "decision",
                                "WITHDRAW",
                                "policyVersion",
                                "test-consent-v1"),
                        1);
        assertEquals(
                200,
                withdrawn.getResponse().getStatus(),
                withdrawn.getResponse().getContentAsString());
        long revision =
                Rows.number(
                        sql.one("SELECT row_version FROM reminder_delivery WHERE id=?", id),
                        "row_version");
        assertEquals(
                409,
                receipt(user, id, Long.toString(revision), "REGISTERED", 1)
                        .getResponse()
                        .getStatus());
        assertNull(
                sql.one("SELECT delivered_at FROM reminder_delivery WHERE id=?", id)
                        .get("delivered_at"));
    }

    @Test
    void oldEpochCannotRegisterAfterDatasetReplacement() throws Exception {
        JsonNode user = register();
        grant(user);
        enableLocal(user);
        long rule = rule(user, "LOCAL_SYSTEM");
        reminders.refreshDaily();
        long id =
                Rows.number(
                        sql.one("SELECT id FROM reminder_delivery WHERE rule_id=?", rule), "id");
        sql.execute(
                "UPDATE user_sync_clock SET dataset_epoch=dataset_epoch+1 WHERE user_id=?",
                user.path("userId").asLong());
        MvcResult stale = receipt(user, id, "1", "REGISTERED", 2);
        assertEquals(
                409, stale.getResponse().getStatus(), stale.getResponse().getContentAsString());
        assertEquals("REMINDER_STALE", json(stale).path("code").asText());
        assertNull(
                sql.one("SELECT registered_at FROM reminder_delivery WHERE id=?", id)
                        .get("registered_at"));
    }

    @Test
    void inAppDispatchSynchronizesInboxAndReadVersionIdempotently() throws Exception {
        JsonNode user = register();
        grant(user);
        long rule = rule(user, "IN_APP");
        reminders.refreshDaily();
        sql.execute(
                "UPDATE reminder_delivery SET fire_at=?,expires_at=? WHERE rule_id=?",
                Instant.now().minusSeconds(60),
                Instant.now().plusSeconds(600),
                rule);
        reminders.dispatchInApp();
        Map<String, Object> note =
                sql.one(
                        "SELECT * FROM notification_inbox WHERE user_id=? AND category='REMINDER'",
                        user.path("userId").asLong());
        long id = Rows.number(note, "id");
        assertEquals(
                1,
                sql.count(
                        "SELECT COUNT(*) FROM sync_change WHERE user_id=? AND"
                                + " entity_type='NOTIFICATION' AND entity_id=?",
                        user.path("userId").asLong(),
                        id));
        assertNull(
                sql.one("SELECT delivered_at FROM reminder_delivery WHERE rule_id=?", rule)
                        .get("delivered_at"));
        String key = UUID.randomUUID().toString();
        Map<String, Object> read =
                Rows.map(
                        "items",
                        List.of(Rows.map("id", Long.toString(id), "expectedRowVersion", "1")),
                        "read",
                        true);
        MvcResult result = mutate(user, put("/api/v1/notifications/read-state"), read, 1, key);
        assertEquals(
                200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(
                "2", json(result).path("data").path("results").get(0).path("rowVersion").asText());
        MvcResult replay = mutate(user, put("/api/v1/notifications/read-state"), read, 1, key);
        assertEquals(
                200, replay.getResponse().getStatus(), replay.getResponse().getContentAsString());
        assertEquals(
                "2", json(replay).path("data").path("results").get(0).path("rowVersion").asText());
        assertEquals(
                2,
                Rows.number(
                        sql.one("SELECT row_version FROM notification_inbox WHERE id=?", id),
                        "row_version"));
        assertEquals(
                409,
                mutate(user, put("/api/v1/notifications/read-state"), read, 1)
                        .getResponse()
                        .getStatus());
    }

    @Test
    void feedbackRequiresOwnedCleanPurposeAndKeepsDiagnosticsExplicit() throws Exception {
        JsonNode owner = register();
        JsonNode other = register();
        long clean = file(owner, "CLEAN", "FEEDBACK_ATTACHMENT");
        long pending = file(owner, "UPLOADING", "FEEDBACK_ATTACHMENT");
        long foreign = file(other, "CLEAN", "FEEDBACK_ATTACHMENT");
        long wrongPurpose = file(owner, "CLEAN", "RECORD_ATTACHMENT");
        for (long rejected : List.of(pending, foreign, wrongPurpose)) {
            MvcResult result =
                    mutate(owner, post("/api/v1/feedback"), feedback(rejected, false), 1);
            assertTrue(
                    Set.of(404, 422).contains(result.getResponse().getStatus()),
                    result.getResponse().getContentAsString());
        }
        Map<String, Object> body = feedback(clean, true);
        body.put(
                "diagnostics",
                Rows.map(
                        "appVersion",
                        "test-1",
                        "platform",
                        "ANDROID",
                        "osVersion",
                        "fixture",
                        "requestIds",
                        List.of("request-fixture")));
        MvcResult created = mutate(owner, post("/api/v1/feedback"), body, 1);
        assertEquals(
                201, created.getResponse().getStatus(), created.getResponse().getContentAsString());
        long id = json(created).path("data").path("id").asLong();
        Map<String, Object> stored = sql.one("SELECT * FROM feedback_ticket WHERE id=?", id);
        assertEquals("test@example.invalid", crypto.decrypt(stored.get("contact_cipher")));
        assertEquals("测试反馈详情", crypto.decrypt(stored.get("description_cipher")));
        assertTrue(Rows.json(stored, "diagnostics_json").path("accepted").asBoolean());
        MvcResult ownList =
                mvc.perform(get("/api/v1/feedback").header("Authorization", bearer(owner)))
                        .andReturn();
        JsonNode visible = json(ownList).path("data").path("items").get(0);
        assertFalse(visible.has("diagnostics"));
        assertFalse(visible.has("contact"));
        MvcResult otherList =
                mvc.perform(get("/api/v1/feedback").header("Authorization", bearer(other)))
                        .andReturn();
        assertEquals(0, json(otherList).path("data").path("items").size());
    }

    private long rule(JsonNode user, String channel) throws Exception {
        sql.execute(
                "UPDATE user_preference SET quiet_hours_enabled=0 WHERE user_id=?",
                user.path("userId").asLong());
        MvcResult response =
                mutate(
                        user,
                        post("/api/v1/reminder-rules"),
                        Rows.map(
                                "scope",
                                "DAILY_RECORD",
                                "enabled",
                                true,
                                "channel",
                                channel,
                                "localTime",
                                LocalTime.now(ZoneOffset.UTC)
                                        .plusHours(2)
                                        .withSecond(0)
                                        .withNano(0)
                                        .toString(),
                                "timeZone",
                                "UTC",
                                "installationId",
                                user.path("installationId").asText()),
                        1);
        assertEquals(
                201,
                response.getResponse().getStatus(),
                response.getResponse().getContentAsString());
        return json(response).path("data").path("id").asLong();
    }

    private void grant(JsonNode user) throws Exception {
        MvcResult response =
                mutate(
                        user,
                        put("/api/v1/me/consents/NOTIFICATION"),
                        Rows.map(
                                "expectedRowVersion",
                                "0",
                                "decision",
                                "GRANT",
                                "policyVersion",
                                "test-consent-v1"),
                        1);
        assertEquals(
                200,
                response.getResponse().getStatus(),
                response.getResponse().getContentAsString());
    }

    private void enableLocal(JsonNode user) {
        sql.execute(
                "UPDATE app_configuration SET value_json=JSON_OBJECT('booleanValue',true) WHERE"
                        + " environment='DEV' AND config_key='feature.local_reminder'");
        sql.execute(
                "UPDATE client_installation SET"
                    + " notification_permission='GRANTED',capabilities_json=JSON_OBJECT('localReminder','SUPPORTED','deliveryObservation','NONE','watchTaskChannel','NONE','secureCredentialStorage','UNVERIFIED')"
                    + " WHERE id=?",
                user.path("installationId").asLong());
    }

    private MvcResult receipt(JsonNode user, long id, String version, String event, long epoch)
            throws Exception {
        return mutate(
                user,
                post("/api/v1/reminder-deliveries/" + id + "/receipts"),
                Rows.map(
                        "expectedRowVersion",
                        version,
                        "installationId",
                        user.path("installationId").asText(),
                        "event",
                        event,
                        "observedAt",
                        Instant.now(),
                        "osRequestId",
                        "fixture-os-handle"),
                epoch);
    }

    private long file(JsonNode user, String status, String purpose) {
        byte[] hash = new byte[32];
        Map<String, Object> row =
                Rows.map(
                        "user_id",
                        user.path("userId").asLong(),
                        "file_key",
                        UUID.randomUUID().toString(),
                        "purpose",
                        purpose,
                        "storage_provider",
                        "LOCAL_PRIVATE",
                        "bucket_code",
                        "test-private",
                        "object_key",
                        "fixture/" + UUID.randomUUID(),
                        "status",
                        status,
                        "encryption_key_ref",
                        crypto.keyRef(),
                        "expected_byte_size",
                        16,
                        "expected_sha256",
                        hash,
                        "declared_mime_type",
                        "image/png");
        if ("CLEAN".equals(status))
            row.putAll(
                    Rows.map(
                            "byte_size",
                            16,
                            "sha256",
                            hash,
                            "mime_type",
                            "image/png",
                            "completed_at",
                            Instant.now()));
        return sql.insert("file_object", row);
    }

    private Map<String, Object> feedback(long file, boolean diagnostics) {
        return Rows.map(
                "category",
                "BUG",
                "title",
                "测试反馈",
                "description",
                "测试反馈详情",
                "fileIds",
                List.of(Long.toString(file)),
                "contact",
                "test@example.invalid",
                "includeDiagnostics",
                diagnostics);
    }

    private JsonNode register() throws Exception {
        String email = "reminder-test-" + UUID.randomUUID() + "@example.invalid";
        MvcResult challenge =
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
                challenge.getResponse().getStatus(),
                challenge.getResponse().getContentAsString());
        MvcResult response =
                mvc.perform(
                                post("/api/v1/auth/register")
                                        .contentType("application/json")
                                        .content(
                                                mapper.writeValueAsString(
                                                        Rows.map(
                                                                "account",
                                                                email,
                                                                "password",
                                                                "Test-only-long-passphrase-2026",
                                                                "verificationId",
                                                                json(challenge)
                                                                        .path("data")
                                                                        .path("verificationId")
                                                                        .asText(),
                                                                "verificationCode",
                                                                codes.get(email),
                                                                "adultConfirmed",
                                                                true,
                                                                "termsAccepted",
                                                                true,
                                                                "policyVersion",
                                                                "test-service-v1",
                                                                "installationKey",
                                                                UUID.randomUUID(),
                                                                "timeZone",
                                                                "Asia/Shanghai",
                                                                "platform",
                                                                "ANDROID"))))
                        .andReturn();
        assertEquals(
                201,
                response.getResponse().getStatus(),
                response.getResponse().getContentAsString());
        return json(response).path("data");
    }

    private MvcResult mutate(
            JsonNode user, MockHttpServletRequestBuilder request, Object body, long epoch)
            throws Exception {
        return mutate(user, request, body, epoch, UUID.randomUUID().toString());
    }

    private MvcResult mutate(
            JsonNode user,
            MockHttpServletRequestBuilder request,
            Object body,
            long epoch,
            String key)
            throws Exception {
        return mvc.perform(
                        request.contentType("application/json")
                                .header("Authorization", bearer(user))
                                .header("Idempotency-Key", key)
                                .header("X-Data-Epoch", epoch)
                                .content(mapper.writeValueAsString(body)))
                .andReturn();
    }

    private String bearer(JsonNode user) {
        return "Bearer " + user.path("accessToken").asText();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }
}
