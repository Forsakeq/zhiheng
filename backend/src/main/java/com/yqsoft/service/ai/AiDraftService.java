package com.yqsoft.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yqsoft.dao.SqlStore;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.persistence.MutationExecutor;
import com.yqsoft.persistence.WorkerGuard;
import com.yqsoft.resource.ai.AiDtos;
import com.yqsoft.resource.common.*;
import com.yqsoft.resource.plan.PlanDtos;
import com.yqsoft.resource.record.RecordDtos;
import com.yqsoft.security.AuthContext;
import com.yqsoft.service.common.CryptoService;
import com.yqsoft.service.plan.PlanService;
import com.yqsoft.service.record.RecordService;
import com.yqsoft.service.statistics.StatisticsService;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
public class AiDraftService {
    private final SqlStore sql;
    private final MutationExecutor mutations;
    private final WorkerGuard guard;
    private final EncryptedJson encrypted;
    private final CryptoService crypto;
    private final ObjectMapper mapper;
    private final ValidationSupport validation;
    private final PlanService plans;
    private final RecordService records;

    public AiDraftService(
            SqlStore sql,
            MutationExecutor mutations,
            WorkerGuard guard,
            EncryptedJson encrypted,
            CryptoService crypto,
            ObjectMapper mapper,
            ValidationSupport validation,
            PlanService plans,
            RecordService records) {
        this.sql = sql;
        this.mutations = mutations;
        this.guard = guard;
        this.encrypted = encrypted;
        this.crypto = crypto;
        this.mapper = mapper;
        this.validation = validation;
        this.plans = plans;
        this.records = records;
    }

    public Map<String, Object> get(long id) {
        Map<String, Object> draft = sql.owned("ai_draft", id, AuthContext.userId());
        Map<String, Object> revision = currentRevision(draft);
        JsonNode payload = encrypted.open(Rows.json(revision, "payload_json"));
        JsonNode report = Rows.json(revision, "validation_report_json");
        return Rows.map(
                "id",
                id,
                "rowVersion",
                Rows.number(draft, "row_version"),
                "runId",
                Rows.number(draft, "source_run_id"),
                "kind",
                draft.get("kind"),
                "status",
                draft.get("status"),
                "revisionNo",
                Rows.number(draft, "current_revision_no"),
                "payloadSchemaVersion",
                "1",
                "payload",
                payload,
                "missingFields",
                payload.path("unresolvedFields"),
                "questions",
                payload.path("questions"),
                "base",
                Rows.json(revision, "base_snapshot_json"),
                "validation",
                report,
                "appliedResourceType",
                publicType(Rows.text(draft, "applied_resource_type")),
                "appliedResourceId",
                draft.get("applied_resource_id"),
                "appliedAt",
                Rows.instant(draft, "applied_at"),
                "expiresAt",
                Rows.instant(draft, "expires_at"));
    }

    /** Called only inside the worker's user/epoch/consent transaction. */
    public long createFromOutput(
            long userId, Map<String, Object> run, JsonNode input, JsonNode payload) {
        String kind =
                switch (Rows.text(run, "kind")) {
                    case "PLAN_GENERATE" -> "PLAN_CREATE";
                    case "PLAN_ADJUST" -> "PLAN_ADJUST";
                    default -> "RECORD_EXTRACT";
                };
        if (!kind.equals(payload.path("kind").asText())) throw invalid("模型输出的草稿类型不匹配");
        boolean missing =
                payload.path("unresolvedFields").isArray()
                        && !payload.path("unresolvedFields").isEmpty();
        if (!missing) validateStructure(payload, kind);
        JsonNode base = input.path("base");
        long id =
                sql.insert(
                        "ai_draft",
                        Rows.map(
                                "user_id",
                                userId,
                                "draft_key",
                                UUID.randomUUID().toString(),
                                "kind",
                                kind,
                                "status",
                                missing ? "NEEDS_INPUT" : "READY",
                                "source_run_id",
                                Rows.number(run, "id"),
                                "conversation_id",
                                run.get("conversation_id"),
                                "current_revision_no",
                                1,
                                "base_plan_id",
                                nullableJsonId(base, "planId"),
                                "base_plan_version_id",
                                nullableJsonId(base, "activeVersionId"),
                                "captured_dataset_epoch",
                                Rows.number(run, "captured_dataset_epoch"),
                                "expires_at",
                                Instant.now().plus(Duration.ofHours(24))));
        insertRevision(userId, id, 1, null, "MODEL", payload, base);
        return id;
    }

