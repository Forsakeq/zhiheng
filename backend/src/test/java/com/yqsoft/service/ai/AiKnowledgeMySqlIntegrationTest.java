package com.yqsoft.service.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.yqsoft.resource.common.Rows;
import com.yqsoft.service.common.CryptoService;
import com.yqsoft.service.privacy.PrivacyWorker;
import com.yqsoft.support.MySqlFlowSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Only the external model transport and verification sender are substituted. */
@EnabledIfEnvironmentVariable(named = "ZH_TEST_DB_URL", matches = ".+")
class AiKnowledgeMySqlIntegrationTest extends MySqlFlowSupport {
    @MockBean AiProviderClient provider;
    @Autowired AiService ai;
    @Autowired CryptoService crypto;
    @Autowired PrivacyWorker privacyWorker;

    @BeforeEach
    void modelMetadata() {
        when(provider.model()).thenReturn("test-fixture-model");
        sql.execute(
                "UPDATE app_configuration SET value_json=JSON_OBJECT('booleanValue',true) WHERE"
                    + " environment='DEV' AND config_key IN "
                    + "('feature.ai_record','feature.ai_plan','feature.ai_chat','feature.weekly_review')");
    }

    @Test
    void extractDraftRequiresValidationConfirmationAndCurrentConsentThenAppliesOnce()
            throws Exception {
        JsonNode user = registerUser();
        JsonNode other = registerUser();
        String recordKey = UUID.randomUUID().toString();
        Instant happened = Instant.parse("2026-10-18T02:00:00Z");
        Map<String, Object> record =
                Rows.map(
                        "clientRecordId",
                        recordKey,
                        "recordType",
                        "ENERGY",
                        "startedAt",
                        happened,
                        "recordTimeZone",
                        "Asia/Shanghai",
                        "recordLocalDate",
                        "2026-10-18",
                        "utcOffsetMinutes",
                        480,
                        "tagIds",
                        List.of(),
                        "detail",
                        Rows.map("detailType", "ENERGY", "energyLevel", 4, "contextCode", "STUDY"));
        JsonNode output =
                mapper.valueToTree(
                        Rows.map(
                                "kind",
                                "RECORD_EXTRACT",
                                "record",
                                record,
                                "unresolvedFields",
                                List.of(),
                                "questions",
                                List.of()));
        when(provider.complete(eq("RECORD_EXTRACT"), anyMap()))
                .thenReturn(new AiProviderClient.Completion(output, 10, 20, "fixture-call"));
        grant(user, "AI_PROCESSING", "0", "GRANT");
        String conversation =
                request(
                                post("/api/v1/ai/conversations"),
                                user,
                                Rows.map("title", "记录提取测试", "scene", "RECORD"),
                                201)
                        .path("id")
                        .asText();
        JsonNode queued =
                request(
                        post("/api/v1/ai/runs"),
                        user,
                        Rows.map(
                                "kind",
                                "RECORD_EXTRACT",
                                "conversationId",
                                conversation,
                                "clientRequestId",
                                UUID.randomUUID(),
                                "input",
                                Rows.map(
                                        "text",
                                        "今天精力四级",
                                        "referenceInstant",
                                        happened,
                                        "timeZone",
                                        "Asia/Shanghai",
                                        "clientRecordId",
                                        recordKey)),
                        202);
        long run = queued.path("runId").asLong();
        assertEquals(
                0,
                sql.count(
                        "SELECT COUNT(*) FROM health_record WHERE user_id=?",
                        user.path("userId").asLong()));
        assertTrue(ai.claim(run));
        ai.executeClaimed(run);
        assertEquals(
                "SUCCEEDED", sql.one("SELECT status FROM ai_run WHERE id=?", run).get("status"));
        assertEquals(
                1,
                sql.count(
                        "SELECT COUNT(*) FROM ai_message WHERE ai_run_id=? AND"
                                + " message_type='RECORD_CARD'",
                        run));
        long draft =
                Rows.number(sql.one("SELECT id FROM ai_draft WHERE source_run_id=?", run), "id");
        request(get("/api/v1/ai/drafts/" + draft), other, null, 404);
        Map<String, Object> apply =
                Rows.map(
                        "expectedRowVersion",
                        "1",
                        "expectedDraftRevisionNo",
                        "1",
                        "expectedDataSeq",
                        "0",
                        "userConfirmed",
                        true,
                        "clientRecordId",
                        recordKey);
        JsonNode premature =
                request(post("/api/v1/ai/drafts/" + draft + "/apply"), user, apply, 409);
        assertEquals("DRAFT_NOT_VALIDATED", premature.path("code").asText());
        JsonNode checked =
                request(
                        post("/api/v1/ai/drafts/" + draft + "/validate"),
                        user,
                        Rows.map(
                                "expectedRowVersion",
                                "1",
                                "expectedDraftRevisionNo",
                                "1",
                                "expectedDataSeq",
                                "0"),
                        200);
        assertTrue(checked.path("valid").asBoolean(), checked.toString());
        apply.put("expectedRowVersion", checked.path("draftRowVersion").asText());
        String operation = UUID.randomUUID().toString();
        JsonNode applied = applyWithKey(user, draft, apply, operation, 200);
        assertEquals("RECORD", applied.path("appliedResourceType").asText());
        long id = applied.path("appliedResourceId").asLong();
        Map<String, Object> persisted = sql.one("SELECT * FROM health_record WHERE id=?", id);
        assertEquals("AI_CONFIRMED", persisted.get("source_type"));
        assertEquals(draft, Rows.number(persisted, "ai_draft_id"));
        assertEquals(
                4,
                Rows.integer(
                        sql.one(
                                "SELECT energy_level FROM energy_record_detail WHERE record_id=?",
                                id),
                        "energy_level"));
        assertEquals(
                id,
                applyWithKey(user, draft, apply, operation, 200)
                        .path("appliedResourceId")
                        .asLong());
        request(post("/api/v1/ai/drafts/" + draft + "/apply"), user, apply, 409);
        assertEquals(
                1,
                sql.count(
                        "SELECT COUNT(*) FROM health_record WHERE user_id=?",
                        user.path("userId").asLong()));

        // A second pending draft cannot be made VALID after its original processing consent is
        // withdrawn.
        JsonNode secondQueued =
                request(
                        post("/api/v1/ai/runs"),
                        user,
                        Rows.map(
                                "kind",
                                "RECORD_EXTRACT",
                                "conversationId",
                                conversation,
                                "clientRequestId",
                                UUID.randomUUID(),
                                "input",
                                Rows.map(
                                        "text",
                                        "另一条精力记录",
                                        "referenceInstant",
                                        happened,
                                        "timeZone",
                                        "Asia/Shanghai",
                                        "clientRecordId",
                                        UUID.randomUUID())),
                        202);
        long secondRun = secondQueued.path("runId").asLong();
        assertTrue(ai.claim(secondRun));
        ai.executeClaimed(secondRun);
        long secondDraft =
                Rows.number(
                        sql.one("SELECT id FROM ai_draft WHERE source_run_id=?", secondRun), "id");
        grant(user, "AI_PROCESSING", "1", "WITHDRAW");
        long sequence =
                sql.count(
                        "SELECT committed_seq FROM user_sync_clock WHERE user_id=?",
                        user.path("userId").asLong());
        JsonNode invalid =
                request(
                        post("/api/v1/ai/drafts/" + secondDraft + "/validate"),
                        user,
                        Rows.map(
                                "expectedRowVersion",
                                "1",
                                "expectedDraftRevisionNo",
                                "1",
                                "expectedDataSeq",
                                Long.toString(sequence)),
                        403);
        assertEquals("CONSENT_CHANGED", invalid.path("code").asText());
        assertEquals(
                1,
                sql.count(
                        "SELECT COUNT(*) FROM health_record WHERE user_id=?",
                        user.path("userId").asLong()));

        JsonNode deletion =
                request(
                        post("/api/v1/privacy/requests/preview"),
                        user,
                        Rows.map(
                                "type", "ERASE_HEALTH_DATA", "scope", Rows.map(), "format", "JSON"),
                        200);
        request(
                post(
                        "/api/v1/privacy/requests/"
                                + deletion.path("requestId").asText()
                                + "/confirm"),
                user,
                Rows.map(
                        "expectedRowVersion",
                        deletion.path("rowVersion").asText(),
                        "manifestDigest",
                        deletion.path("manifestDigest").asText(),
                        "expectedDataSeq",
                        deletion.path("previewDataSeq").asText(),
                        "userConfirmed",
                        true,
                        "reauth",
                        Rows.map(
                                "method",
                                "PASSWORD",
                                "password",
                                "Integration-only-passphrase-2026")),
                202);
        privacyWorker.process(deletion.path("requestId").asLong());
        Map<String, Object> finished =
                sql.one(
                        "SELECT status,error_code FROM privacy_request WHERE id=?",
                        deletion.path("requestId").asLong());
        assertEquals("COMPLETED", finished.get("status"), Rows.text(finished, "error_code"));
        for (String table :
                List.of(
                        "ai_run",
                        "ai_draft",
                        "ai_draft_revision",
                        "health_record",
                        "energy_record_detail")) {
            assertEquals(
                    0,
                    sql.count(
                            "SELECT COUNT(*) FROM " + table + " WHERE user_id=?",
                            user.path("userId").asLong()),
                    table);
        }
    }

