package com.yqsoft.service.record;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.yqsoft.resource.common.Rows;
import com.yqsoft.service.file.FileService;
import com.yqsoft.service.privacy.PrivacyWorker;
import com.yqsoft.support.MySqlFlowSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;

/** End-to-end persistence and deletion semantics against the complete production schema. */
@EnabledIfEnvironmentVariable(named = "ZH_TEST_DB_URL", matches = ".+")
class RecordPlanPrivacyMySqlIntegrationTest extends MySqlFlowSupport {
    private static final Path PRIVATE_ROOT =
            Path.of(
                            System.getProperty("java.io.tmpdir"),
                            "zhiheng-record-plan-privacy-" + UUID.randomUUID())
                    .toAbsolutePath();
    private static final Path LEDGER = PRIVATE_ROOT.resolve("deletion-ledger.jsonl");
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @DynamicPropertySource
    static void privateStorage(DynamicPropertyRegistry registry) {
        registry.add("zhiheng.files.root", () -> PRIVATE_ROOT.resolve("objects").toString());
        registry.add("zhiheng.privacy.deletion-ledger", () -> LEDGER.toString());
    }

    @Autowired PrivacyWorker worker;
    @Autowired FileService files;

    @Test
    void fourRecordKindsHaveOneTypedDetailAndConflictingPayloadRollsBack() throws Exception {
        JsonNode user = registerUser();
        long owner = user.path("userId").asLong();
        Map<String, Object> activity = record("ACTIVITY", "运动后的真实感受");
        JsonNode created = request(post("/api/v1/records"), user, activity, 201);
        String recordId = created.path("id").asText();
        assertEquals(
                1,
                sql.count(
                        "SELECT COUNT(*) FROM activity_record_detail WHERE user_id=? AND"
                                + " record_id=?",
                        owner,
                        recordId));
        assertEquals(
                0,
                sql.count(
                        "SELECT COUNT(*) FROM sleep_record_detail WHERE user_id=? AND record_id=?",
                        owner,
                        recordId));
        JsonNode sleep = request(post("/api/v1/records"), user, record("SLEEP", "起床日归属"), 201);
        JsonNode meal = request(post("/api/v1/records"), user, record("MEAL", "食堂午餐"), 201);
        JsonNode energy = request(post("/api/v1/records"), user, record("ENERGY", "学习后精力"), 201);
        assertEquals(
                1,
                sql.count(
                        "SELECT COUNT(*) FROM sleep_record_detail WHERE user_id=? AND record_id=?",
                        owner,
                        sleep.path("id").asText()));
        assertEquals(
                1,
                sql.count(
                        "SELECT COUNT(*) FROM meal_record_detail WHERE user_id=? AND record_id=?",
                        owner,
                        meal.path("id").asText()));
        assertEquals(
                1,
                sql.count(
                        "SELECT COUNT(*) FROM energy_record_detail WHERE user_id=? AND record_id=?",
                        owner,
                        energy.path("id").asText()));

        Map<String, Object> wrong = record("ACTIVITY", "不能落库");
        wrong.put("detail", Rows.map("detailType", "ENERGY", "energyLevel", 3));
        request(post("/api/v1/records"), user, wrong, 422);
        assertEquals(4, sql.count("SELECT COUNT(*) FROM health_record WHERE user_id=?", owner));
        String stored =
                new String(
                        (byte[])
                                sql.one(
                                                "SELECT notes_cipher FROM health_record WHERE id=?",
                                                recordId)
                                        .get("notes_cipher"),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(stored.contains("运动后的真实感受"));

        Map<String, Object> replacement = record("ACTIVITY", "修改后内容");
        replacement.remove("clientRecordId");
        replacement.remove("recordType");
        replacement.put("expectedRowVersion", "1");
        JsonNode updated = request(put("/api/v1/records/" + recordId), user, replacement, 200);
        request(put("/api/v1/records/" + recordId), user, replacement, 409);
        JsonNode deleted =
                request(
                        delete("/api/v1/records/" + recordId),
                        user,
                        Rows.map("expectedRowVersion", updated.path("rowVersion").asText()),
                        200);
        request(get("/api/v1/records/" + recordId), user, null, 404);
        request(
                post("/api/v1/records/" + recordId + "/restore"),
                user,
                Rows.map("expectedRowVersion", deleted.path("rowVersion").asText()),
                200);
        JsonNode restored = request(get("/api/v1/records/" + recordId), user, null, 200);
        assertEquals("修改后内容", restored.path("notes").asText());
        assertEquals(
                1,
                sql.count(
                        "SELECT COUNT(*) FROM activity_record_detail WHERE user_id=? AND"
                                + " record_id=?",
                        owner,
                        recordId));
        JsonNode another = registerUser();
        request(get("/api/v1/records/" + recordId), another, null, 404);
    }

    @Test
    void activatingANewVersionRetainsTaskIdentityAndImmutableCompletionHistory() throws Exception {
        JsonNode user = registerUser();
        long owner = user.path("userId").asLong();
        ActivePlan initial = activePlan(user);
        String taskId = initial.taskId();
        String originalTitle =
                Rows.text(
                        sql.one(
                                "SELECT title FROM plan_version_task WHERE user_id=? AND"
                                        + " version_id=? AND task_id=?",
                                owner,
                                initial.versionId(),
                                taskId),
                        "title");
        Map<String, Object> edited = task("散步并观察周边", taskId, "1");
        JsonNode draft =
                request(
                        post("/api/v1/plans/" + initial.planId() + "/versions"),
                        user,
                        Rows.map(
                                "expectedPlanRowVersion",
                                version("plan", initial.planId()),
                                "expectedActiveVersionId",
                                initial.versionId(),
                                "changeReason",
                                "只调整未执行任务文案",
                                "tasks",
                                List.of(edited)),
                        201);
        String second = draft.path("id").asText();
        activate(
                user,
                initial.planId(),
                second,
                "1",
                initial.versionId(),
                List.of(taskId),
                List.of(Rows.map("taskId", taskId, "expectedRowVersion", "1")));
        Map<String, Object> stable =
                sql.one(
                        "SELECT * FROM task WHERE user_id=? AND plan_id=?",
                        owner,
                        initial.planId());
        assertEquals(taskId, Long.toString(Rows.number(stable, "id")));
        assertEquals("散步并观察周边", Rows.text(stable, "title"));
        assertEquals(
                originalTitle,
                Rows.text(
                        sql.one(
                                "SELECT title FROM plan_version_task WHERE version_id=? AND"
                                        + " task_id=?",
                                initial.versionId(),
                                taskId),
                        "title"));
        request(
                put("/api/v1/plans/" + initial.planId() + "/versions/" + initial.versionId()),
                user,
                Rows.map(
                        "expectedRowVersion",
                        version("plan_version", initial.versionId()),
                        "expectedActiveVersionId",
                        second,
                        "changeReason",
                        "不可覆盖封存版",
                        "tasks",
                        List.of(edited)),
                409);

        request(
                post("/api/v1/tasks/" + taskId + "/complete"),
                user,
                Rows.map(
                        "expectedRowVersion",
                        version("task", taskId),
                        "performedAt",
                        Instant.now().toString(),
                        "reasonCode",
                        "DONE"),
                200);
        assertEquals(
                0,
                sql.count("SELECT COUNT(*) FROM health_record WHERE user_id=?", owner),
                "完成意图不能虚构实际运动事实");
        Map<String, Object> event =
                sql.one(
                        "SELECT * FROM task_event WHERE user_id=? AND task_id=? AND"
                                + " event_type='COMPLETE'",
                        owner,
                        taskId);
        assertEquals(Long.parseLong(second), Rows.number(event, "plan_version_id"));
        assertEquals(
                second,
                sql.one(
                                "SELECT version_id FROM plan_version_task WHERE id=?",
                                Rows.number(event, "plan_version_task_id"))
                        .get("version_id")
                        .toString());

        Map<String, Object> forbidden = task("改写已经完成的任务", taskId, version("task", taskId));
        JsonNode third =
                request(
                        post("/api/v1/plans/" + initial.planId() + "/versions"),
                        user,
                        Rows.map(
                                "expectedPlanRowVersion",
                                version("plan", initial.planId()),
                                "expectedActiveVersionId",
                                second,
                                "changeReason",
                                "验证完成历史保护",
                                "tasks",
                                List.of(forbidden)),
                        201);
        JsonNode validation =
                request(
                        post(
                                "/api/v1/plans/"
                                        + initial.planId()
                                        + "/versions/"
                                        + third.path("id").asText()
                                        + "/validate"),
                        user,
                        Rows.map(
                                "expectedRowVersion",
                                "1",
                                "expectedActiveVersionId",
                                second,
                                "effectiveFrom",
                                effectiveFrom(),
                                "affectedTaskIds",
                                List.of(taskId)),
                        200);
        assertFalse(validation.path("valid").asBoolean());
        assertTrue(validation.path("issues").toString().contains("HISTORY_IMMUTABLE"));
        assertEquals(
                "COMPLETED",
                Rows.text(sql.one("SELECT status FROM task WHERE id=?", taskId), "status"));
    }

    @Test
    void exportAndPartialDeletionUseExactOwnedFactsAndPreserveOtherAccounts() throws Exception {
        JsonNode owner = registerUser(), other = registerUser();
        String chosen =
                request(post("/api/v1/records"), owner, record("ENERGY", "只导出这条"), 201)
                        .path("id")
                        .asText();
        String retained =
                request(post("/api/v1/records"), owner, record("MEAL", "保留这条"), 201)
                        .path("id")
                        .asText();
        String otherId =
                request(post("/api/v1/records"), other, record("ENERGY", "其他用户内容"), 201)
                        .path("id")
                        .asText();
        LocalDate outsideDay = LocalDate.now(SHANGHAI).minusDays(8);
        request(get("/api/v1/statistics/days/" + outsideDay), owner, null, 200);
        assertEquals(
                1,
                sql.count(
                        "SELECT COUNT(*) FROM daily_stat WHERE user_id=? AND stat_local_date=?",
                        owner.path("userId").asLong(),
                        outsideDay));
        Map<String, Object> scope =
                Rows.map(
                        "recordIds",
                        List.of(chosen),
                        "includeFiles",
                        false,
                        "includeAiContent",
                        false,
                        "includePlans",
                        false);
        JsonNode exported = queuePrivacy(owner, "EXPORT", scope, "JSON");
        worker.process(exported.path("requestId").asLong());
        Map<String, Object> exportRequest =
                sql.one(
                        "SELECT * FROM privacy_request WHERE id=?",
                        exported.path("requestId").asText());
        assertEquals(
                "COMPLETED",
                Rows.text(exportRequest, "status"),
                Rows.text(exportRequest, "error_code"));
        Map<String, Object> output =
                sql.one(
                        "SELECT * FROM file_object WHERE id=?",
                        Rows.number(exportRequest, "result_file_id"));
        JsonNode content = mapper.readTree(files.readContent(output));
        assertEquals(1, content.path("tables").path("health_record").size());
        assertEquals(
                chosen, content.path("tables").path("health_record").get(0).path("id").asText());
        assertEquals(
                "只导出这条",
                content.path("tables").path("health_record").get(0).path("notes").asText());
        assertFalse(content.toString().contains("encryption_key_ref"));
        assertFalse(content.toString().contains("object_key"));
        assertFalse(content.toString().contains("notes_cipher"));
        for (String excluded :
                List.of(
                        "daily_stat",
                        "weekly_review",
                        "weekly_review_evidence",
                        "user_goal",
                        "availability_rule",
                        "availability_exception",
                        "user_preference")) {
            assertFalse(content.path("tables").has(excluded), excluded + " 不属于选定记录导出范围");
        }

        JsonNode zipped = queuePrivacy(owner, "EXPORT", scope, "CSV_ZIP");
        worker.process(zipped.path("requestId").asLong());
        Map<String, Object> zipJob =
                sql.one(
                        "SELECT * FROM privacy_request WHERE id=?",
                        zipped.path("requestId").asText());
        assertEquals("COMPLETED", Rows.text(zipJob, "status"), Rows.text(zipJob, "error_code"));
        byte[] zippedBytes =
                files.readContent(
                        sql.one(
                                "SELECT * FROM file_object WHERE id=?",
                                Rows.number(zipJob, "result_file_id")));
        try (ZipInputStream zip =
                new ZipInputStream(
                        new java.io.ByteArrayInputStream(zippedBytes),
                        java.nio.charset.StandardCharsets.UTF_8)) {
            boolean recordCsv = false;
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null)
                if ("health_record.csv".equals(entry.getName())) {
                    String csv =
                            new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    assertTrue(csv.contains("只导出这条"));
                    assertFalse(csv.contains("保留这条"));
                    recordCsv = true;
                }
            assertTrue(recordCsv);
        }

        JsonNode stale = queuePrivacy(owner, "EXPORT", scope, "JSON");
        Map<String, Object> changed = record("ENERGY", "导出确认后被修改");
        changed.remove("clientRecordId");
        changed.remove("recordType");
        changed.put("expectedRowVersion", "1");
        request(put("/api/v1/records/" + chosen), owner, changed, 200);
        worker.process(stale.path("requestId").asLong());
        Map<String, Object> rejected =
                sql.one(
                        "SELECT status,error_code,result_file_id FROM privacy_request WHERE id=?",
                        stale.path("requestId").asText());
        assertEquals("FAILED", Rows.text(rejected, "status"));
        assertEquals("PREVIEW_STALE", Rows.text(rejected, "error_code"));
        assertNull(rejected.get("result_file_id"));

        long before = sequence(owner);
        JsonNode deletion = queuePrivacy(owner, "DELETE_RECORDS", scope, "JSON");
        worker.process(deletion.path("requestId").asLong());
        assertEquals(
                "COMPLETED",
                Rows.text(
                        sql.one(
                                "SELECT status FROM privacy_request WHERE id=?",
                                deletion.path("requestId").asText()),
                        "status"));
        assertEquals(0, sql.count("SELECT COUNT(*) FROM health_record WHERE id=?", chosen));
        assertEquals(1, sql.count("SELECT COUNT(*) FROM health_record WHERE id=?", retained));
        assertEquals(1, sql.count("SELECT COUNT(*) FROM health_record WHERE id=?", otherId));
        assertEquals(before + 1, sequence(owner));
        assertEquals(
                1,
                sql.count(
                        "SELECT dataset_epoch FROM user_sync_clock WHERE user_id=?",
                        owner.path("userId").asText()));
        assertEquals(
                1,
                sql.count(
                        "SELECT COUNT(*) FROM sync_change WHERE user_id=? AND entity_type='RECORD'"
                                + " AND entity_id=? AND operation='DELETE'",
                        owner.path("userId").asText(),
                        chosen));
        assertEquals(
                0,
                sql.count(
                        "SELECT COUNT(*) FROM sync_change WHERE user_id=? AND entity_type='RECORD'"
                                + " AND entity_id=? AND operation='UPSERT'",
                        owner.path("userId").asText(),
                        chosen));
        assertTrue(Files.readString(LEDGER).contains(deletion.path("requestId").asText()));
    }