    public MutationResult revise(long id, AiDtos.DraftRevision request) {
        return mutations.mutate(
                "AI_DRAFT_REVISE",
                Rows.map("id", id, "request", request),
                true,
                () -> {
                    Map<String, Object> draft =
                            lockedEditable(
                                    id,
                                    request.expectedRowVersion(),
                                    request.expectedDraftRevisionNo());
                    boolean missing =
                            request.payload().path("unresolvedFields").isArray()
                                    && !request.payload().path("unresolvedFields").isEmpty();
                    if (!missing) validateStructure(request.payload(), Rows.text(draft, "kind"));
                    Map<String, Object> previous = currentRevision(draft);
                    int number = Rows.integer(draft, "current_revision_no") + 1;
                    insertRevision(
                            AuthContext.userId(),
                            id,
                            number,
                            number - 1,
                            "USER",
                            request.payload(),
                            Rows.json(previous, "base_snapshot_json"));
                    long version =
                            sql.update(
                                    "ai_draft",
                                    id,
                                    Rows.number(draft, "row_version"),
                                    Rows.map(
                                            "current_revision_no",
                                            number,
                                            "validated_revision_no",
                                            null,
                                            "status",
                                            missing ? "NEEDS_INPUT" : "READY"));
                    return MutationResult.of("AI_DRAFT", id, version)
                            .withExtra(Rows.map("revisionNo", number));
                });
    }

    public Map<String, Object> validate(long id, AiDtos.DraftValidate request) {
        Map<String, Object> initial = sql.owned("ai_draft", id, AuthContext.userId());
        JsonNode captured = Rows.json(currentRevision(initial), "base_snapshot_json");
        return guard.withUser(
                AuthContext.userId(),
                Rows.number(initial, "captured_dataset_epoch"),
                "AI_PROCESSING",
                Ids.parse(captured.path("consentRevision").asText()),
                () -> {
                    Map<String, Object> draft =
                            lockedEditable(
                                    id,
                                    request.expectedRowVersion(),
                                    request.expectedDraftRevisionNo());
                    checkSeq(request.expectedDataSeq());
                    Map<String, Object> revision = currentRevision(draft);
                    JsonNode payload = encrypted.open(Rows.json(revision, "payload_json"));
                    List<Map<String, Object>> issues =
                            inspect(AuthContext.userId(), draft, revision, payload);
                    Map<String, Object> report =
                            report(id, Rows.number(draft, "current_revision_no"), issues);
                    sql.execute(
                            "UPDATE ai_draft_revision SET"
                                    + " validation_status=?,validation_report_json=?,validated_at=?"
                                    + " WHERE id=? AND user_id=?",
                            issues.isEmpty() ? "VALID" : "INVALID",
                            mapper.valueToTree(report).toString(),
                            Rows.utc(Instant.now()),
                            Rows.number(revision, "id"),
                            AuthContext.userId());
                    long newVersion =
                            sql.update(
                                    "ai_draft",
                                    id,
                                    Rows.number(draft, "row_version"),
                                    Rows.map(
                                            "validated_revision_no",
                                            issues.isEmpty()
                                                    ? Rows.number(draft, "current_revision_no")
                                                    : null));
                    report.put("draftRowVersion", newVersion);
                    return report;
                });
    }

