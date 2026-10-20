package com.yqsoft.service.privacy;

import static com.yqsoft.resource.common.Rows.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yqsoft.config.AppProperties;
import com.yqsoft.dao.SqlStore;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.service.common.CryptoService;
import com.yqsoft.service.file.FileService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Durable, restartable data-control jobs. No request-thread transaction spans external work. */
@Service
public class PrivacyWorker {
    private static final Set<String> FINAL = Set.of("COMPLETED", "CANCELLED", "EXPIRED", "FAILED");
    private static final Object LEDGER_LOCK = new Object();
    private final SqlStore sql;
    private final PrivacyService privacy;
    private final CryptoService crypto;
    private final FileService files;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final AppProperties properties;
    private final TransactionTemplate transactions;
    private final Path ledger;
    private final String workerId = UUID.randomUUID().toString();
    private final AtomicBoolean polling = new AtomicBoolean();

    public PrivacyWorker(
            SqlStore sql,
            PrivacyService privacy,
            CryptoService crypto,
            FileService files,
            ObjectMapper mapper,
            Clock clock,
            AppProperties properties,
            PlatformTransactionManager transactionManager,
            @Value("${zhiheng.privacy.deletion-ledger:.local/privacy-ledger.jsonl}")
                    String ledger) {
        this.sql = sql;
        this.privacy = privacy;
        this.crypto = crypto;
        this.files = files;
        this.mapper = mapper;
        this.clock = clock;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setTimeout(120);
        this.ledger = Path.of(ledger).toAbsolutePath().normalize();
    }

    @Scheduled(fixedDelayString = "${zhiheng.privacy.poll-delay-ms:2000}")
    public void poll() {
        if (!properties.isJobsEnabled()) return;
        if (!polling.compareAndSet(false, true)) return;
        try {
            for (Map<String, Object> row :
                    sql.query(
                            "SELECT id FROM privacy_request WHERE status IN"
                                    + " ('QUEUED','PROCESSING','PARTIAL_FAILED') ORDER BY id LIMIT"
                                    + " 8")) {
                process(number(row, "id"));
            }
        } finally {
            polling.set(false);
        }
    }

    /**
     * Public for controlled operational retry and integration verification; not a public HTTP
     * endpoint.
     */
    public void process(long requestId) {
        Map<String, Object> claimed = claim(requestId);
        if (claimed == null) return;
        try {
            JsonNode manifest = loadManifest(claimed);
            if ("EXPORT".equals(text(claimed, "request_type"))) export(claimed, manifest);
            else erase(claimed, manifest);
        } catch (BusinessException exception) {
            failed(
                    claimed,
                    exception.getCode(),
                    Set.of(
                                    "PREVIEW_STALE",
                                    "DATA_EPOCH_CHANGED",
                                    "INVALID_PRIVACY_MANIFEST",
                                    "EXPORT_SCOPE_TOO_LARGE",
                                    "EXPORT_CONTENT_INVALID")
                            .contains(exception.getCode()));
        } catch (Exception exception) {
            // Never copy SQL parameters, record text, paths or cryptographic values into job
            // errors.
            failed(claimed, "PRIVACY_PROCESSING_FAILED", false);
        }
    }

    private Map<String, Object> claim(long id) {
        Optional<Map<String, Object>> found =
                sql.find("SELECT user_id FROM privacy_request WHERE id=?", id);
        if (found.isEmpty()) return null;
        long user = number(found.get(), "user_id");
        return transactions.execute(
                transaction -> {
                    lockOwner(user);
                    Map<String, Object> row =
                            sql.one(
                                    "SELECT * FROM privacy_request WHERE id=? AND user_id=? FOR"
                                            + " UPDATE",
                                    id,
                                    user);
                    String status = text(row, "status");
                    Map<String, Object> progress = progress(row);
                    if (FINAL.contains(status)
                            || !Set.of("QUEUED", "PROCESSING", "PARTIAL_FAILED").contains(status))
                        return null;
                    Instant retry = parseInstant(progress.get("nextAttemptAt"));
                    if (retry != null && retry.isAfter(clock.instant())) return null;
                    Instant lease = parseInstant(progress.get("leaseUntil"));
                    if ("PROCESSING".equals(status)
                            && lease != null
                            && lease.isAfter(clock.instant())) return null;
                    progress.put("leaseOwner", workerId);
                    progress.put("leaseUntil", clock.instant().plusSeconds(300).toString());
                    progress.put(
                            "attempt",
                            ((Number) progress.getOrDefault("attempt", 0)).intValue() + 1);
                    progress.remove("nextAttemptAt");
                    progress.putIfAbsent("phase", "VERIFYING");
                    sql.execute(
                            "UPDATE privacy_request SET"
                                + " status='PROCESSING',started_at=COALESCE(started_at,?),error_code=NULL,progress_json=?,updated_at=?,row_version=row_version+1"
                                + " WHERE id=? AND user_id=?",
                            clock.instant(),
                            progress,
                            clock.instant(),
                            id,
                            user);
                    return sql.one(
                            "SELECT * FROM privacy_request WHERE id=? AND user_id=?", id, user);
                });
    }