    @Test
    void fullErasureAndAccountDeletionFinishOnlyAfterCleanupAndAdvanceEpochOnce() throws Exception {
        JsonNode user = registerUser();
        String owner = user.path("userId").asText();
        request(post("/api/v1/records"), user, record("SLEEP", "全量擦除内容"), 201);
        activePlan(user);
        long seq = sequence(user);
        JsonNode erase = queuePrivacy(user, "ERASE_HEALTH_DATA", Rows.map(), "JSON");
        request(post("/api/v1/records"), user, record("ENERGY", "栅栏后拒绝"), 409);
        worker.process(erase.path("requestId").asLong());
        Map<String, Object> erased =
                sql.one(
                        "SELECT * FROM privacy_request WHERE id=?",
                        erase.path("requestId").asText());
        assertEquals("COMPLETED", Rows.text(erased, "status"), Rows.text(erased, "error_code"));
        for (String table :
                List.of(
                        "health_record",
                        "plan",
                        "plan_version",
                        "plan_version_task",
                        "task",
                        "task_event",
                        "sync_change")) {
            assertEquals(
                    0,
                    sql.count("SELECT COUNT(*) FROM " + table + " WHERE user_id=?", owner),
                    table);
        }
        assertEquals(seq, sequence(user), "清除不重置提交序号");
        assertEquals(
                2, sql.count("SELECT dataset_epoch FROM user_sync_clock WHERE user_id=?", owner));
        assertEquals(
                "OPEN",
                Rows.text(
                        sql.one("SELECT data_write_fence FROM app_user WHERE id=?", owner),
                        "data_write_fence"));
        assertEquals(
                1,
                sql.count(
                        "SELECT COUNT(*) FROM file_object WHERE id=? AND purpose='PRIVACY_MANIFEST'"
                                + " AND status='CLEAN'",
                        Rows.number(erased, "scope_manifest_file_id")));
        worker.process(erase.path("requestId").asLong());
        assertEquals(
                2, sql.count("SELECT dataset_epoch FROM user_sync_clock WHERE user_id=?", owner));

        JsonNode account = registerUser();
        String accountId = account.path("userId").asText();
        request(post("/api/v1/records"), account, record("ENERGY", "注销的内容"), 201);
        activePlan(account);
        JsonNode preview =
                request(
                        post("/api/v1/privacy/requests/preview"),
                        account,
                        Rows.map("type", "DELETE_ACCOUNT", "scope", Rows.map(), "format", "JSON"),
                        200);
        String receipt = preview.path("receiptToken").asText();
        assertTrue(receipt.length() >= 32);
        confirm(account, preview);
        worker.process(preview.path("requestId").asLong());
        assertEquals(
                "DELETED",
                Rows.text(sql.one("SELECT status FROM app_user WHERE id=?", accountId), "status"));
        for (String table :
                List.of(
                        "health_record",
                        "task",
                        "plan",
                        "auth_session",
                        "client_installation",
                        "user_profile",
                        "user_preference")) {
            assertEquals(
                    0,
                    sql.count("SELECT COUNT(*) FROM " + table + " WHERE user_id=?", accountId),
                    table);
        }
        JsonNode status =
                request(
                        post("/api/v1/privacy/receipts/status"),
                        null,
                        Rows.map("receiptToken", receipt),
                        200);
        assertEquals(
                "COMPLETED", status.path("status").asText(), status.path("errorCode").asText());
        assertEquals(
                2,
                sql.count("SELECT dataset_epoch FROM user_sync_clock WHERE user_id=?", accountId));
    }