    public MutationResult apply(long id, AiDtos.DraftApply request) {
        return mutations.mutate(
                "AI_DRAFT_APPLY",
                Rows.map("id", id, "request", request),
                true,
                () -> {
                    Map<String, Object> draft =
                            lockedEditable(
                                    id,
                                    request.expectedRowVersion(),
                                    request.expectedDraftRevisionNo());
                    if (!Boolean.TRUE.equals(request.userConfirmed())) throw invalid("应用前必须明确确认");
                    checkSeq(request.expectedDataSeq());
                    Map<String, Object> revision = currentRevision(draft);
                    if (Rows.number(draft, "validated_revision_no")
                                    != Rows.number(draft, "current_revision_no")
                            || !"VALID".equals(revision.get("validation_status"))
                            || Rows.instant(revision, "validated_at") == null
                            || !Rows.instant(revision, "validated_at")
                                    .plusSeconds(600)
                                    .isAfter(Instant.now())) {
                        throw BusinessException.conflict("DRAFT_NOT_VALIDATED", "请先校验当前修订并核对修改预览");
                    }
                    JsonNode payload = encrypted.open(Rows.json(revision, "payload_json"));
                    List<Map<String, Object>> issues =
                            inspect(AuthContext.userId(), draft, revision, payload);
                    if (!issues.isEmpty())
                        throw BusinessException.conflict("DRAFT_SOURCE_CHANGED", "草稿依据已变化，请重新校验");
                    MutationResult applied;
                    String kind = Rows.text(draft, "kind");
                    if ("RECORD_EXTRACT".equals(kind)) {
                        AiDtos.RecordPayload value =
                                validation.convert(payload, AiDtos.RecordPayload.class);
                        RecordDtos.RecordCreate record =
                                validation.convert(value.record(), RecordDtos.RecordCreate.class);
                        if (request.clientRecordId() == null
                                || !request.clientRecordId()
                                        .toString()
                                        .equals(value.record().path("clientRecordId").asText())) {
                            throw invalid("记录编号必须与用户核对的草稿一致");
                        }
                        applied = records.createConfirmed(record, id);
                    } else {
                        if (request.effectiveFrom() == null || request.affectedTasks() == null)
                            throw invalid("计划应用必须明确影响起点与任务范围");
                        PlanDtos.ApplyScope scope =
                                new PlanDtos.ApplyScope(
                                        request.expectedDataSeq(),
                                        request.expectedActiveVersionId(),
                                        request.effectiveFrom(),
                                        request.affectedTasks(),
                                        request.allowFixedTaskChanges(),
                                        request.fixedChangeReason());
                        if ("PLAN_CREATE".equals(kind)) {
                            AiDtos.PlanCreatePayload value =
                                    validation.convert(payload, AiDtos.PlanCreatePayload.class);
                            applied =
                                    plans.applyAiCreate(
                                            new PlanDtos.PlanCreate(
                                                    value.planTitle(),
                                                    value.from().equals(value.to())
                                                            ? PlanDtos.PlanKind.DAY
                                                            : PlanDtos.PlanKind.CUSTOM,
                                                    value.timeZone(),
                                                    value.from(),
                                                    value.to(),
                                                    value.goalIds()),
                                            value.tasks(),
                                            scope);
                        } else {
                            AiDtos.PlanAdjustPayload value =
                                    validation.convert(payload, AiDtos.PlanAdjustPayload.class);
                            if (!Objects.equals(
                                    value.baseActiveVersionId(), request.expectedActiveVersionId()))
                                throw BusinessException.conflict(
                                        "ACTIVE_VERSION_CONFLICT", "当前计划版本与预览不一致");
                            applied =
                                    plans.applyAiAdjustment(
                                            value.planId(),
                                            value.baseActiveVersionId(),
                                            value.changes(),
                                            scope);
                        }
                    }
                    String storageType = "RECORD_EXTRACT".equals(kind) ? "HEALTH_RECORD" : "PLAN";
                    long resourceId = Ids.parse(applied.id());
                    sql.update(
                            "ai_draft",
                            id,
                            Rows.number(draft, "row_version"),
                            Rows.map(
                                    "status",
                                    "APPLIED",
                                    "applied_at",
                                    Instant.now(),
                                    "applied_resource_type",
                                    storageType,
                                    "applied_resource_id",
                                    resourceId,
                                    "applied_operation_id",
                                    RequestContext.idempotencyKey()));
                    Map<String, Object> extra =
                            new LinkedHashMap<>(
                                    applied.extra() == null ? Map.of() : applied.extra());
                    extra.putAll(
                            Rows.map(
                                    "draftId",
                                    id,
                                    "draftRevisionNo",
                                    Rows.number(draft, "current_revision_no"),
                                    "appliedResourceType",
                                    "RECORD_EXTRACT".equals(kind) ? "RECORD" : "PLAN",
                                    "appliedResourceId",
                                    resourceId));
                    return applied.withExtra(extra);
                });
    }