    private JsonNode loadManifest(Map<String, Object> request) {
        Map<String, Object> row =
                sql.one(
                        "SELECT * FROM file_object WHERE id=? AND user_id=? AND"
                                + " purpose='PRIVACY_MANIFEST' AND status='CLEAN'",
                        number(request, "scope_manifest_file_id"),
                        number(request, "user_id"));
        byte[] content = files.readContent(row);
        if (!MessageDigest.isEqual((byte[]) request.get("scope_digest"), crypto.sha256(content)))
            throw stale();
        try {
            JsonNode manifest = mapper.readTree(content);
            if (!manifest.path("tables").isObject()
                    || !text(request, "request_type").equals(manifest.path("type").asText()))
                throw stale();
            manifest.path("tables")
                    .fieldNames()
                    .forEachRemaining(
                            table -> {
                                if (!allowedTables().contains(table))
                                    throw BusinessException.badRequest(
                                            "INVALID_PRIVACY_MANIFEST", "清单含未允许的数据模块");
                            });
            return manifest;
        } catch (IOException exception) {
            throw BusinessException.badRequest("INVALID_PRIVACY_MANIFEST", "删除清单无法校验");
        }
    }

    private void export(Map<String, Object> request, JsonNode manifest) {
        long user = number(request, "user_id"), id = number(request, "id");
        Map<String, List<Map<String, Object>>> rows =
                transactions.execute(
                        transaction -> {
                            guard(request, false);
                            assertFrozen(manifest, user);
                            int rowCount = 0;
                            for (JsonNode targets : manifest.path("tables"))
                                rowCount += targets.size();
                            if (rowCount > 10000)
                                throw BusinessException.badRequest(
                                        "EXPORT_SCOPE_TOO_LARGE", "单次导出范围过大，请缩小日期或关闭非必要模块");
                            Map<String, List<Map<String, Object>>> data = new LinkedHashMap<>();
                            long[] encodedBytes = {0};
                            manifest.path("tables")
                                    .fields()
                                    .forEachRemaining(
                                            entry -> {
                                                String table = entry.getKey();
                                                List<Map<String, Object>> selected =
                                                        new ArrayList<>();
                                                for (JsonNode target : entry.getValue()) {
                                                    Map<String, Object> item =
                                                            exportRow(
                                                                    table,
                                                                    target.path("id").asLong(),
                                                                    user);
                                                    encodedBytes[0] += privacy.json(item).length;
                                                    if (encodedBytes[0] > 32L * 1024 * 1024)
                                                        throw BusinessException.badRequest(
                                                                "EXPORT_SCOPE_TOO_LARGE",
                                                                "单次导出内容超过32MiB，请缩小范围");
                                                    selected.add(item);
                                                }
                                                data.put(table, selected);
                                            });
                            return data;
                        });
        String format = json(request, "scope_filter_json").path("format").asText("JSON");
        byte[] content = encodeExport(request, rows, format);
        Long fileId = null;
        try {
            fileId =
                    files.createServerFile(
                            user,
                            "EXPORT",
                            "zhiheng-export." + ("CSV_ZIP".equals(format) ? "zip" : "json"),
                            "CSV_ZIP".equals(format) ? "application/zip" : "application/json",
                            content,
                            clock.instant().plusSeconds(86400),
                            number(request, "captured_dataset_epoch"));
            long output = fileId;
            transactions.executeWithoutResult(
                    transaction -> {
                        guard(request, false);
                        assertFrozen(manifest, user);
                        Map<String, Object> current = lockedRequest(request);
                        if (!"PROCESSING".equals(text(current, "status")))
                            throw BusinessException.conflict("EXPORT_CANCELLED", "导出已取消");
                        sql.execute(
                                "UPDATE privacy_request SET"
                                    + " status='COMPLETED',result_file_id=?,completed_at=?,error_code=NULL,progress_json=?,updated_at=?,row_version=row_version+1"
                                    + " WHERE id=? AND user_id=?",
                                output,
                                clock.instant(),
                                map(
                                        "phase",
                                        "COMPLETED",
                                        "format",
                                        format,
                                        "exportedTables",
                                        rows.size()),
                                clock.instant(),
                                id,
                                user);
                    });
        } catch (RuntimeException exception) {
            if (fileId != null) destroyFile(user, fileId);
            throw exception;
        }
    }