    private JsonNode queuePrivacy(
            JsonNode user, String type, Map<String, Object> scope, String format) throws Exception {
        JsonNode preview =
                request(
                        post("/api/v1/privacy/requests/preview"),
                        user,
                        Rows.map("type", type, "scope", scope, "format", format),
                        200);
        confirm(user, preview);
        return preview;
    }

    private void confirm(JsonNode user, JsonNode preview) throws Exception {
        request(
                post("/api/v1/privacy/requests/" + preview.path("requestId").asText() + "/confirm"),
                user,
                Rows.map(
                        "expectedRowVersion",
                        preview.path("rowVersion").asText(),
                        "manifestDigest",
                        preview.path("manifestDigest").asText(),
                        "expectedDataSeq",
                        preview.path("previewDataSeq").asText(),
                        "userConfirmed",
                        true,
                        "reauth",
                        Rows.map(
                                "method",
                                "PASSWORD",
                                "password",
                                "Integration-only-passphrase-2026")),
                202);
    }

    private ActivePlan activePlan(JsonNode user) throws Exception {
        LocalDate date = planDay();
        JsonNode created =
                request(
                        post("/api/v1/plans"),
                        user,
                        Rows.map(
                                "title",
                                "有稳定实例的计划",
                                "planKind",
                                "DAY",
                                "timeZone",
                                "Asia/Shanghai",
                                "startDate",
                                date,
                                "endDate",
                                date,
                                "goalIds",
                                List.of()),
                        201);
        String plan = created.path("id").asText(), version = created.path("planVersionId").asText();
        request(
                put("/api/v1/plans/" + plan + "/versions/" + version),
                user,
                Rows.map(
                        "expectedRowVersion",
                        "1",
                        "changeReason",
                        "配置一个可执行任务",
                        "tasks",
                        List.of(task("户外散步", null, null))),
                200);
        activate(user, plan, version, "2", null, List.of(), List.of());
        String task =
                sql.one(
                                "SELECT id FROM task WHERE user_id=? AND plan_id=?",
                                user.path("userId").asText(),
                                plan)
                        .get("id")
                        .toString();
        return new ActivePlan(plan, version, task);
    }