    @Test
    void disabledFeatureAndPerUserQueueLimitPreventUnboundedGeneration() throws Exception {
        JsonNode user = registerUser();
        grant(user, "AI_PROCESSING", "0", "GRANT");
        sql.execute(
                "UPDATE app_configuration SET value_json=JSON_OBJECT('booleanValue',false) "
                        + "WHERE environment='DEV' AND config_key='feature.ai_record'");
        var rejected = request(post("/api/v1/ai/runs"), user, extractionRequest(), 503);
        assertEquals("FEATURE_NOT_ENABLED", rejected.path("code").asText());
        assertEquals(
                0,
                sql.count(
                        "SELECT COUNT(*) FROM ai_run WHERE user_id=?",
                        user.path("userId").asLong()));
        sql.execute(
                "UPDATE app_configuration SET value_json=JSON_OBJECT('booleanValue',true) "
                        + "WHERE environment='DEV' AND config_key='feature.ai_record'");
        request(post("/api/v1/ai/runs"), user, extractionRequest(), 202);
        request(post("/api/v1/ai/runs"), user, extractionRequest(), 202);
        var limited = request(post("/api/v1/ai/runs"), user, extractionRequest(), 429);
        assertEquals("AI_CONCURRENCY_LIMIT", limited.path("code").asText());
        assertEquals(
                2,
                sql.count(
                        "SELECT COUNT(*) FROM ai_run WHERE user_id=?",
                        user.path("userId").asLong()));
    }