    private void erase(Map<String, Object> request, JsonNode manifest) throws IOException {
        long user = number(request, "user_id"), id = number(request, "id");
        boolean full = isFull(request);
        Map<String, Object> state =
                sql.one("SELECT * FROM privacy_request WHERE id=? AND user_id=?", id, user);
        if (!Boolean.TRUE.equals(progress(state).get("dbErased"))) {
            transactions.executeWithoutResult(
                    transaction -> {
                        guard(request, full);
                        Map<String, Object> current = lockedRequest(request);
                        if (Boolean.TRUE.equals(progress(current).get("dbErased"))) return;
                        if (!full) assertFrozen(manifest, user);
                        if (full)
                            eraseFullRows(
                                    user,
                                    "DELETE_ACCOUNT".equals(text(request, "request_type")),
                                    (byte[]) request.get("scope_digest"));
                        else eraseSelectedRows(request, manifest);
                        markFilesForDeletion(manifest, user);
                        Map<String, Object> p = progress(current);
                        p.put("dbErased", true);
                        p.put("phase", "FILES");
                        p.put("leaseOwner", workerId);
                        p.put("leaseUntil", clock.instant().plusSeconds(300).toString());
                        sql.execute(
                                "UPDATE privacy_request SET"
                                        + " progress_json=?,updated_at=?,row_version=row_version+1"
                                        + " WHERE id=? AND user_id=?",
                                p,
                                clock.instant(),
                                id,
                                user);
                    });
        }
        // Objects are already private tombstones. Local disk failures keep the job
        // retryable/fenced.
        int deletedFiles = 0;
        for (long file : ids(manifest, "file_object")) {
            Map<String, Object> row =
                    sql.one("SELECT * FROM file_object WHERE user_id=? AND id=?", user, file);
            if ("PRIVACY_MANIFEST".equals(text(row, "purpose"))) continue;
            if (!"LOCAL_PRIVATE".equals(text(row, "storage_provider")))
                throw BusinessException.unavailable("FILE_ADAPTER_NOT_CONFIGURED", "该私有存储尚无删除适配器");
            if (stillReferenced(user, file)) continue;
            Files.deleteIfExists(files.objectPath(text(row, "object_key")));
            sql.execute(
                    "UPDATE file_object SET"
                        + " status='DELETED',original_name_cipher=NULL,sha256=NULL,byte_size=NULL,mime_type=NULL,expected_sha256=?,deleted_at=COALESCE(deleted_at,?),deleted_from_storage_at=?,updated_at=?,row_version=row_version+1"
                        + " WHERE user_id=? AND id=? AND purpose<>'PRIVACY_MANIFEST'",
                    crypto.sha256(("erased:" + user + ":" + file).getBytes(StandardCharsets.UTF_8)),
                    clock.instant(),
                    clock.instant(),
                    clock.instant(),
                    user,
                    file);
            deletedFiles++;
        }
        Map<String, Object> ledgerEntry =
                map(
                        "requestId",
                        Long.toString(id),
                        "userId",
                        Long.toString(user),
                        "type",
                        text(request, "request_type"),
                        "datasetEpoch",
                        Long.toString(number(request, "captured_dataset_epoch") + (full ? 1 : 0)),
                        "scopeDigest",
                        HexFormat.of().formatHex((byte[]) request.get("scope_digest")),
                        "manifestFileId",
                        Long.toString(number(request, "scope_manifest_file_id")),
                        "targets",
                        manifest.path("tables"),
                        "recordedAt",
                        clock.instant().toString());
        appendLedger(ledgerEntry);
        int filesCount = deletedFiles;
        transactions.executeWithoutResult(
                transaction -> {
                    guard(request, full);
                    Map<String, Object> current = lockedRequest(request);
                    if ("COMPLETED".equals(text(current, "status"))) return;
                    if (!Boolean.TRUE.equals(progress(current).get("dbErased")))
                        throw new IllegalStateException("Erasure checkpoint missing");
                    if (full) {
                        sql.execute(
                                "UPDATE privacy_request SET"
                                    + " status='CANCELLED',error_code='SUPERSEDED_BY_ERASURE',updated_at=?,row_version=row_version+1"
                                    + " WHERE user_id=? AND id<>? AND status IN"
                                    + " ('QUEUED','PROCESSING','PARTIAL_FAILED')",
                                clock.instant(),
                                user,
                                id);
                        sql.execute(
                                "UPDATE user_sync_clock SET"
                                    + " dataset_epoch=dataset_epoch+1,retained_from_seq=committed_seq+1,updated_at=?"
                                    + " WHERE user_id=?",
                                clock.instant(),
                                user);
                        sql.execute(
                                "UPDATE client_installation SET"
                                    + " sync_state='UNINITIALIZED',sync_epoch=NULL,bootstrap_id=NULL,bootstrap_from_seq=NULL,bootstrap_expires_at=NULL,bootstrap_epoch=NULL,bootstrap_progress_json=NULL,bootstrap_completed_at=NULL,last_ack_seq=0,last_delivered_seq=0,device_credential_hash=NULL,credential_expires_at=NULL,pairing_token_hash=NULL,pairing_expires_at=NULL,credential_revision=credential_revision+1,updated_at=?,row_version=row_version+1"
                                    + " WHERE user_id=?",
                                clock.instant(),
                                user);
                        if ("DELETE_ACCOUNT".equals(text(request, "request_type"))) {
                            sql.execute(
                                    "UPDATE app_user SET"
                                        + " status='DELETED',data_write_fence='ACCOUNT_DELETING',password_hash=NULL,password_updated_at=NULL,last_login_at=NULL,lock_until=NULL,email_lookup_hmac=?,email_cipher=?,email_key_ref=?,anonymized_at=?,deleted_at=?,security_version=security_version+1,updated_at=?,row_version=row_version+1"
                                        + " WHERE id=?",
                                    crypto.hmac("deleted-account", Long.toString(user)),
                                    crypto.encrypt("deleted-account-" + user),
                                    crypto.keyRef(),
                                    clock.instant(),
                                    clock.instant(),
                                    clock.instant(),
                                    user);
                        } else {
                            sql.execute(
                                    "UPDATE app_user SET"
                                        + " data_write_fence='OPEN',updated_at=?,row_version=row_version+1"
                                        + " WHERE id=? AND status='ACTIVE'",
                                    clock.instant(),
                                    user);
                        }
                    }
                    sql.execute(
                            "UPDATE privacy_request SET"
                                + " status='COMPLETED',completed_at=?,error_code=NULL,progress_json=?,updated_at=?,row_version=row_version+1"
                                + " WHERE id=? AND user_id=?",
                            clock.instant(),
                            map(
                                    "phase",
                                    "COMPLETED",
                                    "dbErased",
                                    true,
                                    "deletedFiles",
                                    filesCount,
                                    "epochAdvanced",
                                    full,
                                    "deletionLedgerAppended",
                                    true),
                            clock.instant(),
                            id,
                            user);
                });
    }