    public MutationResult cancel(long id, AiDtos.DraftCancel request) {
        return mutations.mutate(
                "AI_DRAFT_CANCEL",
                Rows.map("id", id, "request", request),
                true,
                () -> {
                    Map<String, Object> draft =
                            lockedEditable(
                                    id,
                                    request.expectedRowVersion(),
                                    request.expectedDraftRevisionNo());
                    long version =
                            sql.update(
                                    "ai_draft",
                                    id,
                                    Rows.number(draft, "row_version"),
                                    Rows.map("status", "CANCELLED", "validated_revision_no", null));
                    return MutationResult.of("AI_DRAFT", id, version);
                });
    }

    private void insertRevision(
            long userId,
            long draftId,
            int number,
            Integer previous,
            String source,
            JsonNode payload,
            JsonNode base) {
        sql.insert(
                "ai_draft_revision",
                Rows.map(
                        "user_id",
                        userId,
                        "draft_id",
                        draftId,
                        "revision_no",
                        number,
                        "source_revision",
                        previous,
                        "edit_source",
                        source,
                        "payload_schema_version",
                        1,
                        "payload_json",
                        encrypted.seal(payload),
                        "base_snapshot_json",
                        base,
                        "validation_status",
                        "NOT_VALIDATED",
                        "validation_report_json",
                        Rows.map(),
                        "payload_digest",
                        crypto.digest(payload)));
    }

