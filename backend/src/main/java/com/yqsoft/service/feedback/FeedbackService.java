package com.yqsoft.service.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yqsoft.dao.SqlStore;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.persistence.MutationExecutor;
import com.yqsoft.resource.common.*;
import com.yqsoft.resource.feedback.FeedbackDtos;
import com.yqsoft.security.AuthContext;
import com.yqsoft.service.common.CryptoService;
import com.yqsoft.service.common.CursorService;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
public class FeedbackService {
    private final SqlStore sql;
    private final MutationExecutor mutations;
    private final CryptoService crypto;
    private final CursorService cursors;
    private final ObjectMapper mapper;

    public FeedbackService(
            SqlStore sql,
            MutationExecutor mutations,
            CryptoService crypto,
            CursorService cursors,
            ObjectMapper mapper) {
        this.sql = sql;
        this.mutations = mutations;
        this.crypto = crypto;
        this.cursors = cursors;
        this.mapper = mapper;
    }

    public MutationResult create(FeedbackDtos.Create request) {
        return mutations.mutate(
                "FEEDBACK_CREATE",
                request,
                true,
                () -> {
                    List<String> files = request.fileIds() == null ? List.of() : request.fileIds();
                    if (files.stream().distinct().count() != files.size()) throw invalid("附件不能重复");
                    for (String fileId : files) {
                        Map<String, Object> file =
                                sql.owned("file_object", Ids.parse(fileId), AuthContext.userId());
                        if (!"CLEAN".equals(file.get("status"))
                                || !"FEEDBACK_ATTACHMENT".equals(file.get("purpose")))
                            throw invalid("附件未完成核验或用途不匹配");
                    }
                    if (request.installationId() != null)
                        sql.owned(
                                "client_installation",
                                Ids.parse(request.installationId()),
                                AuthContext.userId());
                    if (!Boolean.TRUE.equals(request.includeDiagnostics())
                            && request.diagnostics() != null) throw invalid("未授权附诊断信息时不能提交诊断字段");
                    Map<String, Object> diagnostic = null;
                    if (Boolean.TRUE.equals(request.includeDiagnostics())) {
                        diagnostic =
                                request.diagnostics() == null
                                        ? Rows.map()
                                        : mapper.convertValue(
                                                request.diagnostics(),
                                                new com.fasterxml.jackson.core.type
                                                        .TypeReference<>() {});
                        diagnostic.put("accepted", true);
                    }
                    long id =
                            sql.insert(
                                    "feedback_ticket",
                                    Rows.map(
                                            "user_id",
                                            AuthContext.userId(),
                                            "ticket_no",
                                            UUID.randomUUID()
                                                    .toString()
                                                    .replace("-", "")
                                                    .substring(0, 26),
                                            "category",
                                            request.category(),
                                            "subject",
                                            request.title(),
                                            "description_cipher",
                                            crypto.encrypt(request.description()),
                                            "encryption_key_ref",
                                            crypto.keyRef(),
                                            "status",
                                            "OPEN",
                                            "installation_id",
                                            Ids.optional(request.installationId()),
                                            "attachment_file_ids_json",
                                            files,
                                            "contact_cipher",
                                            crypto.encrypt(request.contact()),
                                            "diagnostics_json",
                                            diagnostic));
                    return MutationResult.of("FEEDBACK", id, 1);
                });
    }

    public PageResult<Map<String, Object>> list(String status, String cursor, int limit) {
        return listInternal(status, null, null, cursor, limit, false);
    }

    public PageResult<Map<String, Object>> adminList(
            String status, String category, String assignedTo, String cursor, int limit) {
        return listInternal(status, category, assignedTo, cursor, limit, true);
    }

    public Map<String, Object> getForAdmin(long id) {
        return view(
                sql.one("SELECT * FROM feedback_ticket WHERE id=? AND deleted_at IS NULL", id),
                true);
    }