    private Object extractionRequest() {
        return Rows.map(
                "kind",
                "RECORD_EXTRACT",
                "clientRequestId",
                UUID.randomUUID(),
                "input",
                Rows.map(
                        "text",
                        "测试记录请求",
                        "referenceInstant",
                        Instant.now(),
                        "timeZone",
                        "Asia/Shanghai",
                        "clientRecordId",
                        UUID.randomUUID()));
    }

    @Test
    void knowledgeRequiresIndependentReviewAndPublishedVerifiedSources() throws Exception {
        JsonNode editorMobile = registerUser();
        JsonNode reviewerMobile = registerUser();
        JsonNode reader = registerUser();
        JsonNode editor = admin(editorMobile, List.of("CONTENT_EDITOR", "CONTENT_REVIEWER"));
        JsonNode reviewer = admin(reviewerMobile, List.of("CONTENT_REVIEWER", "CONTENT_PUBLISHER"));
        String url = "https://example.invalid/test-only-source/" + UUID.randomUUID();
        JsonNode source =
                request(
                        post("/api/admin/v1/knowledge/source-commands"),
                        editor,
                        Rows.map(
                                "action",
                                "CREATE",
                                "title",
                                "Fixture source metadata",
                                "organization",
                                "Test fixture",
                                "url",
                                url,
                                "accessedOn",
                                "2026-10-18",
                                "verificationStatus",
                                "VERIFIED",
                                "rightsNote",
                                "Test-only metadata, no real advice",
                                "sourceKind",
                                "OFFICIAL_WEB",
                                "usageBasis",
                                "LINK_ONLY"),
                        200);
        String sourceId = source.path("id").asText();
        Map<String, Object> content =
                Rows.map(
                        "title",
                        "记录填写帮助",
                        "summary",
                        "测试知识摘要",
                        "bodyMarkdown",
                        "正确填写实际发生时间。",
                        "applicablePopulation",
                        "本软件使用者",
                        "limitations",
                        "这是测试用软件帮助，不用于诊断。",
                        "tags",
                        List.of("app-help"),
                        "sourceLinks",
                        List.of(
                                Rows.map(
                                        "sourceId",
                                        sourceId,
                                        "citationLabel",
                                        "软件填写说明",
                                        "sortOrder",
                                        0)),
                        "contentDate",
                        "2026-10-18",
                        "rightsNote",
                        "Test fixture authored content");
        JsonNode created =
                request(
                        post("/api/admin/v1/knowledge/articles"),
                        editor,
                        Rows.map(
                                "slug",
                                "fixture-" + UUID.randomUUID(),
                                "title",
                                "记录填写帮助",
                                "category",
                                "APP_HELP",
                                "initialVersion",
                                content),
                        201);
        String article = created.path("id").asText();
        String version = created.path("draftVersionId").asText();
        request(get("/api/v1/knowledge/articles/" + article), reader, null, 404);
        request(
                get("/api/v1/knowledge/articles/" + article).param("versionId", version),
                reader,
                null,
                404);
        request(
                post("/api/admin/v1/knowledge/versions/" + version + "/submit"),
                editor,
                Rows.map("expectedRowVersion", "1"),
                200);
        JsonNode selfReview =
                request(
                        post("/api/admin/v1/knowledge/versions/" + version + "/review"),
                        editor,
                        Rows.map("expectedRowVersion", "2", "decision", "PASS", "comment", "不可自审"),
                        403);
        assertEquals("SELF_REVIEW_FORBIDDEN", selfReview.path("code").asText());
        request(
                post("/api/admin/v1/knowledge/versions/" + version + "/review"),
                reviewer,
                Rows.map(
                        "expectedRowVersion",
                        "2",
                        "decision",
                        "PASS",
                        "comment",
                        "Checked test-only source metadata and limited scope"),
                200);
        request(
                post("/api/admin/v1/knowledge/versions/" + version + "/publish"),
                reviewer,
                Rows.map(
                        "expectedRowVersion",
                        "3",
                        "expectedArticleRowVersion",
                        "1",
                        "expectedActiveVersionId",
                        null,
                        "confirmPublishedContent",
                        true),
                200);
        JsonNode publicArticle =
                request(get("/api/v1/knowledge/articles/" + article), reader, null, 200);
        assertEquals(version, publicArticle.path("versionId").asText());
        assertEquals(url, publicArticle.path("sources").get(0).path("url").asText());
        assertFalse(publicArticle.has("reviewComment"));
        request(
                get("/api/admin/v1/knowledge/articles")
                        .param("status", "PUBLISHED")
                        .param("editorId", editorMobile.path("userId").asText()),
                reviewer,
                null,
                200);
        assertTrue(
                sql.count(
                                "SELECT COUNT(*) FROM audit_log WHERE resource_id=? AND"
                                        + " action_code='KNOWLEDGE_PUBLISH'",
                                Long.parseLong(version))
                        > 0);
    }