    private List<Map<String, Object>> inspect(
            long userId,
            Map<String, Object> draft,
            Map<String, Object> revision,
            JsonNode payload) {
        List<Map<String, Object>> issues = new ArrayList<>();
        try {
            validateStructure(payload, Rows.text(draft, "kind"));
            JsonNode base = Rows.json(revision, "base_snapshot_json");
            Map<String, Object> consent =
                    sql.find(
                                    "SELECT * FROM user_consent WHERE user_id=? AND"
                                        + " purpose_code='AI_PROCESSING' AND deleted_at IS NULL",
                                    userId)
                            .orElseThrow(
                                    () ->
                                            BusinessException.forbidden(
                                                    "CONSENT_REQUIRED", "请先确认AI处理用途授权"));
            if (!"GRANTED".equals(consent.get("status"))
                    || (Rows.instant(consent, "expires_at") != null
                            && !Rows.instant(consent, "expires_at").isAfter(Instant.now())))
                throw BusinessException.forbidden("CONSENT_REQUIRED", "AI处理用途授权未生效或已撤回");
            if (!base.hasNonNull("consentRevision")
                    || Rows.number(consent, "consent_version")
                            != Ids.parse(base.path("consentRevision").asText()))
                throw BusinessException.conflict("CONSENT_WITHDRAWN", "草稿所依据的AI授权已变化，请重新生成");
            checkDependencies(userId, base);
            if ("PLAN_CREATE".equals(draft.get("kind"))) {
                AiDtos.PlanCreatePayload value =
                        validation.convert(payload, AiDtos.PlanCreatePayload.class);
                for (String goal : value.goalIds()) sql.owned("user_goal", Ids.parse(goal), userId);
                for (PlanDtos.TaskSnapshotInput task : value.tasks()) {
                    if (task.taskId() != null
                            || task.expectedTaskRowVersion() != null
                            || task.ruleId() != null
                            || task.occurrenceKey() != null) throw invalid("新计划任务不得冒用已有任务或循环规则标识");
                    LocalDate date =
                            task.scheduledStartAt()
                                    .atZone(ZoneId.of(value.timeZone()))
                                    .toLocalDate();
                    if (date.isBefore(value.from()) || date.isAfter(value.to()))
                        throw invalid("任务日期超出计划范围");
                }
                inspectTasks(userId, value.tasks(), Set.of(), issues);
            } else if ("PLAN_ADJUST".equals(draft.get("kind"))) {
                AiDtos.PlanAdjustPayload value =
                        validation.convert(payload, AiDtos.PlanAdjustPayload.class);
                Map<String, Object> plan = sql.owned("plan", Ids.parse(value.planId()), userId);
                if (Rows.number(plan, "active_version_id")
                        != Ids.parse(value.baseActiveVersionId()))
                    throw BusinessException.conflict("ACTIVE_VERSION_CONFLICT", "当前计划版本已变化");
                Set<Long> affected = new HashSet<>();
                List<PlanDtos.TaskSnapshotInput> tasks = new ArrayList<>();
                for (PlanDtos.DraftTaskChange change : value.changes()) {
                    if (!Set.of("ADD", "UPDATE", "REMOVE").contains(change.action()))
                        throw invalid("不支持的计划修改动作");
                    if (!"ADD".equals(change.action())) {
                        long taskId = Ids.parse(change.taskId());
                        Map<String, Object> task = sql.owned("task", taskId, userId);
                        if (Rows.number(task, "plan_id") != Ids.parse(value.planId())
                                || Rows.number(task, "row_version")
                                        != Ids.parse(change.expectedTaskRowVersion())
                                || !"PENDING".equals(task.get("status")))
                            throw BusinessException.conflict("TASK_CHANGED", "涉及任务已变化或不可调整");
                        if (!affected.add(taskId)) throw invalid("同一任务不能重复调整");
                    }
                    if (!"REMOVE".equals(change.action())) {
                        if (change.snapshot() == null) throw invalid("新增或修改任务必须有完整快照");
                        tasks.add(change.snapshot());
                    }
                }
                inspectTasks(userId, tasks, affected, issues);
            } else {
                AiDtos.RecordPayload value =
                        validation.convert(payload, AiDtos.RecordPayload.class);
                if (!value.unresolvedFields().isEmpty()) throw invalid("记录仍有待补充字段");
                RecordDtos.RecordCreate record =
                        validation.convert(value.record(), RecordDtos.RecordCreate.class);
                if (value.record().hasNonNull("tagIds"))
                    for (JsonNode tag : value.record().path("tagIds"))
                        sql.owned("record_tag", Ids.parse(tag.asText()), userId);
                StatisticsService.checkedZone(record.recordTimeZone());
            }
        } catch (BusinessException ex) {
            issues.add(
                    Rows.map(
                            "code",
                            ex.getCode(),
                            "severity",
                            "ERROR",
                            "fieldPath",
                            "payload",
                            "message",
                            ex.getMessage(),
                            "relatedResourceIds",
                            List.of(),
                            "suggestedAction",
                            "REFRESH_SOURCE"));
        }
        return issues;
    }