    private void eraseSelectedRows(Map<String, Object> request, JsonNode manifest) {
        long user = number(request, "user_id");
        List<Long> records = ids(manifest, "health_record");
        Map<Long, Long> versions = new LinkedHashMap<>();
        Set<LocalDate> dates = new HashSet<>();
        for (long id : records) {
            Map<String, Object> row =
                    sql.one(
                            "SELECT id,row_version,record_local_date FROM health_record WHERE"
                                    + " user_id=? AND id=?",
                            user,
                            id);
            versions.put(id, number(row, "row_version") + 1);
            dates.add(date(row, "record_local_date"));
        }
        deleteManifestRows(
                manifest, user, List.of("weekly_review_evidence", "weekly_review", "daily_stat"));
        forChunks(
                records,
                chunk -> {
                    deleteIn("weekly_review_evidence", "record_id", user, chunk);
                    deleteIn("task_record_link", "record_id", user, chunk);
                    deleteIn("record_attachment", "record_id", user, chunk);
                    deleteIn("record_tag_relation", "record_id", user, chunk);
                    for (String detail :
                            List.of(
                                    "sleep_record_detail",
                                    "activity_record_detail",
                                    "meal_record_detail",
                                    "energy_record_detail"))
                        deleteIn(detail, "record_id", user, chunk);
                    deleteIn("health_record", "id", user, chunk);
                    sql.execute(
                            "DELETE FROM sync_change WHERE user_id=? AND entity_type='RECORD' AND"
                                    + " entity_id IN ("
                                    + marks(chunk.size())
                                    + ")",
                            args(user, chunk));
                });
        long sequence =
                sql.count("SELECT committed_seq FROM user_sync_clock WHERE user_id=?", user);
        long epoch = number(request, "captured_dataset_epoch");
        String operation =
                UUID.nameUUIDFromBytes(
                                ("privacy:" + number(request, "id"))
                                        .getBytes(StandardCharsets.UTF_8))
                        .toString();
        for (var record : versions.entrySet()) {
            sequence++;
            sql.insert(
                    "sync_change",
                    map(
                            "user_id",
                            user,
                            "change_seq",
                            sequence,
                            "dataset_epoch",
                            epoch,
                            "entity_type",
                            "RECORD",
                            "entity_id",
                            record.getKey(),
                            "entity_row_version",
                            record.getValue(),
                            "operation",
                            "DELETE",
                            "changed_at",
                            clock.instant(),
                            "payload_schema_version",
                            1,
                            "payload_json",
                            map(
                                    "id",
                                    Long.toString(record.getKey()),
                                    "rowVersion",
                                    Long.toString(record.getValue()),
                                    "datasetEpoch",
                                    Long.toString(epoch)),
                            "operation_id",
                            operation));
        }
        sql.execute(
                "UPDATE user_sync_clock SET committed_seq=?,updated_at=? WHERE user_id=?",
                sequence,
                clock.instant(),
                user);
        if (manifest.path("tables").has("ai_conversation")) purgeAi(manifest, user, false);
        deleteManifestRows(
                manifest,
                user,
                List.of("reminder_delivery", "reminder_rule", "notification_inbox"));
        if (!dates.isEmpty())
            sql.insert(
                    "outbox_event",
                    map(
                            "user_id",
                            user,
                            "event_key",
                            UUID.randomUUID().toString(),
                            "event_type",
                            "STAT_INVALIDATED",
                            "aggregate_type",
                            "PRIVACY_REQUEST",
                            "aggregate_id",
                            number(request, "id"),
                            "payload_schema_version",
                            1,
                            "payload_json",
                            map("dates", dates.stream().map(LocalDate::toString).sorted().toList()),
                            "status",
                            "PENDING",
                            "available_at",
                            clock.instant(),
                            "captured_dataset_epoch",
                            epoch));
    }

