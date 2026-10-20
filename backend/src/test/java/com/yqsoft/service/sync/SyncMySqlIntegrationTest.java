package com.yqsoft.service.sync;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.yqsoft.resource.common.Rows;
import com.yqsoft.support.MySqlFlowSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@EnabledIfEnvironmentVariable(named = "ZH_TEST_DB_URL", matches = ".+")
class SyncMySqlIntegrationTest extends MySqlFlowSupport {

    private static final List<String> TYPES =
            List.of(
                    "PROFILE",
                    "PREFERENCE",
                    "GOAL",
                    "AVAILABILITY_RULE",
                    "AVAILABILITY_EXCEPTION",
                    "RECORD",
                    "RECORD_TAG",
                    "PLAN",
                    "PLAN_VERSION",
                    "TASK",
                    "REMINDER_RULE",
                    "NOTIFICATION");

    @Test
    void completeBootstrapThenReplayOfflineMutationWithoutDuplicateFacts() throws Exception {
        JsonNode user = registerUser();
        String installation = user.path("installationId").asText();
        String epoch = user.path("datasetEpoch").asText();
        JsonNode begun =
                request(
                        post("/api/v1/sync/bootstrap"),
                        user,
                        Rows.map(
                                "installationId",
                                installation,
                                "datasetEpoch",
                                epoch,
                                "entityTypes",
                                TYPES),
                        200);
        String bootstrap = begun.path("bootstrapId").asText();
        String baseline = begun.path("snapshotFromSeq").asText();

        JsonNode premature =
                request(
                        post("/api/v1/sync/ack"),
                        user,
                        Rows.map(
                                "installationId",
                                installation,
                                "datasetEpoch",
                                epoch,
                                "ackSeq",
                                baseline,
                                "bootstrapId",
                                bootstrap,
                                "bootstrapCompleted",
                                true),
                        409);
        assertEquals("BOOTSTRAP_INCOMPLETE", premature.path("code").asText());
        for (String type : TYPES) {
            JsonNode page =
                    request(
                            get("/api/v1/sync/bootstrap/" + bootstrap + "/entities")
                                    .param("installationId", installation)
                                    .param("datasetEpoch", epoch)
                                    .param("entityType", type),
                            user,
                            null,
                            200);
            assertFalse(page.path("hasMore").asBoolean());
            String json = page.toString();
            assertFalse(json.contains("_cipher"));
            assertFalse(json.contains("password"));
        }
        JsonNode caughtUp =
                request(
                        get("/api/v1/sync/changes")
                                .param("installationId", installation)
                                .param("datasetEpoch", epoch)
                                .param("afterSeq", baseline)
                                .param("bootstrapId", bootstrap),
                        user,
                        null,
                        200);
        String delivered = caughtUp.path("nextAfterSeq").asText();
        // A concurrent writer after the last page must be caught up before READY is committed.
        request(
                post("/api/v1/record-tags"),
                user,
                Rows.map("name", "握手期间新增", "color", "TEAL", "sortOrder", 0),
                201);
        request(
                post("/api/v1/sync/ack"),
                user,
                Rows.map(
                        "installationId",
                        installation,
                        "datasetEpoch",
                        epoch,
                        "ackSeq",
                        delivered,
                        "bootstrapId",
                        bootstrap,
                        "bootstrapCompleted",
                        true),
                409);
        caughtUp =
                request(
                        get("/api/v1/sync/changes")
                                .param("installationId", installation)
                                .param("datasetEpoch", epoch)
                                .param("afterSeq", delivered)
                                .param("bootstrapId", bootstrap),
                        user,
                        null,
                        200);
        delivered = caughtUp.path("nextAfterSeq").asText();
        request(
                post("/api/v1/sync/ack"),
                user,
                Rows.map(
                        "installationId",
                        installation,
                        "datasetEpoch",
                        epoch,
                        "ackSeq",
                        delivered,
                        "bootstrapId",
                        bootstrap,
                        "bootstrapCompleted",
                        true),
                200);

        String operationId = UUID.randomUUID().toString();
        var operation =
                Rows.map(
                        "clientOperationId",
                        operationId,
                        "operationCode",
                        "RECORD_CREATE",
                        "datasetEpoch",
                        epoch,
                        "payload",
                        energyRecord(),
                        "clientOccurredAt",
                        Instant.now().toString(),
                        "createdOffline",
                        true);
        var batch =
                Rows.map(
                        "installationId",
                        installation,
                        "datasetEpoch",
                        epoch,
                        "operations",
                        List.of(operation));
        JsonNode first = request(post("/api/v1/sync/operations"), user, batch, 200);
        assertEquals("APPLIED", first.path("results").get(0).path("status").asText());
        String recordId = first.path("results").get(0).path("result").path("id").asText();
        JsonNode second = request(post("/api/v1/sync/operations"), user, batch, 200);
        assertEquals("REPLAYED", second.path("results").get(0).path("status").asText());
        assertEquals(
                1,
                sql.count(
                        "SELECT COUNT(*) FROM health_record WHERE user_id=?",
                        Long.parseLong(user.path("userId").asText())));

        JsonNode changes =
                request(
                        get("/api/v1/sync/changes")
                                .param("installationId", installation)
                                .param("datasetEpoch", epoch)
                                .param("afterSeq", delivered),
                        user,
                        null,
                        200);
        boolean found = false;
        for (JsonNode change : changes.path("items")) {
            if (recordId.equals(change.path("entityId").asText())) {
                found = true;
                assertEquals("RECORD", change.path("entityType").asText());
                assertEquals(4, change.path("payload").path("detail").path("energyLevel").asInt());
            }
        }
        assertTrue(found);
        var stored =
                sql.one(
                        "SELECT payload_json FROM sync_change WHERE user_id=? AND"
                                + " entity_type='RECORD'",
                        Long.parseLong(user.path("userId").asText()));
        assertTrue(Rows.json(stored, "payload_json").has("ciphertext"));
        assertFalse(Rows.json(stored, "payload_json").has("detail"));

        JsonNode excessiveAck =
                request(
                        post("/api/v1/sync/ack"),
                        user,
                        Rows.map(
                                "installationId",
                                installation,
                                "datasetEpoch",
                                epoch,
                                "ackSeq",
                                "99999999"),
                        422);
        assertEquals("INVALID_ACK", excessiveAck.path("code").asText());
    }