    private void inspectTasks(
            long userId,
            List<PlanDtos.TaskSnapshotInput> tasks,
            Set<Long> replacing,
            List<Map<String, Object>> issues) {
        for (PlanDtos.ValidationIssue issue : plans.validateAiAvailability(tasks))
            issues.add(
                    Rows.map(
                            "code",
                            issue.code(),
                            "severity",
                            issue.severity(),
                            "fieldPath",
                            issue.fieldPath(),
                            "message",
                            issue.message(),
                            "relatedResourceIds",
                            issue.relatedResourceIds(),
                            "suggestedAction",
                            issue.suggestedAction()));
        Set<UUID> keys = new HashSet<>();
        Map<LocalDate, Integer> perDay = new HashMap<>();
        int limit =
                Rows.integer(
                        sql.one(
                                "SELECT daily_task_limit FROM user_preference WHERE user_id=?",
                                userId),
                        "daily_task_limit");
        for (PlanDtos.TaskSnapshotInput task : tasks) {
            validation.validate(task);
            if (!keys.add(task.draftTaskKey())
                    || !task.scheduledEndAt().isAfter(task.scheduledStartAt())
                    || Duration.between(task.scheduledStartAt(), task.scheduledEndAt()).toMinutes()
                            < task.plannedDurationMinutes()) throw invalid("任务标识、时间窗或时长无效");
            ZoneId zone = ZoneId.of(StatisticsService.checkedZone(task.taskTimeZone()));
            LocalDate date = task.scheduledStartAt().atZone(zone).toLocalDate();
            if (perDay.merge(date, 1, Integer::sum) > limit) throw invalid("草稿单日任务数超过用户上限");
            List<Map<String, Object>> overlap =
                    sql.query(
                            "SELECT id FROM task WHERE user_id=? AND deleted_at IS NULL AND status"
                                + " IN ('PENDING','COMPLETED','SKIPPED') AND scheduled_start_at<?"
                                + " AND scheduled_end_at>?",
                            userId,
                            task.scheduledEndAt(),
                            task.scheduledStartAt());
            if (overlap.stream().anyMatch(r -> !replacing.contains(Rows.number(r, "id")))) {
                issues.add(
                        Rows.map(
                                "code",
                                "TASK_TIME_CONFLICT",
                                "severity",
                                "ERROR",
                                "fieldPath",
                                "tasks",
                                "message",
                                "与现行任务时间冲突",
                                "relatedResourceIds",
                                overlap.stream().map(r -> Rows.number(r, "id")).toList(),
                                "suggestedAction",
                                "EDIT"));
            }
        }
        for (int i = 0; i < tasks.size(); i++)
            for (int j = i + 1; j < tasks.size(); j++) {
                if (tasks.get(i).scheduledStartAt().isBefore(tasks.get(j).scheduledEndAt())
                        && tasks.get(j).scheduledStartAt().isBefore(tasks.get(i).scheduledEndAt()))
                    throw invalid("草稿中的任务时间互相重叠");
            }
    }

    private void validateStructure(JsonNode payload, String kind) {
        if (payload == null || !payload.isObject() || !kind.equals(payload.path("kind").asText()))
            throw invalid("草稿结构或类型无效");
        switch (kind) {
            case "PLAN_CREATE" -> {
                AiDtos.PlanCreatePayload value =
                        validation.convert(payload, AiDtos.PlanCreatePayload.class);
                StatisticsService.dateRange(value.from(), value.to(), 14);
                StatisticsService.checkedZone(value.timeZone());
            }
            case "PLAN_ADJUST" -> validation.convert(payload, AiDtos.PlanAdjustPayload.class);
            default -> {
                AiDtos.RecordPayload value =
                        validation.convert(payload, AiDtos.RecordPayload.class);
                if (value.unresolvedFields().isEmpty())
                    validation.convert(value.record(), RecordDtos.RecordCreate.class);
            }
        }
    }