    private void eraseFullRows(long user, boolean account, byte[] confirmationDigest) {
        // Break nullable cycles first, always scoped to this one owner.
        sql.execute(
                "UPDATE plan SET active_version_id=NULL,origin_draft_id=NULL WHERE user_id=?",
                user);
        sql.execute(
                "UPDATE plan_version SET"
                    + " source_draft_id=NULL,based_on_version_id=NULL,restore_from_version_id=NULL"
                    + " WHERE user_id=?",
                user);
        sql.execute(
                "UPDATE ai_draft SET base_plan_id=NULL,base_plan_version_id=NULL WHERE user_id=?",
                user);
        sql.execute("UPDATE health_record SET ai_draft_id=NULL WHERE user_id=?", user);
        for (String table :
                List.of(
                        "weekly_review_evidence",
                        "weekly_review",
                        "daily_stat",
                        "ai_message_citation",
                        "device_task_receipt",
                        "task_event",
                        "task_record_link",
                        "reminder_delivery",
                        "reminder_rule",
                        "plan_version_task",
                        "task",
                        "plan_rule",
                        "plan_version",
                        "plan",
                        "record_attachment",
                        "record_tag_relation",
                        "sleep_record_detail",
                        "activity_record_detail",
                        "meal_record_detail",
                        "energy_record_detail",
                        "health_record",
                        "record_tag",
                        "availability_exception",
                        "availability_rule",
                        "user_goal")) deleteOwner(table, user);
        purgeAi(null, user, true);
        for (String table :
                List.of(
                        "import_batch",
                        "external_data_connection",
                        "notification_inbox",
                        "sync_change",
                        "sync_operation",
                        "outbox_event")) deleteOwner(table, user);
        revokeConsents(user, confirmationDigest);
        if (account) {
            Map<String, Object> owner =
                    sql.one("SELECT email_lookup_hmac FROM app_user WHERE id=?", user);
            sql.execute(
                    "DELETE FROM auth_verification WHERE user_id=? OR destination_hash=?",
                    user,
                    owner.get("email_lookup_hmac"));
            sql.execute(
                    "UPDATE auth_session SET"
                            + " parent_session_id=NULL,replaced_by_session_id=NULL,consumed_at=NULL"
                            + " WHERE user_id=?",
                    user);
            deleteOwner("auth_session", user);
            deleteOwner("auth_identity", user);
            deleteOwner("user_role_assignment", user);
            deleteOwner("feedback_ticket", user);
            deleteOwner("user_profile", user);
            deleteOwner("user_preference", user);
            sql.execute("UPDATE user_consent SET installation_id=NULL WHERE user_id=?", user);
            sql.execute("UPDATE user_consent_event SET installation_id=NULL WHERE user_id=?", user);
            sql.execute(
                    "DELETE FROM client_installation WHERE user_id=? AND installation_kind='WATCH'",
                    user);
            sql.execute("DELETE FROM client_installation WHERE user_id=?", user);
        } else {
            sql.execute(
                    "UPDATE user_preference SET"
                        + " preferred_wake_time=NULL,preferred_bed_time=NULL,preferred_activity_types_json=JSON_ARRAY(),excluded_activity_types_json=JSON_ARRAY(),daily_task_limit=4,default_task_minutes=15,preferred_session_minutes=NULL,plan_tone='NEUTRAL',theme_mode='SYSTEM',reduced_motion=0,font_scale=1.00,quiet_hours_enabled=0,quiet_start=NULL,quiet_end=NULL,updated_at=?,row_version=row_version+1"
                        + " WHERE user_id=?",
                    clock.instant(),
                    user);
            sql.execute(
                    "UPDATE user_profile SET"
                        + " bio_cipher=NULL,encryption_key_ref=NULL,updated_at=?,row_version=row_version+1"
                        + " WHERE user_id=?",
                    clock.instant(),
                    user);
        }
    }

    private void purgeAi(JsonNode manifest, long user, boolean all) {
        List<Long> drafts = all ? ownerIds("ai_draft", user) : ids(manifest, "ai_draft");
        forChunks(
                drafts,
                chunk -> {
                    updateIn("plan", "origin_draft_id", user, chunk, "origin_draft_id=NULL");
                    updateIn(
                            "plan_version", "source_draft_id", user, chunk, "source_draft_id=NULL");
                    updateIn("health_record", "ai_draft_id", user, chunk, "ai_draft_id=NULL");
                });
        if (all) {
            sql.execute("UPDATE ai_conversation SET last_message_id=NULL WHERE user_id=?", user);
            sql.execute("UPDATE ai_run SET input_message_id=NULL WHERE user_id=?", user);
            sql.execute(
                    "UPDATE ai_message SET reply_to_message_id=NULL,ai_run_id=NULL,draft_id=NULL"
                            + " WHERE user_id=?",
                    user);
            for (String table :
                    List.of(
                            "ai_message_citation",
                            "ai_draft_revision",
                            "ai_message",
                            "ai_draft",
                            "ai_run",
                            "ai_conversation")) deleteOwner(table, user);
        } else {
            forChunks(
                    ids(manifest, "ai_conversation"),
                    chunk ->
                            updateIn("ai_conversation", "id", user, chunk, "last_message_id=NULL"));
            forChunks(
                    ids(manifest, "ai_run"),
                    chunk -> updateIn("ai_run", "id", user, chunk, "input_message_id=NULL"));
            forChunks(
                    ids(manifest, "ai_message"),
                    chunk ->
                            updateIn(
                                    "ai_message",
                                    "id",
                                    user,
                                    chunk,
                                    "reply_to_message_id=NULL,ai_run_id=NULL,draft_id=NULL"));
            forChunks(
                    ids(manifest, "ai_run"),
                    chunk ->
                            sql.execute(
                                    "UPDATE outbox_event SET status='CANCELLED',updated_at=? WHERE"
                                            + " user_id=? AND event_type='AI_RUN_QUEUED' AND"
                                            + " aggregate_id IN ("
                                            + marks(chunk.size())
                                            + ")",
                                    prepend(clock.instant(), args(user, chunk))));
            deleteManifestRows(
                    manifest,
                    user,
                    List.of(
                            "ai_message_citation",
                            "ai_draft_revision",
                            "ai_message",
                            "ai_draft",
                            "ai_run",
                            "ai_conversation"));
        }
    }

    private void revokeConsents(long user, byte[] confirmationDigest) {
        for (Map<String, Object> row :
                sql.query(
                        "SELECT * FROM user_consent WHERE user_id=? AND purpose_code IN"
                                + " ('AI_PROCESSING','EXTERNAL_HEALTH','NOTIFICATION')",
                        user)) {
            long version = number(row, "consent_version") + 1;
            long event =
                    sql.insert(
                            "user_consent_event",
                            map(
                                    "user_id",
                                    user,
                                    "consent_id",
                                    number(row, "id"),
                                    "event_type",
                                    "REVOKE",
                                    "consent_version",
                                    version,
                                    "policy_version",
                                    text(row, "policy_version"),
                                    "scope_json",
                                    json(row, "scope_json"),
                                    "occurred_at",
                                    clock.instant(),
                                    "operation_id",
                                    UUID.randomUUID().toString(),
                                    "notice_digest",
                                    confirmationDigest));
            sql.execute(
                    "UPDATE user_consent SET"
                        + " status='REVOKED',revoked_at=?,consent_version=?,last_event_id=?,updated_at=?,row_version=row_version+1"
                        + " WHERE user_id=? AND id=?",
                    clock.instant(),
                    version,
                    event,
                    clock.instant(),
                    user,
                    number(row, "id"));
        }
    }