    private void activate(
            JsonNode user,
            String plan,
            String version,
            String rowVersion,
            String active,
            List<String> affected,
            List<Map<String, Object>> expected)
            throws Exception {
        request(
                post("/api/v1/plans/" + plan + "/versions/" + version + "/activate"),
                user,
                Rows.map(
                        "expectedRowVersion",
                        rowVersion,
                        "expectedActiveVersionId",
                        active,
                        "effectiveFrom",
                        effectiveFrom(),
                        "affectedTaskIds",
                        affected,
                        "expectedTasks",
                        expected,
                        "expectedDataSeq",
                        Long.toString(sequence(user)),
                        "userConfirmed",
                        true),
                200);
    }

    private Map<String, Object> task(String title, String id, String rowVersion) {
        return Rows.map(
                "draftTaskKey",
                UUID.randomUUID().toString(),
                "taskId",
                id,
                "expectedTaskRowVersion",
                rowVersion,
                "title",
                title,
                "taskType",
                "ACTIVITY",
                "scheduledStartAt",
                planDay().atTime(10, 0).atZone(SHANGHAI).toInstant(),
                "scheduledEndAt",
                planDay().atTime(10, 30).atZone(SHANGHAI).toInstant(),
                "plannedDurationMinutes",
                20,
                "taskTimeZone",
                "Asia/Shanghai",
                "isFixed",
                false,
                "priority",
                3,
                "sortOrder",
                0,
                "instructions",
                "量力而行");
    }