    private void checkDependencies(long userId, JsonNode base) {
        for (String collection :
                List.of("goalVersions", "availabilityVersions", "preferenceVersions")) {
            JsonNode versions = base.path(collection);
            for (JsonNode ref : versions) {
                String table =
                        ref.path("table")
                                .asText(
                                        switch (collection) {
                                            case "goalVersions" -> "user_goal";
                                            case "preferenceVersions" -> "user_preference";
                                            default -> "availability_rule";
                                        });
                if (!Set.of(
                                "user_goal",
                                "user_preference",
                                "user_profile",
                                "availability_rule",
                                "availability_exception")
                        .contains(table)) throw invalid("来源快照无效");
                Map<String, Object> actual =
                        sql.owned(table, Ids.parse(ref.path("id").asText()), userId);
                if (Rows.number(actual, "row_version")
                        != Ids.parse(ref.path("rowVersion").asText()))
                    throw BusinessException.conflict("DRAFT_SOURCE_CHANGED", "目标或时间偏好已变化");
            }
        }
    }

    private Map<String, Object> lockedEditable(
            long id, String expectedVersion, String expectedRevision) {
        Map<String, Object> row = sql.lockOwned("ai_draft", id, AuthContext.userId());
        if (Rows.number(row, "captured_dataset_epoch")
                != Rows.number(
                        sql.one(
                                "SELECT dataset_epoch FROM user_sync_clock WHERE user_id=?",
                                AuthContext.userId()),
                        "dataset_epoch"))
            throw BusinessException.conflict("DATA_EPOCH_CHANGED", "草稿所属数据代际已改变，请重新生成");
        if ("APPLIED".equals(row.get("status")))
            throw BusinessException.conflict("DRAFT_ALREADY_APPLIED", "此草稿已经应用，不能再次创建");
        if (!Set.of("READY", "NEEDS_INPUT").contains(Rows.text(row, "status"))
                || Rows.instant(row, "expires_at").isBefore(Instant.now()))
            throw BusinessException.conflict("DRAFT_EXPIRED", "此草稿不可继续使用");
        if (Rows.number(row, "row_version") != Ids.parse(expectedVersion)
                || Rows.number(row, "current_revision_no") != Ids.parse(expectedRevision))
            throw BusinessException.conflict("DRAFT_REVISION_CONFLICT", "草稿修订已变化，请刷新");
        return row;
    }

    private Map<String, Object> currentRevision(Map<String, Object> draft) {
        return sql.one(
                "SELECT * FROM ai_draft_revision WHERE user_id=? AND draft_id=? AND revision_no=?",
                Rows.number(draft, "user_id"),
                Rows.number(draft, "id"),
                Rows.number(draft, "current_revision_no"));
    }

    private void checkSeq(String expected) {
        long actual =
                sql.count(
                        "SELECT committed_seq FROM user_sync_clock WHERE user_id=?",
                        AuthContext.userId());
        if (parseSeq(expected) != actual)
            throw BusinessException.conflict("SOURCE_CHANGED", "数据已变化，请重新读取预览");
    }

    private Map<String, Object> report(long id, long revision, List<Map<String, Object>> issues) {
        Map<String, Object> clock =
                sql.one("SELECT * FROM user_sync_clock WHERE user_id=?", AuthContext.userId());
        return Rows.map(
                "draftId",
                id,
                "revisionNo",
                revision,
                "valid",
                issues.isEmpty(),
                "issues",
                issues,
                "checkedDataSeq",
                Rows.number(clock, "committed_seq"),
                "checkedEpoch",
                Rows.number(clock, "dataset_epoch"),
                "checkedAt",
                Instant.now(),
                "expiresAt",
                Instant.now().plus(Duration.ofMinutes(10)));
    }

    private Long nullableJsonId(JsonNode node, String key) {
        return node.path(key).isNull() || node.path(key).isMissingNode()
                ? null
                : Ids.parse(node.path(key).asText());
    }

    private long parseSeq(String value) {
        return "0".equals(value) ? 0 : Ids.parse(value);
    }

    private String publicType(String type) {
        return "HEALTH_RECORD".equals(type) ? "RECORD" : type;
    }

    private BusinessException invalid(String message) {
        return BusinessException.badRequest("OUTPUT_INVALID", message);
    }
}