    private void markFilesForDeletion(JsonNode manifest, long user) {
        for (long id : ids(manifest, "file_object")) {
            Map<String, Object> row =
                    sql.one("SELECT * FROM file_object WHERE user_id=? AND id=?", user, id);
            if ("PRIVACY_MANIFEST".equals(text(row, "purpose")) || stillReferenced(user, id))
                continue;
            sql.execute(
                    "UPDATE file_object SET"
                        + " status='DELETING',deleted_at=COALESCE(deleted_at,?),original_name_cipher=NULL,updated_at=?,row_version=row_version+1"
                        + " WHERE user_id=? AND id=?",
                    clock.instant(),
                    clock.instant(),
                    user,
                    id);
        }
    }

    private boolean stillReferenced(long user, long file) {
        return sql.count(
                                "SELECT COUNT(*) FROM record_attachment WHERE user_id=? AND"
                                        + " file_id=?",
                                user,
                                file)
                        > 0
                || sql.count(
                                "SELECT COUNT(*) FROM user_profile WHERE user_id=? AND"
                                        + " avatar_file_id=?",
                                user,
                                file)
                        > 0
                || sql.count(
                                "SELECT COUNT(*) FROM feedback_ticket WHERE user_id=? AND"
                                        + " JSON_CONTAINS(attachment_file_ids_json,JSON_QUOTE(?))",
                                user,
                                Long.toString(file))
                        > 0;
    }

    private void assertFrozen(JsonNode manifest, long user) {
        manifest.path("tables")
                .fields()
                .forEachRemaining(
                        entry -> {
                            String table = entry.getKey();
                            String primary = primary(table);
                            for (JsonNode target : entry.getValue()) {
                                Map<String, Object> row =
                                        sql.find(
                                                        "SELECT * FROM "
                                                                + sql.metadata().table(table)
                                                                + " WHERE user_id=? AND "
                                                                + sql.metadata()
                                                                        .column(table, primary)
                                                                + "=?",
                                                        user,
                                                        target.path("id").asLong())
                                                .orElseThrow(PrivacyWorker::stale);
                                if (sql.metadata().hasColumn(table, "row_version")
                                        && number(row, "row_version")
                                                != target.path("row_version").asLong())
                                    throw stale();
                            }
                        });
    }

    private void guard(Map<String, Object> request, boolean full) {
        long user = number(request, "user_id");
        Map<String, Object> clockRow =
                sql.one("SELECT * FROM user_sync_clock WHERE user_id=? FOR UPDATE", user);
        Map<String, Object> account =
                sql.one("SELECT status,data_write_fence FROM app_user WHERE id=? FOR UPDATE", user);
        if (number(clockRow, "dataset_epoch") != number(request, "captured_dataset_epoch"))
            throw BusinessException.conflict("DATA_EPOCH_CHANGED", "数据代际已改变");
        String required =
                full
                        ? ("DELETE_ACCOUNT".equals(text(request, "request_type"))
                                ? "ACCOUNT_DELETING"
                                : "ERASING")
                        : "OPEN";
        if (!required.equals(text(account, "data_write_fence"))
                || (!"ACTIVE".equals(text(account, "status"))
                        && !(full && "DELETING".equals(text(account, "status")))))
            throw BusinessException.conflict("DATA_EPOCH_CHANGED", "请求授权或写入栅栏已变化");
    }

    private Map<String, Object> lockedRequest(Map<String, Object> request) {
        Map<String, Object> row =
                sql.one(
                        "SELECT * FROM privacy_request WHERE id=? AND user_id=? FOR UPDATE",
                        number(request, "id"),
                        number(request, "user_id"));
        if (!"PROCESSING".equals(text(row, "status")))
            throw BusinessException.conflict("PRIVACY_REQUEST_CANCELLED", "工单已取消或结束");
        Map<String, Object> progress = progress(row);
        if (!workerId.equals(progress.get("leaseOwner")))
            throw BusinessException.conflict("PRIVACY_LEASE_CHANGED", "工单处理租约已变化");
        return row;
    }

    private void lockOwner(long user) {
        sql.one("SELECT user_id FROM user_sync_clock WHERE user_id=? FOR UPDATE", user);
        sql.one("SELECT id FROM app_user WHERE id=? FOR UPDATE", user);
    }