    private Map<String, Object> record(String type, String notes) {
        LocalDate day = LocalDate.now(SHANGHAI).minusDays(1);
        Instant start = day.atTime(12, 0).atZone(SHANGHAI).toInstant(), end = null;
        Map<String, Object> detail;
        switch (type) {
            case "SLEEP" -> {
                start = day.minusDays(1).atTime(23, 0).atZone(SHANGHAI).toInstant();
                end = day.atTime(7, 0).atZone(SHANGHAI).toInstant();
                detail = Rows.map("detailType", type, "restedLevel", 4, "sleepKind", "MAIN");
            }
            case "ACTIVITY" -> {
                end = start.plusSeconds(1800);
                detail =
                        Rows.map(
                                "detailType",
                                type,
                                "activityType",
                                "WALK",
                                "actualDurationMinutes",
                                20,
                                "intensity",
                                "EASY");
            }
            case "MEAL" ->
                    detail =
                            Rows.map(
                                    "detailType",
                                    type,
                                    "mealType",
                                    "LUNCH",
                                    "description",
                                    "米饭、豆腐与青菜",
                                    "vegetablePresent",
                                    true,
                                    "proteinFoodPresent",
                                    true);
            default ->
                    detail =
                            Rows.map(
                                    "detailType",
                                    "ENERGY",
                                    "energyLevel",
                                    4,
                                    "contextCode",
                                    "STUDY",
                                    "description",
                                    "状态平稳");
        }
        return Rows.map(
                "clientRecordId",
                UUID.randomUUID().toString(),
                "recordType",
                type,
                "startedAt",
                start,
                "endedAt",
                end,
                "recordTimeZone",
                "Asia/Shanghai",
                "recordLocalDate",
                day,
                "utcOffsetMinutes",
                480,
                "notes",
                notes,
                "tagIds",
                List.of(),
                "detail",
                detail);
    }

    private String version(String table, String id) {
        return sql.one("SELECT row_version FROM " + table + " WHERE id=?", id)
                .get("row_version")
                .toString();
    }

    private long sequence(JsonNode user) {
        return sql.count(
                "SELECT committed_seq FROM user_sync_clock WHERE user_id=?",
                user.path("userId").asText());
    }

    private LocalDate planDay() {
        return LocalDate.now(SHANGHAI).plusDays(2);
    }

    private Instant effectiveFrom() {
        return planDay().atStartOfDay(SHANGHAI).toInstant();
    }

    private record ActivePlan(String planId, String versionId, String taskId) {}
}