    private JsonNode admin(JsonNode user, List<String> roleCodes) throws Exception {
        long id = user.path("userId").asLong();
        for (String code : roleCodes)
            sql.insert(
                    "user_role_assignment",
                    Rows.map(
                            "user_id",
                            id,
                            "role_id",
                            sql.count("SELECT id FROM sys_role WHERE role_code=?", code),
                            "status",
                            "ACTIVE",
                            "valid_from",
                            Instant.now().minusSeconds(60),
                            "granted_by_user_id",
                            id,
                            "grant_reason",
                            "Test fixture role bootstrap"));
        String account =
                crypto.decrypt(
                        sql.one("SELECT email_cipher FROM app_user WHERE id=?", id)
                                .get("email_cipher"));
        return request(
                post("/api/v1/auth/login"),
                null,
                Rows.map(
                        "account",
                        account,
                        "password",
                        "Integration-only-passphrase-2026",
                        "installationKey",
                        UUID.randomUUID(),
                        "clientName",
                        "Test admin web",
                        "clientAudience",
                        "ADMIN",
                        "platform",
                        "WEB"),
                200);
    }

    private void grant(JsonNode user, String purpose, String version, String decision)
            throws Exception {
        request(
                put("/api/v1/me/consents/" + purpose),
                user,
                Rows.map(
                        "expectedRowVersion",
                        version,
                        "decision",
                        decision,
                        "policyVersion",
                        "integration-consent-v1"),
                200);
    }

    private JsonNode applyWithKey(JsonNode user, long draft, Object body, String key, int status)
            throws Exception {
        var response =
                mvc.perform(
                                post("/api/v1/ai/drafts/" + draft + "/apply")
                                        .contentType("application/json")
                                        .header(
                                                "Authorization",
                                                "Bearer " + user.path("accessToken").asText())
                                        .header("X-Data-Epoch", user.path("datasetEpoch").asText())
                                        .header("Idempotency-Key", key)
                                        .content(mapper.writeValueAsBytes(body)))
                        .andReturn()
                        .getResponse();
        assertEquals(
                status, response.getStatus(), response.getContentAsString(StandardCharsets.UTF_8));
        return mapper.readTree(response.getContentAsByteArray()).path("data");
    }
}