    private void failed(Map<String, Object> request, String code, boolean terminal) {
        transactions.executeWithoutResult(
                transaction -> {
                    long user = number(request, "user_id");
                    lockOwner(user);
                    Map<String, Object> row =
                            sql.one(
                                    "SELECT * FROM privacy_request WHERE id=? AND user_id=? FOR"
                                            + " UPDATE",
                                    number(request, "id"),
                                    user);
                    if (FINAL.contains(text(row, "status"))) return;
                    Map<String, Object> p = progress(row);
                    if ("PROCESSING".equals(text(row, "status"))
                            && !workerId.equals(p.get("leaseOwner"))) return;
                    int attempt = ((Number) p.getOrDefault("attempt", 1)).intValue();
                    // A completed destructive DB checkpoint remains retryable until files/ledger
                    // succeed.
                    boolean destructive = Boolean.TRUE.equals(p.get("dbErased"));
                    boolean finalFailure = terminal && !destructive && !isFull(request);
                    p.put("phase", destructive ? "FILES_OR_LEDGER_RETRY" : "VERIFY_RETRY");
                    p.remove("leaseOwner");
                    p.remove("leaseUntil");
                    p.put(
                            "nextAttemptAt",
                            clock.instant()
                                    .plusSeconds(Math.min(3600, 5L << Math.min(attempt, 9)))
                                    .toString());
                    sql.execute(
                            "UPDATE privacy_request SET"
                                + " status=?,error_code=?,progress_json=?,updated_at=?,row_version=row_version+1"
                                + " WHERE id=? AND user_id=?",
                            finalFailure ? "FAILED" : "PARTIAL_FAILED",
                            code,
                            p,
                            clock.instant(),
                            number(request, "id"),
                            user);
                });
    }

    private Map<String, Object> exportRow(String table, long id, long user) {
        Map<String, Object> source =
                sql.one(
                        "SELECT * FROM "
                                + sql.metadata().table(table)
                                + " WHERE user_id=? AND "
                                + sql.metadata().column(table, primary(table))
                                + "=?",
                        user,
                        id);
        Map<String, Object> result = new LinkedHashMap<>();
        Set<String> permittedCipher =
                Set.of(
                        "notes_cipher",
                        "name_cipher",
                        "description_cipher",
                        "motivation_cipher",
                        "instruction_cipher",
                        "reason_cipher",
                        "title_cipher",
                        "content_cipher",
                        "summary_cipher",
                        "original_name_cipher",
                        "bio_cipher",
                        "interruption_note_cipher",
                        "location_label_cipher",
                        "caption_cipher");
        source.forEach(
                (name, value) -> {
                    if (name.endsWith("_cipher")) {
                        if (permittedCipher.contains(name))
                            result.put(
                                    name.substring(0, name.length() - 7),
                                    value == null ? null : crypto.decrypt(value));
                    } else if (name.contains("hash")
                            || name.contains("token")
                            || name.contains("key_ref")
                            || Set.of(
                                            "object_key",
                                            "storage_provider",
                                            "bucket_code",
                                            "input_snapshot_cipher",
                                            "provider_request_id",
                                            "content_digest",
                                            "payload_digest",
                                            "notice_digest")
                                    .contains(name)) {
                        // Never export credentials, internal storage addresses, fingerprints or raw
                        // model input snapshots.
                    } else if (name.endsWith("_json"))
                        result.put(
                                name.substring(0, name.length() - 5), safeJson(json(source, name)));
                    else if (!(value instanceof byte[]))
                        result.put(
                                name,
                                value instanceof Timestamp timestamp
                                        ? timestamp
                                                .toLocalDateTime()
                                                .toInstant(ZoneOffset.UTC)
                                                .toString()
                                        : value instanceof LocalDateTime time
                                                ? time.toInstant(ZoneOffset.UTC).toString()
                                                : value);
                });
        if ("file_object".equals(table) && "CLEAN".equals(text(source, "status")))
            result.put(
                    "contentBase64", Base64.getEncoder().encodeToString(files.readContent(source)));
        return result;
    }

    private JsonNode safeJson(JsonNode value) {
        if (value == null || value.isNull()) return value;
        if (value.isObject() && value.has("ciphertext") && value.has("alg")) {
            try {
                return safeJson(
                        mapper.readTree(
                                crypto.decrypt(
                                        Base64.getDecoder()
                                                .decode(value.path("ciphertext").asText()))));
            } catch (IOException exception) {
                throw BusinessException.unavailable("EXPORT_CONTENT_INVALID", "加密内容无法导出");
            }
        }
        if (value.isObject()) {
            var result = mapper.createObjectNode();
            value.fields()
                    .forEachRemaining(
                            field -> {
                                String key = field.getKey().toLowerCase(Locale.ROOT);
                                if (!key.contains("token")
                                        && !key.contains("secret")
                                        && !key.contains("password")
                                        && !key.contains("keyref")
                                        && !key.contains("ciphertext")
                                        && !key.contains("objectkey"))
                                    result.set(field.getKey(), safeJson(field.getValue()));
                            });
            return result;
        }
        if (value.isArray()) {
            var result = mapper.createArrayNode();
            value.forEach(item -> result.add(safeJson(item)));
            return result;
        }
        return value;
    }

