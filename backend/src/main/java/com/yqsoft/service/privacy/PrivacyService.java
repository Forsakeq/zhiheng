package com.yqsoft.service.privacy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yqsoft.dao.SqlStore;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.persistence.MutationExecutor;
import com.yqsoft.resource.common.Ids;
import com.yqsoft.resource.common.MutationResult;
import com.yqsoft.resource.common.PageResult;
import com.yqsoft.resource.common.Rows;
import com.yqsoft.resource.file.FileDtos;
import com.yqsoft.resource.privacy.PrivacyDtos;
import com.yqsoft.security.AuthContext;
import com.yqsoft.service.auth.AuthService;
import com.yqsoft.service.common.CryptoService;
import com.yqsoft.service.common.CursorService;
import com.yqsoft.service.file.FileService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PrivacyService {

    static final List<String> AI_TABLES =
            List.of(
                    "ai_conversation",
                    "ai_message",
                    "ai_run",
                    "ai_draft",
                    "ai_draft_revision",
                    "ai_message_citation");
    static final List<String> PLAN_TABLES =
            List.of(
                    "plan",
                    "plan_version",
                    "task",
                    "plan_version_task",
                    "plan_rule",
                    "task_event",
                    "task_record_link",
                    "reminder_rule",
                    "reminder_delivery",
                    "device_task_receipt");
    static final List<String> FACT_TABLES =
            List.of(
                    "health_record",
                    "sleep_record_detail",
                    "activity_record_detail",
                    "meal_record_detail",
                    "energy_record_detail",
                    "record_tag",
                    "record_tag_relation",
                    "record_attachment");
    static final List<String> REVIEW_TABLES =
            List.of("daily_stat", "weekly_review", "weekly_review_evidence");

    private final SqlStore sql;
    private final MutationExecutor mutations;
    private final CryptoService crypto;
    private final ObjectMapper mapper;
    private final FileService files;
    private final ObjectProvider<AuthService> auth;
    private final CursorService cursors;
    private final Clock clock;
    private final ThreadLocal<String> issuedReceipt = new ThreadLocal<>();

    public PrivacyService(
            SqlStore sql,
            MutationExecutor mutations,
            CryptoService crypto,
            ObjectMapper mapper,
            FileService files,
            ObjectProvider<AuthService> auth,
            CursorService cursors,
            Clock clock) {
        this.sql = sql;
        this.mutations = mutations;
        this.crypto = crypto;
        this.mapper = mapper;
        this.files = files;
        this.auth = auth;
        this.cursors = cursors;
        this.clock = clock;
    }

    public Map<String, Object> preview(PrivacyDtos.Preview request) {
        validateScope(request);
        Map<String, Object> filter = mapper.convertValue(request.scope(), new TypeReference<>() {});
        filter.put("format", request.format() == null ? "JSON" : request.format());
        return createPreview(request.type(), filter);
    }

    public long previewForConsentWithdrawal(String purpose) {
        if (!Set.of("AI_PROCESSING", "NOTIFICATION", "EXTERNAL_HEALTH").contains(purpose)) {
            throw BusinessException.badRequest("INVALID_PURPOSE", "用途无效");
        }
        return Ids.parse(
                createPreview(
                                "DELETE_RECORDS",
                                Rows.map(
                                        "consentPurpose",
                                        purpose,
                                        "includeAiContent",
                                        "AI_PROCESSING".equals(purpose),
                                        "includeFiles",
                                        true,
                                        "includePlans",
                                        false,
                                        "format",
                                        "JSON"))
                        .get("requestId")
                        .toString());
    }

    private Map<String, Object> createPreview(String type, Map<String, Object> filter) {
        issuedReceipt.remove();
        try {
            var result =
                    mutations.mutate(
                            "PRIVACY_PREVIEW",
                            Rows.map("type", type, "filter", filter),
                            true,
                            () -> {
                                Map<String, Object> manifest =
                                        manifest(type, filter, AuthContext.userId());
                                byte[] content = json(manifest);
                                byte[] digest = crypto.sha256(content);
                                long file =
                                        files.createServerFile(
                                                AuthContext.userId(),
                                                "PRIVACY_MANIFEST",
                                                "manifest.json",
                                                "application/json",
                                                content,
                                                clock.instant().plus(Duration.ofDays(45)));
                                String receipt =
                                        "DELETE_ACCOUNT".equals(type) ? crypto.randomToken() : null;
                                long id =
                                        sql.insert(
                                                "privacy_request",
                                                Rows.map(
                                                        "user_id",
                                                        AuthContext.userId(),
                                                        "request_key",
                                                        UUID.randomUUID().toString(),
                                                        "request_type",
                                                        type,
                                                        "status",
                                                        "PREVIEW_READY",
                                                        "scope_filter_json",
                                                        filter,
                                                        "scope_manifest_file_id",
                                                        file,
                                                        "scope_digest",
                                                        digest,
                                                        "scope_summary_json",
                                                        summary(manifest),
                                                        "captured_dataset_epoch",
                                                        mutations.currentEpoch(),
                                                        "preview_data_seq",
                                                        mutations.currentSeq(),
                                                        "preview_expires_at",
                                                        clock.instant().plusSeconds(900),
                                                        "progress_json",
                                                        Rows.map(
                                                                "phase",
                                                                "PREVIEW",
                                                                "completedModules",
                                                                List.of()),
                                                        "receipt_token_hash",
                                                        receipt == null
                                                                ? null
                                                                : crypto.hashToken(receipt),
                                                        "receipt_expires_at",
                                                        receipt == null
                                                                ? null
                                                                : clock.instant()
                                                                        .plus(
                                                                                Duration.ofDays(
                                                                                        45))));
                                issuedReceipt.set(receipt);
                                return MutationResult.of("PRIVACY_REQUEST", id, 1);
                            });
            var view =
                    view(
                            sql.owned(
                                    "privacy_request",
                                    Ids.parse(result.id()),
                                    AuthContext.userId()));
            view.put(
                    "manifestDigest",
                    HexFormat.of()
                            .formatHex(
                                    (byte[])
                                            sql.owned(
                                                            "privacy_request",
                                                            Ids.parse(result.id()),
                                                            AuthContext.userId())
                                                    .get("scope_digest")));
            view.put("receiptToken", result.replayed() ? null : issuedReceipt.get());
            view.put("tokenUnavailable", result.replayed() && "DELETE_ACCOUNT".equals(type));
            view.put("replayed", result.replayed());
            return view;
        } finally {
            issuedReceipt.remove();
        }
    }

    public Map<String, Object> confirm(long id, PrivacyDtos.Confirm request) {
        auth.getObject()
                .reauthenticate(
                        AuthContext.userId(), AuthContext.current().audience(), request.reauth());
        var result =
                mutations.mutate(
                        "PRIVACY_CONFIRM",
                        Rows.map(
                                "requestId",
                                id,
                                "expectedRowVersion",
                                request.expectedRowVersion(),
                                "manifestDigest",
                                request.manifestDigest(),
                                "expectedDataSeq",
                                request.expectedDataSeq(),
                                "userConfirmed",
                                request.userConfirmed()),
                        true,
                        () -> {
                            var row = sql.lockOwned("privacy_request", id, AuthContext.userId());
                            String type = Rows.text(row, "request_type");
                            if (!"PREVIEW_READY".equals(Rows.text(row, "status"))
                                    || Rows.instant(row, "preview_expires_at")
                                            .isBefore(clock.instant())) {
                                throw BusinessException.conflict(
                                        "PREVIEW_EXPIRED", "预览已失效，请重新确认范围");
                            }
                            if (!request.userConfirmed()
                                    || Rows.number(row, "captured_dataset_epoch")
                                            != mutations.currentEpoch()
                                    || !HexFormat.of()
                                            .formatHex((byte[]) row.get("scope_digest"))
                                            .equals(request.manifestDigest())) {
                                throw BusinessException.conflict("PREVIEW_STALE", "预览依据已改变");
                            }
                            if (Set.of("ERASE_HEALTH_DATA", "DELETE_ACCOUNT").contains(type)
                                    && (Ids.nonNegative(request.expectedDataSeq())
                                                    != mutations.currentSeq()
                                            || Rows.number(row, "preview_data_seq")
                                                    != mutations.currentSeq())) {
                                throw BusinessException.conflict("PREVIEW_STALE", "预览后新增数据，请重新预览");
                            }
                            Map<String, Object> filter =
                                    mapper.convertValue(
                                            Rows.json(row, "scope_filter_json"),
                                            new TypeReference<>() {});
                            if (!java.security.MessageDigest.isEqual(
                                    (byte[]) row.get("scope_digest"),
                                    crypto.sha256(
                                            json(manifest(type, filter, AuthContext.userId()))))) {
                                throw BusinessException.conflict(
                                        "PREVIEW_STALE", "目标版本或范围已改变，请重新预览");
                            }
                            long version =
                                    sql.update(
                                            "privacy_request",
                                            id,
                                            Ids.parse(request.expectedRowVersion()),
                                            Rows.map(
                                                    "status",
                                                    "QUEUED",
                                                    "confirmed_at",
                                                    clock.instant(),
                                                    "confirmation_operation_id",
                                                    com.yqsoft.resource.common.RequestContext
                                                            .idempotencyKey()));
                            if (Set.of("ERASE_HEALTH_DATA", "DELETE_ACCOUNT").contains(type)) {
                                sql.execute(
                                        "UPDATE app_user SET data_write_fence=?, status=?,"
                                            + " delete_requested_at=?,"
                                            + " security_version=security_version+?,"
                                            + " row_version=row_version+1,updated_at=? WHERE id=?",
                                        "DELETE_ACCOUNT".equals(type)
                                                ? "ACCOUNT_DELETING"
                                                : "ERASING",
                                        "DELETE_ACCOUNT".equals(type) ? "DELETING" : "ACTIVE",
                                        "DELETE_ACCOUNT".equals(type) ? clock.instant() : null,
                                        "DELETE_ACCOUNT".equals(type) ? 1 : 0,
                                        clock.instant(),
                                        AuthContext.userId());
                                sql.execute(
                                        "UPDATE ai_run SET"
                                            + " status='CANCEL_REQUESTED',row_version=row_version+1,updated_at=?"
                                            + " WHERE user_id=? AND status IN ('QUEUED','RUNNING')",
                                        clock.instant(),
                                        AuthContext.userId());
                                sql.execute(
                                        "UPDATE outbox_event SET status='CANCELLED',updated_at=?"
                                                + " WHERE user_id=? AND status IN"
                                                + " ('PENDING','RETRY','CLAIMED')",
                                        clock.instant(),
                                        AuthContext.userId());
                                sql.execute(
                                        "UPDATE privacy_request SET"
                                            + " status='CANCELLED',row_version=row_version+1,updated_at=?"
                                            + " WHERE user_id=? AND request_type='EXPORT' AND"
                                            + " status IN ('QUEUED','PROCESSING') AND id<>?",
                                        clock.instant(),
                                        AuthContext.userId(),
                                        id);
                                sql.execute(
                                        "UPDATE external_data_connection SET"
                                            + " status='REVOKED',revoked_at=?,access_token_cipher=NULL,"
                                            + "refresh_token_cipher=NULL,provider_cursor_cipher=NULL,encryption_key_ref=NULL,authorization_revision=authorization_revision+1"
                                            + " WHERE user_id=?",
                                        clock.instant(),
                                        AuthContext.userId());
                                if ("DELETE_ACCOUNT".equals(type)) {
                                    sql.execute(
                                            "UPDATE auth_session SET"
                                                + " revoked_at=?,revoke_reason='ACCOUNT_DELETED'"
                                                + " WHERE user_id=?",
                                            clock.instant(),
                                            AuthContext.userId());
                                }
                            }
                            return MutationResult.of("PRIVACY_REQUEST", id, version);
                        });
        var row = sql.owned("privacy_request", id, AuthContext.userId());
        return Rows.map(
                "id",
                result.id(),
                "requestId",
                result.id(),
                "rowVersion",
                result.rowVersion(),
                "status",
                Rows.text(row, "status"),
                "receiptToken",
                null,
                "receiptExpiresAt",
                Rows.instant(row, "receipt_expires_at"),
                "sessionRevoked",
                "DELETE_ACCOUNT".equals(Rows.text(row, "request_type")),
                "dataWriteFenced",
                Set.of("ERASE_HEALTH_DATA", "DELETE_ACCOUNT")
                        .contains(Rows.text(row, "request_type")),
                "operationId",
                result.operationId(),
                "changeSeq",
                result.changeSeq(),
                "replayed",
                result.replayed());
    }

    public PageResult<Map<String, Object>> list(String cursor, int limit) {
        if (limit < 1 || limit > 100) {
            throw BusinessException.badRequest("INVALID_PAGE_SIZE", "分页范围为1至100");
        }
        long after =
                Long.parseLong(
                        cursors.decode(cursor, "privacy").getOrDefault("after", "0").toString());
        var rows =
                sql.query(
                        "SELECT * FROM privacy_request WHERE user_id=? AND id>? AND deleted_at IS"
                                + " NULL ORDER BY id LIMIT ?",
                        AuthContext.userId(),
                        after,
                        limit + 1);
        boolean more = rows.size() > limit;
        var page = rows.subList(0, Math.min(limit, rows.size()));
        return PageResult.of(
                page.stream().map(this::view).toList(),
                more
                        ? cursors.encode(
                                "privacy",
                                Rows.map("after", Rows.number(page.get(page.size() - 1), "id")))
                        : null,
                more);
    }

    public Map<String, Object> get(long id) {
        return view(sql.owned("privacy_request", id, AuthContext.userId()));
    }

    public MutationResult cancel(long id, long version) {
        return mutations.mutate(
                "PRIVACY_CANCEL",
                Rows.map("id", id, "version", version),
                true,
                () -> {
                    var row = sql.lockOwned("privacy_request", id, AuthContext.userId());
                    if (!"PREVIEW_READY".equals(Rows.text(row, "status"))) {
                        throw BusinessException.conflict("CANCEL_NOT_ALLOWED", "已开始的删除或导出不能由此撤销");
                    }
                    return MutationResult.of(
                            "PRIVACY_REQUEST",
                            id,
                            sql.update(
                                    "privacy_request",
                                    id,
                                    version,
                                    Rows.map("status", "CANCELLED")));
                });
    }

    public Map<String, Object> receipt(String token) {
        var row =
                sql.find(
                                "SELECT"
                                    + " id,request_type,status,progress_json,completed_at,error_code,receipt_expires_at"
                                    + " FROM privacy_request WHERE receipt_token_hash=?",
                                crypto.hashToken(token))
                        .orElseThrow(BusinessException::notFound);
        if (Rows.instant(row, "receipt_expires_at").isBefore(clock.instant())) {
            throw new BusinessException(410, "RECEIPT_EXPIRED", "查询回执已过期");
        }
        return Rows.map(
                "requestId",
                Long.toString(Rows.number(row, "id")),
                "type",
                Rows.text(row, "request_type"),
                "status",
                Rows.text(row, "status"),
                "progress",
                Rows.json(row, "progress_json"),
                "completedAt",
                Rows.instant(row, "completed_at"),
                "errorCode",
                Rows.text(row, "error_code"));
    }

    public FileDtos.DownloadView download(long id, PrivacyDtos.Download request) {
        auth.getObject()
                .reauthenticate(
                        AuthContext.userId(), AuthContext.current().audience(), request.reauth());
        var row = sql.owned("privacy_request", id, AuthContext.userId());
        if (!"COMPLETED".equals(Rows.text(row, "status"))
                || !"EXPORT".equals(Rows.text(row, "request_type"))) {
            throw BusinessException.conflict("EXPORT_NOT_READY", "导出尚未完成");
        }
        return files.exportDownload(Rows.number(row, "result_file_id"));
    }

    Map<String, Object> manifest(String type, Map<String, Object> filter, long user) {
        Map<String, Object> tables = new LinkedHashMap<>();
        boolean full = Set.of("ERASE_HEALTH_DATA", "DELETE_ACCOUNT").contains(type);
        boolean exporting = "EXPORT".equals(type);
        boolean includeAi =
                full
                        || (exporting
                                ? Boolean.TRUE.equals(filter.get("includeAiContent"))
                                : !Boolean.FALSE.equals(filter.get("includeAiContent")));
        boolean includePlans =
                full
                        || (exporting
                                ? Boolean.TRUE.equals(filter.get("includePlans"))
                                : !Boolean.FALSE.equals(filter.get("includePlans")));
        boolean includeFiles =
                exporting
                        ? Boolean.TRUE.equals(filter.get("includeFiles"))
                        : !Boolean.FALSE.equals(filter.get("includeFiles"));
        boolean selectedFacts =
                filter.get("from") != null
                        || filter.get("sourceConnectionId") != null
                        || filter.get("recordIds") instanceof List<?> selectedIds
                                && !selectedIds.isEmpty()
                        || filter.get("recordTypes") instanceof List<?> selectedTypes
                                && !selectedTypes.isEmpty();
        String purpose = (String) filter.get("consentPurpose");
        if (full || exporting) {
            addTables(tables, FACT_TABLES, user);
            if (full) {
                addTables(tables, REVIEW_TABLES, user);
            } else if (!selectedFacts) {
                addTables(tables, List.of("daily_stat"), user);
                if (includeAi) {
                    addTables(tables, List.of("weekly_review", "weekly_review_evidence"), user);
                }
            }
            if (full || !selectedFacts) {
                addTables(
                        tables,
                        List.of(
                                "user_goal",
                                "availability_rule",
                                "availability_exception",
                                "user_preference"),
                        user);
            }
            if (includePlans) {
                addTables(tables, PLAN_TABLES, user);
            }
            if (includeAi) {
                addTables(tables, AI_TABLES, user);
            }
            if (full) {
                addTables(
                        tables,
                        List.of("external_data_connection", "import_batch", "notification_inbox"),
                        user);
            }
            if ("DELETE_ACCOUNT".equals(type)) {
                addTables(tables, List.of("feedback_ticket", "user_profile"), user);
            }
            // Export scopes only select the requested records; related derived content remains
            // explicit.
            if ("EXPORT".equals(type)) {
                addSelectedFacts(tables, selectedRecords(filter, user), user);
            }
        } else if (purpose != null) {
            if ("AI_PROCESSING".equals(purpose)) {
                addTables(tables, AI_TABLES, user);
                addTables(tables, REVIEW_TABLES, user);
            } else if ("NOTIFICATION".equals(purpose)) {
                addTables(
                        tables,
                        List.of("reminder_delivery", "reminder_rule", "notification_inbox"),
                        user);
            } else {
                tables.put(
                        "health_record",
                        sql.query(
                                "SELECT id,row_version FROM health_record WHERE user_id=? AND"
                                        + " source_type='EXTERNAL' ORDER BY id",
                                user));
            }
        } else {
            tables.put("health_record", selectedRecords(filter, user));
            addTables(tables, REVIEW_TABLES, user);
            if (!Boolean.FALSE.equals(filter.get("includeAiContent"))) {
                addTables(tables, AI_TABLES, user);
            }
        }
        if (full) {
            String condition =
                    "DELETE_ACCOUNT".equals(type)
                            ? "purpose<>'PRIVACY_MANIFEST'"
                            : "purpose NOT IN ('PRIVACY_MANIFEST','AVATAR','FEEDBACK_ATTACHMENT')";
            tables.put(
                    "file_object",
                    sql.query(
                            "SELECT id,row_version FROM file_object WHERE user_id=? AND deleted_at"
                                    + " IS NULL AND "
                                    + condition
                                    + " ORDER BY id",
                            user));
        } else if (includeFiles) {
            List<Long> selected = ids(tables.get("health_record"));
            List<Object> arguments = new ArrayList<>();
            arguments.add(user);
            String related = "1=0";
            if (!selected.isEmpty()) {
                related =
                        "id IN (SELECT file_id FROM record_attachment WHERE user_id=? AND record_id"
                                + " IN ("
                                + String.join(
                                        ",", java.util.Collections.nCopies(selected.size(), "?"))
                                + "))";
                arguments.add(user);
                arguments.addAll(selected);
            }
            if (includeAi) {
                related = "(" + related + " OR purpose IN ('VOICE_TEMP','COURSE_IMAGE'))";
            }
            tables.put(
                    "file_object",
                    sql.query(
                            "SELECT id,row_version FROM file_object WHERE user_id=? "
                                    + "AND deleted_at IS NULL AND purpose<>'PRIVACY_MANIFEST' AND "
                                    + related
                                    + " ORDER BY id",
                            arguments.toArray()));
        }
        return Rows.map("schemaVersion", 1, "type", type, "tables", tables);
    }

    private void addSelectedFacts(
            Map<String, Object> tables, List<Map<String, Object>> selected, long user) {
        tables.put("health_record", selected);
        List<Long> ids = selected.stream().map(row -> Rows.number(row, "id")).toList();
        if (ids.size() > 10000) {
            throw BusinessException.badRequest("EXPORT_RANGE_TOO_LARGE", "请缩小记录导出日期范围");
        }
        for (String table : FACT_TABLES) {
            if (table.equals("health_record") || table.equals("record_tag")) {
                continue;
            }
            if (ids.isEmpty()) {
                tables.put(table, List.of());
                continue;
            }
            List<Object> args = new ArrayList<>();
            args.add(user);
            args.addAll(ids);
            String primary = sql.metadata().hasColumn(table, "id") ? "id" : "record_id";
            tables.put(
                    table,
                    sql.query(
                            "SELECT "
                                    + primary
                                    + " AS id,0 AS row_version FROM "
                                    + sql.metadata().table(table)
                                    + " WHERE user_id=? AND record_id IN ("
                                    + String.join(
                                            ",", java.util.Collections.nCopies(ids.size(), "?"))
                                    + ") ORDER BY "
                                    + primary,
                            args.toArray()));
        }
        if (ids.isEmpty()) {
            tables.put("record_tag", List.of());
        } else {
            List<Object> args = new ArrayList<>();
            args.add(user);
            args.add(user);
            args.addAll(ids);
            tables.put(
                    "record_tag",
                    sql.query(
                            "SELECT id,row_version FROM record_tag WHERE user_id=? AND id IN"
                                    + " (SELECT tag_id FROM record_tag_relation WHERE user_id=? AND"
                                    + " record_id IN ("
                                    + String.join(
                                            ",", java.util.Collections.nCopies(ids.size(), "?"))
                                    + ")) ORDER BY id",
                            args.toArray()));
        }
    }

    private List<Long> ids(Object rows) {
        if (!(rows instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(row -> Long.parseLong(((Map<?, ?>) row).get("id").toString()))
                .toList();
    }

    private void addTables(Map<String, Object> target, List<String> tables, long user) {
        for (String table : tables) {
            boolean id = sql.metadata().hasColumn(table, "id");
            String primary = id ? "id" : "record_id";
            String version = sql.metadata().hasColumn(table, "row_version") ? "row_version" : "0";
            target.put(
                    table,
                    sql.query(
                            "SELECT "
                                    + primary
                                    + " AS id,"
                                    + version
                                    + " AS row_version FROM "
                                    + sql.metadata().table(table)
                                    + " WHERE user_id=? ORDER BY "
                                    + primary,
                            user));
        }
    }

    private List<Map<String, Object>> selectedRecords(Map<String, Object> filter, long user) {
        StringBuilder query =
                new StringBuilder("SELECT id,row_version FROM health_record WHERE user_id=?");
        List<Object> args = new ArrayList<>();
        args.add(user);
        if (filter.get("recordIds") instanceof List<?> ids && !ids.isEmpty()) {
            query.append(" AND id IN (")
                    .append(String.join(",", java.util.Collections.nCopies(ids.size(), "?")))
                    .append(')');
            ids.forEach(id -> args.add(Ids.parse(id.toString())));
        }
        if (filter.get("from") != null) {
            query.append(" AND record_local_date>=? AND record_local_date<=?");
            args.add(filter.get("from"));
            args.add(filter.get("to"));
        }
        if (filter.get("sourceConnectionId") != null) {
            query.append(" AND source_connection_id=?");
            args.add(Ids.parse(filter.get("sourceConnectionId").toString()));
        }
        if (filter.get("recordTypes") instanceof List<?> types && !types.isEmpty()) {
            if (!Set.of("SLEEP", "ACTIVITY", "MEAL", "ENERGY").containsAll(types)) {
                throw BusinessException.badRequest("INVALID_RECORD_TYPE", "记录类型无效");
            }
            query.append(" AND record_type IN (")
                    .append(String.join(",", java.util.Collections.nCopies(types.size(), "?")))
                    .append(')');
            args.addAll(types);
        }
        query.append(" ORDER BY id");
        return sql.query(query.toString(), args.toArray());
    }

    Map<String, Object> view(Map<String, Object> row) {
        return Rows.map(
                "id",
                Long.toString(Rows.number(row, "id")),
                "requestId",
                Long.toString(Rows.number(row, "id")),
                "rowVersion",
                Long.toString(Rows.number(row, "row_version")),
                "type",
                Rows.text(row, "request_type"),
                "status",
                Rows.text(row, "status"),
                "scopeSummary",
                Rows.json(row, "scope_summary_json"),
                "previewDataSeq",
                Long.toString(Rows.number(row, "preview_data_seq")),
                "datasetEpoch",
                Long.toString(Rows.number(row, "captured_dataset_epoch")),
                "previewExpiresAt",
                Rows.instant(row, "preview_expires_at"),
                "progress",
                Rows.json(row, "progress_json"),
                "completedAt",
                Rows.instant(row, "completed_at"),
                "errorCode",
                Rows.text(row, "error_code"),
                "receiptExpiresAt",
                Rows.instant(row, "receipt_expires_at"));
    }

    private Map<String, Object> summary(Map<String, Object> manifest) {
        Map<String, Object> counts = new LinkedHashMap<>();
        ((Map<?, ?>) manifest.get("tables"))
                .forEach((table, rows) -> counts.put(table.toString(), ((List<?>) rows).size()));
        return Rows.map(
                "moduleCounts",
                counts,
                "type",
                manifest.get("type"),
                "preservesPlanExecutionHistory",
                "DELETE_RECORDS".equals(manifest.get("type")));
    }

    byte[] json(Object object) {
        try {
            return mapper.writeValueAsBytes(object);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot encode privacy manifest");
        }
    }

    private void validateScope(PrivacyDtos.Preview request) {
        var scope = request.scope();
        if ((scope.from() == null) != (scope.to() == null)
                || scope.from() != null && scope.to().isBefore(scope.from())) {
            throw BusinessException.badRequest("INVALID_DATE_RANGE", "日期范围无效");
        }
        if ("DELETE_RECORDS".equals(request.type())
                && (scope.recordIds() == null || scope.recordIds().isEmpty())
                && scope.from() == null
                && scope.sourceConnectionId() == null) {
            throw BusinessException.badRequest("DELETE_SCOPE_REQUIRED", "部分删除必须明确记录或筛选范围");
        }
    }
}