    @Test
    void rejectsCrossInstallationAndStaleEpoch() throws Exception {
        JsonNode owner = registerUser();
        JsonNode other = registerUser();
        request(
                post("/api/v1/sync/bootstrap"),
                other,
                Rows.map(
                        "installationId",
                        owner.path("installationId").asText(),
                        "datasetEpoch",
                        "1",
                        "entityTypes",
                        List.of("RECORD")),
                404);
        JsonNode stale =
                request(
                        post("/api/v1/sync/bootstrap"),
                        owner,
                        Rows.map(
                                "installationId",
                                owner.path("installationId").asText(),
                                "datasetEpoch",
                                "2",
                                "entityTypes",
                                List.of("RECORD")),
                        409);
        assertEquals("DATA_EPOCH_CHANGED", stale.path("code").asText());
    }

    private Object energyRecord() {
        return Rows.map(
                "clientRecordId",
                UUID.randomUUID().toString(),
                "recordType",
                "ENERGY",
                "startedAt",
                "2026-10-18T04:00:00Z",
                "recordTimeZone",
                "Asia/Shanghai",
                "recordLocalDate",
                "2026-10-18",
                "utcOffsetMinutes",
                480,
                "notes",
                "午间状态记录",
                "tagIds",
                List.of(),
                "detail",
                Rows.map(
                        "detailType",
                        "ENERGY",
                        "energyLevel",
                        4,
                        "contextCode",
                        "STUDY",
                        "description",
                        "学习后状态良好"));
    }
}