    private byte[] encodeExport(
            Map<String, Object> request,
            Map<String, List<Map<String, Object>>> rows,
            String format) {
        if (!"CSV_ZIP".equals(format))
            return privacy.json(
                    map(
                            "schemaVersion",
                            1,
                            "generatedAt",
                            clock.instant().toString(),
                            "datasetEpoch",
                            Long.toString(number(request, "captured_dataset_epoch")),
                            "tables",
                            rows));
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (var table : rows.entrySet()) {
                zip.putNextEntry(new ZipEntry(table.getKey() + ".csv"));
                Set<String> keys = new LinkedHashSet<>();
                table.getValue().forEach(row -> keys.addAll(row.keySet()));
                zip.write(
                        (String.join(",", keys.stream().map(this::csv).toList()) + "\r\n")
                                .getBytes(StandardCharsets.UTF_8));
                for (Map<String, Object> row : table.getValue()) {
                    List<String> line = new ArrayList<>();
                    for (String key : keys)
                        line.add(
                                csv(
                                        row.get(key) == null
                                                ? ""
                                                : row.get(key) instanceof JsonNode node
                                                        ? node.toString()
                                                        : row.get(key).toString()));
                    zip.write((String.join(",", line) + "\r\n").getBytes(StandardCharsets.UTF_8));
                }
                zip.closeEntry();
            }
            zip.finish();
            return output.toByteArray();
        } catch (IOException exception) {
            throw BusinessException.unavailable("EXPORT_ENCODING_FAILED", "导出文件暂不可生成");
        }
    }

    private String csv(String input) {
        String leading = input.stripLeading();
        boolean formula = !leading.isEmpty() && "=+-@".indexOf(leading.charAt(0)) >= 0;
        String safe = formula ? "'" + input : input;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private void appendLedger(Map<String, Object> entry) throws IOException {
        Files.createDirectories(ledger.getParent());
        byte[] bytes = (mapper.writeValueAsString(entry) + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (LEDGER_LOCK) {
            try (FileChannel channel =
                            FileChannel.open(
                                    ledger,
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.WRITE,
                                    StandardOpenOption.APPEND);
                    var lock = channel.lock()) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
        }
    }

    private void destroyFile(long user, long id) {
        try {
            Map<String, Object> row =
                    sql.one("SELECT * FROM file_object WHERE user_id=? AND id=?", user, id);
            Files.deleteIfExists(files.objectPath(text(row, "object_key")));
            sql.execute(
                    "UPDATE file_object SET"
                        + " status='DELETED',deleted_at=?,original_name_cipher=NULL,updated_at=?,row_version=row_version+1"
                        + " WHERE user_id=? AND id=?",
                    clock.instant(),
                    clock.instant(),
                    user,
                    id);
        } catch (Exception ignored) {
            /* Next data-control cleanup sees the private orphan; never publish it. */
        }
    }

    private void deleteManifestRows(JsonNode manifest, long user, List<String> tables) {
        for (String table : tables)
            forChunks(ids(manifest, table), chunk -> deleteIn(table, primary(table), user, chunk));
    }

    private void deleteOwner(String table, long user) {
        sql.execute("DELETE FROM " + sql.metadata().table(table) + " WHERE user_id=?", user);
    }

    private void deleteIn(String table, String column, long user, List<Long> values) {
        sql.execute(
                "DELETE FROM "
                        + sql.metadata().table(table)
                        + " WHERE user_id=? AND "
                        + sql.metadata().column(table, column)
                        + " IN ("
                        + marks(values.size())
                        + ")",
                args(user, values));
    }

    private void updateIn(
            String table, String column, long user, List<Long> values, String assignments) {
        sql.execute(
                "UPDATE "
                        + sql.metadata().table(table)
                        + " SET "
                        + assignments
                        + " WHERE user_id=? AND "
                        + sql.metadata().column(table, column)
                        + " IN ("
                        + marks(values.size())
                        + ")",
                args(user, values));
    }

    private List<Long> ownerIds(String table, long user) {
        return sql
                .query("SELECT id FROM " + sql.metadata().table(table) + " WHERE user_id=?", user)
                .stream()
                .map(row -> number(row, "id"))
                .toList();
    }

    private String primary(String table) {
        return sql.metadata().hasColumn(table, "id") ? "id" : "record_id";
    }

    private static List<Long> ids(JsonNode manifest, String table) {
        List<Long> result = new ArrayList<>();
        if (manifest != null)
            for (JsonNode row : manifest.path("tables").path(table))
                result.add(row.path("id").asLong());
        return result;
    }

    private static boolean isFull(Map<String, Object> row) {
        return Set.of("ERASE_HEALTH_DATA", "DELETE_ACCOUNT").contains(text(row, "request_type"));
    }

    private Map<String, Object> progress(Map<String, Object> row) {
        JsonNode value = json(row, "progress_json");
        return value == null
                ? new LinkedHashMap<>()
                : mapper.convertValue(value, new TypeReference<>() {});
    }

    private static Instant parseInstant(Object value) {
        return value == null ? null : Instant.parse(value.toString());
    }

    private static String marks(int size) {
        return String.join(",", Collections.nCopies(size, "?"));
    }

    private static Object[] args(long user, List<Long> values) {
        List<Object> args = new ArrayList<>();
        args.add(user);
        args.addAll(values);
        return args.toArray();
    }

    private static Object[] prepend(Object value, Object[] arguments) {
        Object[] result = new Object[arguments.length + 1];
        result[0] = value;
        System.arraycopy(arguments, 0, result, 1, arguments.length);
        return result;
    }

    private static void forChunks(List<Long> values, Consumer<List<Long>> consumer) {
        for (int start = 0; start < values.size(); start += 500)
            consumer.accept(values.subList(start, Math.min(values.size(), start + 500)));
    }

    private static BusinessException stale() {
        return BusinessException.conflict("PREVIEW_STALE", "冻结范围中的目标已改变或删除，请重新预览");
    }

    private static Set<String> allowedTables() {
        Set<String> tables = new HashSet<>();
        tables.addAll(PrivacyService.FACT_TABLES);
        tables.addAll(PrivacyService.PLAN_TABLES);
        tables.addAll(PrivacyService.AI_TABLES);
        tables.addAll(PrivacyService.REVIEW_TABLES);
        tables.addAll(
                List.of(
                        "user_goal",
                        "availability_rule",
                        "availability_exception",
                        "user_preference",
                        "external_data_connection",
                        "import_batch",
                        "notification_inbox",
                        "feedback_ticket",
                        "user_profile",
                        "file_object"));
        return tables;
    }
}