    public MutationResult manage(long id, FeedbackDtos.ManagePatch request) {
        Map<String, Object> canonical =
                Rows.map("id", id, "expectedRowVersion", request.expectedRowVersion());
        for (String field : List.of("status", "assignedTo", "reply", "internalNote"))
            if (request.has(field)) {
                canonical.put(
                        field,
                        switch (field) {
                            case "status" -> request.status();
                            case "assignedTo" -> request.assignedTo();
                            case "reply" -> request.reply();
                            default -> request.internalNote();
                        });
            }
        return mutations.mutate(
                "FEEDBACK_MANAGE",
                canonical,
                false,
                () -> {
                    if (request.empty()) throw invalid("至少修改一个字段");
                    Map<String, Object> row =
                            sql.one(
                                    "SELECT * FROM feedback_ticket WHERE id=? AND deleted_at IS"
                                            + " NULL FOR UPDATE",
                                    id);
                    if (Rows.number(row, "row_version") != Ids.parse(request.expectedRowVersion()))
                        throw BusinessException.conflict("ROW_VERSION_CONFLICT", "工单已更新");
                    Map<String, Object> values = Rows.map();
                    if (request.has("status")) {
                        if (request.status() == null) throw invalid("工单状态不能清空");
                        values.put("status", request.status());
                        if ("RESOLVED".equals(request.status()))
                            values.put("resolved_at", Instant.now());
                        if ("CLOSED".equals(request.status()))
                            values.put("closed_at", Instant.now());
                    }
                    if (request.has("assignedTo")) {
                        Long assigned = Ids.optional(request.assignedTo());
                        if (assigned != null) {
                            long permitted =
                                    sql.count(
                                            "SELECT COUNT(*) FROM user_role_assignment a JOIN"
                                                + " sys_role r ON r.id=a.role_id JOIN"
                                                + " role_permission rp ON rp.role_id=r.id JOIN"
                                                + " sys_permission p ON p.id=rp.permission_id JOIN"
                                                + " app_user u ON u.id=a.user_id WHERE a.user_id=?"
                                                + " AND a.status='ACTIVE' AND a.deleted_at IS NULL"
                                                + " AND r.status='ACTIVE' AND r.deleted_at IS NULL"
                                                + " AND p.status='ACTIVE' AND"
                                                + " p.permission_code='feedback.manage' AND"
                                                + " u.status='ACTIVE' AND a.valid_from<=? AND"
                                                + " (a.valid_until IS NULL OR a.valid_until>?)",
                                            assigned,
                                            Instant.now(),
                                            Instant.now());
                            if (permitted == 0) throw invalid("处理人员没有有效工单权限");
                        }
                        values.put("assigned_to_user_id", assigned);
                    }
                    if (request.has("reply"))
                        values.put("resolution_cipher", crypto.encrypt(request.reply()));
                    if (request.has("internalNote"))
                        values.put("internal_note_cipher", crypto.encrypt(request.internalNote()));
                    long version =
                            sql.update(
                                    "feedback_ticket", id, Rows.number(row, "row_version"), values);
                    sql.insert(
                            "audit_log",
                            Rows.map(
                                    "actor_user_id",
                                    AuthContext.userId(),
                                    "subject_user_id",
                                    Rows.number(row, "user_id"),
                                    "actor_type",
                                    "ADMIN",
                                    "action_code",
                                    "FEEDBACK_MANAGE",
                                    "resource_type",
                                    "FEEDBACK",
                                    "resource_id",
                                    id,
                                    "request_id",
                                    RequestContext.requestId(),
                                    "outcome",
                                    "SUCCESS",
                                    "metadata_json",
                                    Rows.map("changedFields", values.keySet()),
                                    "retention_until",
                                    Instant.now().plus(Duration.ofDays(90))));
                    return MutationResult.of("FEEDBACK", id, version);
                });
    }

    public Map<String, Object> view(Map<String, Object> row, boolean admin) {
        Map<String, Object> result =
                Rows.map(
                        "id",
                        Rows.number(row, "id"),
                        "rowVersion",
                        Rows.number(row, "row_version"),
                        "category",
                        row.get("category"),
                        "title",
                        row.get("subject"),
                        "description",
                        crypto.decrypt(row.get("description_cipher")),
                        "fileIds",
                        Rows.json(row, "attachment_file_ids_json"),
                        "status",
                        row.get("status"),
                        "reply",
                        crypto.decrypt(row.get("resolution_cipher")),
                        "repliedAt",
                        row.get("resolution_cipher") == null
                                ? null
                                : Rows.instant(row, "updated_at"),
                        "createdAt",
                        Rows.instant(row, "created_at"));
        if (admin)
            result.putAll(
                    Rows.map(
                            "userId",
                            Rows.number(row, "user_id"),
                            "assignedTo",
                            row.get("assigned_to_user_id"),
                            "contact",
                            crypto.decrypt(row.get("contact_cipher")),
                            "diagnostics",
                            Rows.json(row, "diagnostics_json"),
                            "internalNote",
                            crypto.decrypt(row.get("internal_note_cipher"))));
        return result;
    }

    private PageResult<Map<String, Object>> listInternal(
            String status,
            String category,
            String assignedTo,
            String cursor,
            int limit,
            boolean admin) {
        if (limit < 1 || limit > 100) throw invalid("分页数量无效");
        String scope = "feedback:" + admin + ":" + status + ":" + category + ":" + assignedTo;
        long after = Rows.number(cursors.decode(cursor, scope), "id");
        String where = "deleted_at IS NULL AND id>?";
        List<Object> args = new ArrayList<>(List.of(after));
        if (!admin) {
            where += " AND user_id=?";
            args.add(AuthContext.userId());
        }
        if (status != null) {
            if (!Set.of("OPEN", "IN_PROGRESS", "WAITING_USER", "RESOLVED", "CLOSED")
                    .contains(status)) throw invalid("工单状态无效");
            where += " AND status=?";
            args.add(status);
        }
        if (category != null) {
            if (!Set.of("BUG", "SUGGESTION", "SYNC", "PRIVACY", "AI_QUALITY").contains(category))
                throw invalid("反馈分类无效");
            where += " AND category=?";
            args.add(category);
        }
        if (assignedTo != null) {
            where += " AND assigned_to_user_id=?";
            args.add(Ids.parse(assignedTo));
        }
        args.add(limit + 1);
        List<Map<String, Object>> rows =
                sql.query(
                        "SELECT * FROM feedback_ticket WHERE " + where + " ORDER BY id LIMIT ?",
                        args.toArray());
        boolean more = rows.size() > limit;
        if (more) rows = rows.subList(0, limit);
        return PageResult.of(
                rows.stream().map(r -> view(r, admin)).toList(),
                more
                        ? cursors.encode(scope, Rows.map("id", Rows.number(rows.getLast(), "id")))
                        : null,
                more);
    }

    private BusinessException invalid(String message) {
        return BusinessException.badRequest("VALIDATION_FAILED", message);
    }
}
