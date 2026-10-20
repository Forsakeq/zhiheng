package com.yqsoft.service.plan;

import static com.yqsoft.resource.common.Rows.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yqsoft.dao.SqlStore;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.persistence.MutationExecutor;
import com.yqsoft.resource.common.*;
import com.yqsoft.resource.plan.PlanDtos.*;
import com.yqsoft.security.AuthContext;
import com.yqsoft.service.common.CryptoService;
import com.yqsoft.service.common.CursorService;
import com.yqsoft.service.record.RecordService;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Plans are immutable configurations; task rows are stable execution instances. */
@Service
public class PlanService {
    private final SqlStore store;
    private final MutationExecutor mutations;
    private final CryptoService crypto;
    private final CursorService cursors;
    private final ValidationSupport validation;
    private final ObjectMapper mapper;

    public PlanService(
            SqlStore store,
            MutationExecutor mutations,
            CryptoService crypto,
            CursorService cursors,
            ValidationSupport validation,
            ObjectMapper mapper) {
        this.store = store;
        this.mutations = mutations;
        this.crypto = crypto;
        this.cursors = cursors;
        this.validation = validation;
        this.mapper = mapper;
    }

    public PageResult<PlanView> listPlans(PlanQuery query) {
        validation.validate(query);
        String status = query.status() == null ? "ACTIVE" : query.status().name();
        String scope = "plans:" + status;
        long after = after(query.cursor(), scope);
        int limit = limit(query.limit());
        List<Map<String, Object>> rows =
                store.query(
                        "SELECT * FROM plan WHERE user_id=? AND deleted_at IS NULL AND status=? AND"
                                + " id>? ORDER BY id LIMIT ?",
                        uid(),
                        status,
                        after,
                        limit + 1);
        boolean more = rows.size() > limit;
        if (more) rows = rows.subList(0, limit);
        return PageResult.of(
                rows.stream().map(this::planView).toList(), next(rows, scope, more), more);
    }

    public MutationResult createPlan(PlanCreate request) {
        return createPlan(request, "MANUAL");
    }

    private MutationResult createPlan(PlanCreate request, String source) {
        validation.validate(request);
        RecordService.requireRange(request.startDate(), request.endDate(), 31);
        zone(request.timeZone());
        return mutations.mutate(
                "CREATE_PLAN",
                request,
                true,
                () -> {
                    List<Map<String, Object>> goals = new ArrayList<>();
                    for (String goal : safe(request.goalIds())) {
                        Map<String, Object> row = store.owned("user_goal", Ids.parse(goal), uid());
                        goals.add(
                                map(
                                        "id",
                                        goal,
                                        "rowVersion",
                                        Long.toString(number(row, "row_version"))));
                    }
                    long id =
                            store.insert(
                                    "plan",
                                    map(
                                            "user_id",
                                            uid(),
                                            "plan_key",
                                            UUID.randomUUID().toString(),
                                            "name",
                                            request.title(),
                                            "plan_kind",
                                            request.planKind().name(),
                                            "status",
                                            "DRAFT",
                                            "range_start_date",
                                            request.startDate(),
                                            "range_end_date",
                                            request.endDate(),
                                            "plan_time_zone",
                                            request.timeZone(),
                                            "latest_version_no",
                                            1,
                                            "source_type",
                                            source));
                    long version =
                            createVersionRow(
                                    id,
                                    1,
                                    null,
                                    source,
                                    "初始计划草稿",
                                    request.startDate()
                                            .atStartOfDay(zone(request.timeZone()))
                                            .toInstant(),
                                    map("goalVersions", goals));
                    syncPlan(id);
                    syncVersion(version);
                    return MutationResult.of("PLAN", id, 1)
                            .withExtra(map("planVersionId", Long.toString(version)));
                });
    }

    public PlanDetailView getPlan(long id) {
        Map<String, Object> plan = store.owned("plan", id, uid());
        Long active = nullableId(plan, "active_version_id");
        List<PlanVersionView> drafts =
                store
                        .query(
                                "SELECT * FROM plan_version WHERE user_id=? AND plan_id=? AND"
                                    + " status='DRAFT' AND deleted_at IS NULL ORDER BY version_no"
                                    + " DESC LIMIT 20",
                                uid(),
                                id)
                        .stream()
                        .map(row -> versionView(row, active))
                        .toList();
        return new PlanDetailView(
                planView(plan), active == null ? null : getVersion(id, active), drafts);
    }

    public PageResult<PlanVersionView> listVersions(
            long planId, String cursor, Integer requestedLimit) {
        Map<String, Object> plan = store.owned("plan", planId, uid());
        String scope = "plan-versions:" + planId;
        long before = after(cursor, scope);
        int count = limit(requestedLimit);
        List<Map<String, Object>> rows =
                store.query(
                        "SELECT * FROM plan_version WHERE user_id=? AND plan_id=? AND deleted_at IS"
                                + " NULL AND (?=0 OR id<?) ORDER BY id DESC LIMIT ?",
                        uid(),
                        planId,
                        before,
                        before,
                        count + 1);
        boolean more = rows.size() > count;
        if (more) rows = rows.subList(0, count);
        return PageResult.of(
                rows.stream()
                        .map(row -> versionView(row, nullableId(plan, "active_version_id")))
                        .toList(),
                next(rows, scope, more),
                more);
    }

    public PlanVersionDetail getVersion(long planId, long versionId) {
        Map<String, Object> plan = store.owned("plan", planId, uid());
        Map<String, Object> version = version(planId, versionId);
        return new PlanVersionDetail(
                versionView(version, nullableId(plan, "active_version_id")),
                nullableId(version, "based_on_version_id"),
                snapshotRows(versionId).stream().map(this::snapshot).toList(),
                nullableId(version, "source_draft_id"),
                instant(version, "effective_from"));
    }

    public MutationResult createVersion(long planId, PlanVersionCreate request) {
        validation.validate(request);
        if (request.copyFromVersionId() != null && !safe(request.tasks()).isEmpty())
            throw invalid("VERSION_SOURCE_CONFLICT", "复制来源与任务列表不可同时填写");
        return mutations.mutate(
                "CREATE_PLAN_VERSION",
                map("planId", planId, "request", request),
                true,
                () -> {
                    Map<String, Object> plan = store.lockOwned("plan", planId, uid());
                    expectActive(plan, request.expectedActiveVersionId());
                    List<TaskSnapshotInput> tasks =
                            request.copyFromVersionId() == null
                                    ? safe(request.tasks())
                                    : snapshotRows(
                                                    number(
                                                            version(
                                                                    planId,
                                                                    Ids.parse(
                                                                            request
                                                                                    .copyFromVersionId())),
                                                            "id"))
                                            .stream()
                                            .filter(
                                                    row ->
                                                            !"REMOVE"
                                                                    .equals(
                                                                            text(
                                                                                    row,
                                                                                    "change_action")))
                                            .map(this::snapshot)
                                            .toList();
                    validateShapes(planId, tasks);
                    long no = number(plan, "latest_version_no") + 1;
                    long planVersion =
                            store.update(
                                    "plan",
                                    planId,
                                    Ids.parse(request.expectedPlanRowVersion()),
                                    map("latest_version_no", no));
                    long id =
                            createVersionRow(
                                    planId,
                                    no,
                                    nullableId(plan, "active_version_id"),
                                    "MANUAL",
                                    request.changeReason(),
                                    Instant.now(),
                                    constraintSnapshot(plan));
                    saveSnapshots(planId, id, tasks);
                    syncVersion(id);
                    syncPlan(planId);
                    return MutationResult.of("PLAN_VERSION", id, 1)
                            .withExtra(map("planRowVersion", Long.toString(planVersion)));
                });
    }

    public MutationResult replaceVersion(long planId, long versionId, PlanVersionReplace request) {
        validation.validate(request);
        return mutations.mutate(
                "REPLACE_PLAN_VERSION_DRAFT",
                map("planId", planId, "versionId", versionId, "request", request),
                true,
                () -> {
                    Map<String, Object> plan = store.lockOwned("plan", planId, uid());
                    expectActive(plan, request.expectedActiveVersionId());
                    Map<String, Object> version = version(planId, versionId);
                    requireDraft(version);
                    validateShapes(planId, request.tasks());
                    store.execute(
                            "DELETE FROM plan_version_task WHERE user_id=? AND version_id=?",
                            uid(),
                            versionId);
                    saveSnapshots(planId, versionId, request.tasks());
                    long rowVersion =
                            store.update(
                                    "plan_version",
                                    versionId,
                                    Ids.parse(request.expectedRowVersion()),
                                    map(
                                            "change_summary",
                                            request.changeReason(),
                                            "validation_report_json",
                                            null));
                    syncVersion(versionId);
                    return MutationResult.of("PLAN_VERSION", versionId, rowVersion);
                });
    }

    public PlanValidationView validate(long planId, long versionId, PlanValidationRequest request) {
        validation.validate(request);
        return check(
                        planId,
                        versionId,
                        new PlanScopeRequest(
                                request.expectedRowVersion(),
                                request.expectedActiveVersionId(),
                                request.effectiveFrom(),
                                request.affectedTaskIds(),
                                request.allowFixedTaskChanges(),
                                request.fixedChangeReason(),
                                List.of()),
                        false)
                .validation();
    }

    public PlanPreview preview(long planId, long versionId, PlanScopeRequest request) {
        validation.validate(request);
        return check(planId, versionId, request, true);
    }

    /** Shared read-only constraints for an AI preview before a persisted plan version exists. */
    public List<ValidationIssue> validateAiAvailability(List<TaskSnapshotInput> tasks) {
        List<ValidationIssue> issues = new ArrayList<>();
        for (int index = 0; index < tasks.size(); index++) {
            validation.validate(tasks.get(index));
            validateTaskTime(tasks.get(index));
            checkAvailability(tasks.get(index), "tasks[" + index + "]", issues);
        }
        return List.copyOf(issues);
    }

    private PlanPreview check(
            long planId, long versionId, PlanScopeRequest request, boolean expectedTasksRequired) {
        Map<String, Object> plan = store.owned("plan", planId, uid());
        Map<String, Object> version = version(planId, versionId);
        requireDraft(version);
        expectVersion(version, request.expectedRowVersion());
        expectActive(plan, request.expectedActiveVersionId());
        List<TaskSnapshotInput> proposed =
                snapshotRows(versionId).stream()
                        .filter(row -> !"REMOVE".equals(text(row, "change_action")))
                        .map(this::snapshot)
                        .toList();
        Map<Long, Map<String, Object>> current = currentTasks(planId);
        Set<Long> affected = idSet(request.affectedTaskIds(), 500);
        Map<Long, Long> expected = expectedMap(request.expectedTasks());
        if (expectedTasksRequired && !expected.keySet().equals(affected))
            throw invalid("AFFECTED_TASK_SET_MISMATCH", "受影响任务与预期版本集合必须一致");
        List<ValidationIssue> issues = new ArrayList<>();
        List<PlanChangeView> changes = new ArrayList<>();
        List<String> historical = new ArrayList<>();
        Set<Long> included = new HashSet<>();
        Map<LocalDate, Integer> dailyCounts = new HashMap<>();
        Set<LocalDate> changedDays = new HashSet<>();
        for (int index = 0; index < proposed.size(); index++) {
            TaskSnapshotInput input = proposed.get(index);
            String path = "tasks[" + index + "]";
            Map<String, Object> old =
                    input.taskId() == null ? null : current.get(Ids.parse(input.taskId()));
            boolean changed =
                    old == null
                            || different(input, old)
                            || ("CANCELLED".equals(text(old, "status"))
                                    && affected.contains(number(old, "id")));
            if (old != null) included.add(number(old, "id"));
            if (input.taskId() != null && old == null)
                issues.add(issue("TASK_NOT_IN_PLAN", path, "任务不属于当前计划", input.taskId()));
            if (old != null
                    && input.expectedTaskRowVersion() != null
                    && Ids.parse(input.expectedTaskRowVersion()) != number(old, "row_version"))
                issues.add(issue("TASK_VERSION_CHANGED", path, "任务已在另一端变化，请重新获取", input.taskId()));
            if (changed) {
                if (input.scheduledStartAt().isBefore(request.effectiveFrom()))
                    issues.add(issue("BEFORE_EFFECTIVE_FROM", path, "变更早于允许生效范围", input.taskId()));
                if (old != null && !affected.contains(number(old, "id")))
                    issues.add(issue("TASK_OUTSIDE_SCOPE", path, "任务不在明确确认的影响范围内", input.taskId()));
                if (old != null && Set.of("COMPLETED", "SKIPPED").contains(text(old, "status")))
                    issues.add(issue("HISTORY_IMMUTABLE", path, "已完成或跳过的历史不得改写", input.taskId()));
                if (old != null && bool(old, "is_fixed") && !fixedAllowed(request))
                    issues.add(
                            issue(
                                    "FIXED_TASK_CONFIRMATION_REQUIRED",
                                    path,
                                    "固定任务变更需要明确确认和原因",
                                    input.taskId()));
            }
            LocalDate day =
                    input.scheduledStartAt().atZone(zone(input.taskTimeZone())).toLocalDate();
            if (day.isBefore(date(plan, "range_start_date"))
                    || day.isAfter(date(plan, "range_end_date")))
                issues.add(issue("OUTSIDE_PLAN_RANGE", path, "任务日期超出计划范围", input.taskId()));
            if (old == null || changed || !"CANCELLED".equals(text(old, "status")))
                dailyCounts.merge(day, 1, Integer::sum);
            if (changed) {
                changedDays.add(day);
                if (old != null) changedDays.add(date(old, "task_local_date"));
                checkAvailability(input, path, issues);
                if (store.count(
                                "SELECT COUNT(*) FROM task WHERE user_id=? AND plan_id<>? AND"
                                        + " deleted_at IS NULL AND status='PENDING' AND"
                                        + " scheduled_start_at<? AND scheduled_end_at>?",
                                uid(),
                                planId,
                                utc(input.scheduledEndAt()),
                                utc(input.scheduledStartAt()))
                        > 0) {
                    issues.add(
                            issue(
                                    "OTHER_PLAN_TASK_OVERLAP",
                                    path,
                                    "任务与另一计划中的待执行任务重叠",
                                    input.taskId()));
                }
            }
            changes.add(
                    new PlanChangeView(
                            old == null ? "ADD" : changed ? "UPDATE" : "KEEP",
                            input.taskId(),
                            input.draftTaskKey(),
                            old == null ? null : taskSnapshot(old),
                            input,
                            changed ? "用户确认的计划配置变更" : "配置保持",
                            old != null && bool(old, "is_fixed") && changed));
        }
        for (Map<String, Object> old : current.values()) {
            long id = number(old, "id");
            boolean history = Set.of("COMPLETED", "SKIPPED").contains(text(old, "status"));
            if (history) historical.add(Long.toString(id));
            if (expected.containsKey(id) && expected.get(id) != number(old, "row_version"))
                issues.add(
                        issue(
                                "TASK_VERSION_CHANGED",
                                "expectedTasks",
                                "受影响任务版本已变化",
                                Long.toString(id)));
            if (!included.contains(id) && affected.contains(id)) {
                changedDays.add(date(old, "task_local_date"));
                if (history)
                    issues.add(
                            issue(
                                    "HISTORY_IMMUTABLE",
                                    "affectedTaskIds",
                                    "历史任务不可移除",
                                    Long.toString(id)));
                if (instant(old, "scheduled_start_at").isBefore(request.effectiveFrom()))
                    issues.add(
                            issue(
                                    "BEFORE_EFFECTIVE_FROM",
                                    "affectedTaskIds",
                                    "任务早于生效范围",
                                    Long.toString(id)));
                if (bool(old, "is_fixed") && !fixedAllowed(request))
                    issues.add(
                            issue(
                                    "FIXED_TASK_CONFIRMATION_REQUIRED",
                                    "affectedTaskIds",
                                    "移除固定任务需要确认原因",
                                    Long.toString(id)));
                changes.add(
                        new PlanChangeView(
                                "REMOVE",
                                Long.toString(id),
                                UUID.randomUUID(),
                                taskSnapshot(old),
                                null,
                                "用户确认移除",
                                bool(old, "is_fixed")));
            } else if (!included.contains(id) && !"CANCELLED".equals(text(old, "status"))) {
                TaskSnapshotInput snapshot = taskSnapshot(old);
                changes.add(
                        new PlanChangeView(
                                "KEEP",
                                Long.toString(id),
                                snapshot.draftTaskKey(),
                                snapshot,
                                snapshot,
                                "保留范围外或已执行历史",
                                false));
                dailyCounts.merge(date(old, "task_local_date"), 1, Integer::sum);
            }
        }
        if (!current.keySet().containsAll(affected)) throw BusinessException.notFound();
        int maximum =
                (int)
                        store.count(
                                "SELECT daily_task_limit FROM user_preference WHERE user_id=?",
                                uid());
        for (LocalDate day : changedDays) {
            long other =
                    store.count(
                            "SELECT COUNT(*) FROM task WHERE user_id=? AND plan_id<>? AND"
                                    + " task_local_date=? AND deleted_at IS NULL AND"
                                    + " status<>'CANCELLED'",
                            uid(),
                            planId,
                            day);
            if (dailyCounts.getOrDefault(day, 0) + other > maximum)
                issues.add(issue("DAILY_TASK_LIMIT", "tasks", day + " 的任务数量超过已设上限", null));
        }
        List<TaskSnapshotInput> effective =
                changes.stream()
                        .filter(change -> !"REMOVE".equals(change.action()))
                        .filter(
                                change ->
                                        !"KEEP".equals(change.action())
                                                || change.taskId() == null
                                                || !Set.of("COMPLETED", "SKIPPED", "CANCELLED")
                                                        .contains(
                                                                text(
                                                                        current.get(
                                                                                Ids.parse(
                                                                                        change
                                                                                                .taskId())),
                                                                        "status")))
                        .map(PlanChangeView::after)
                        .filter(Objects::nonNull)
                        .sorted(Comparator.comparing(TaskSnapshotInput::scheduledStartAt))
                        .toList();
        for (int i = 1; i < effective.size(); i++)
            if (effective.get(i).scheduledStartAt().isBefore(effective.get(i - 1).scheduledEndAt()))
                issues.add(issue("TASK_OVERLAP", "tasks", "执行任务时间重叠", effective.get(i).taskId()));
        long sequence =
                store.count("SELECT committed_seq FROM user_sync_clock WHERE user_id=?", uid());
        Instant now = Instant.now();
        PlanValidationView result =
                new PlanValidationView(
                        issues.isEmpty(),
                        issues,
                        versionId,
                        number(version, "row_version"),
                        nullableId(plan, "active_version_id"),
                        sequence,
                        now,
                        now.plusSeconds(600));
        List<ReminderChangeView> reminderChanges = new ArrayList<>();
        for (PlanChangeView change : changes)
            if (!"KEEP".equals(change.action())) {
                if (change.taskId() != null)
                    reminderChanges.add(
                            new ReminderChangeView(
                                    change.taskId(),
                                    "CANCEL",
                                    null,
                                    null,
                                    "IN_APP",
                                    "任务配置变化，撤销旧版本提醒"));
                if (change.after() != null
                        && change.after().reminder() != null
                        && Boolean.TRUE.equals(change.after().reminder().enabled()))
                    reminderChanges.add(
                            new ReminderChangeView(
                                    change.taskId(),
                                    "CREATE",
                                    null,
                                    change.after()
                                            .scheduledStartAt()
                                            .plusSeconds(
                                                    60L
                                                            * (change.after()
                                                                                    .reminder()
                                                                                    .offsetMinutes()
                                                                            == null
                                                                    ? 0
                                                                    : change.after()
                                                                            .reminder()
                                                                            .offsetMinutes())),
                                    change.after().reminder().channel() == null
                                            ? "IN_APP"
                                            : change.after().reminder().channel(),
                                    "按新版本提醒意愿计算"));
            }
        return new PlanPreview(
                result,
                changes,
                affected.stream().map(String::valueOf).sorted().toList(),
                historical,
                reminderChanges,
                request.effectiveFrom(),
                result.valid());
    }

    public MutationResult activate(long planId, long versionId, PlanActivate request) {
        validation.validate(request);
        return mutations.mutate(
                "ACTIVATE_PLAN_VERSION",
                map("planId", planId, "versionId", versionId, "request", request),
                true,
                () -> {
                    if (!Boolean.TRUE.equals(request.userConfirmed()))
                        throw invalid("CONFIRMATION_REQUIRED", "请明确确认应用计划");
                    if (Ids.nonNegative(request.expectedDataSeq()) != mutations.currentSeq())
                        throw BusinessException.conflict("PREVIEW_STALE", "预览后数据发生变化，请重新预览");
                    Map<String, Object> plan = store.lockOwned("plan", planId, uid());
                    Map<String, Object> version = version(planId, versionId);
                    PlanPreview preview = check(planId, versionId, request.scope(), true);
                    if (!preview.canApply())
                        throw BusinessException.conflict(
                                "PLAN_VALIDATION_FAILED", "计划存在冲突，请先查看校验结果");
                    // Normalize this DRAFT to a full snapshot including protected
                    // historical/out-of-scope tasks.
                    store.execute(
                            "DELETE FROM plan_version_task WHERE user_id=? AND version_id=?",
                            uid(),
                            versionId);
                    Set<LocalDate> dates = new HashSet<>();
                    List<String> affected = new ArrayList<>();
                    for (PlanChangeView change : preview.changes()) {
                        TaskSnapshotInput input =
                                change.after() == null ? change.before() : change.after();
                        Long taskId = input.taskId() == null ? null : Ids.parse(input.taskId());
                        Map<String, Object> old =
                                taskId == null ? null : store.lockOwned("task", taskId, uid());
                        long before = old == null ? 0 : number(old, "row_version");
                        long taskVersion = before;
                        if ("ADD".equals(change.action())) {
                            taskId =
                                    store.insert(
                                            "task",
                                            taskValues(
                                                    planId,
                                                    versionId,
                                                    input,
                                                    text(version, "source_type")));
                            taskVersion = 1;
                        } else if ("UPDATE".equals(change.action())) {
                            dates.add(date(old, "task_local_date"));
                            cancelReminders(taskId, before);
                            Map<String, Object> values =
                                    taskValues(
                                            planId, versionId, input, text(version, "source_type"));
                            values.remove("user_id");
                            values.remove("plan_id");
                            taskVersion = store.update("task", taskId, before, values);
                        } else if ("REMOVE".equals(change.action())) {
                            dates.add(date(old, "task_local_date"));
                            cancelReminders(taskId, before);
                            taskVersion =
                                    store.update(
                                            "task",
                                            taskId,
                                            before,
                                            map(
                                                    "status",
                                                    "CANCELLED",
                                                    "cancelled_at",
                                                    Instant.now(),
                                                    "current_plan_version_id",
                                                    versionId));
                        }
                        TaskSnapshotInput stable = withTask(input, taskId, taskVersion);
                        long snapshotId = saveSnapshot(planId, versionId, stable, change.action());
                        if (!"KEEP".equals(change.action())) {
                            String event =
                                    "ADD".equals(change.action())
                                            ? "CREATE"
                                            : "REMOVE".equals(change.action())
                                                    ? "CANCEL"
                                                    : !Objects.equals(
                                                                    instant(
                                                                            old,
                                                                            "scheduled_start_at"),
                                                                    input.scheduledStartAt())
                                                            ? "RESCHEDULE"
                                                            : "EDIT";
                            Map<String, Object> current = store.owned("task", taskId, uid());
                            event(
                                    taskId,
                                    event,
                                    before,
                                    taskVersion,
                                    old == null ? null : text(old, "status"),
                                    text(current, "status"),
                                    null,
                                    null,
                                    null,
                                    versionId,
                                    snapshotId);
                            affected.add(Long.toString(taskId));
                            dates.add(date(current, "task_local_date"));
                            syncTask(taskId);
                            if (!"REMOVE".equals(change.action())) applyReminder(taskId, stable);
                        }
                    }
                    long sealed =
                            store.update(
                                    "plan_version",
                                    versionId,
                                    Ids.parse(request.expectedRowVersion()),
                                    map(
                                            "status",
                                            "SEALED",
                                            "effective_from",
                                            request.effectiveFrom(),
                                            "affected_task_ids_json",
                                            request.affectedTaskIds(),
                                            "validation_report_json",
                                            projection(preview.validation()),
                                            "sealed_at",
                                            Instant.now(),
                                            "sealed_by_operation_id",
                                            RequestContext.idempotencyKey(),
                                            "content_digest",
                                            crypto.digest(preview.changes())));
                    long planVersion =
                            store.update(
                                    "plan",
                                    planId,
                                    number(plan, "row_version"),
                                    map("active_version_id", versionId, "status", "ACTIVE"));
                    syncVersion(versionId);
                    syncPlan(planId);
                    invalidate("PLAN", planId, dates);
                    return MutationResult.of("PLAN", planId, planVersion)
                            .withExtra(
                                    map(
                                            "planVersionId",
                                            Long.toString(versionId),
                                            "planVersionRowVersion",
                                            Long.toString(sealed),
                                            "affectedTaskIds",
                                            affected));
                });
    }

    public MutationResult restoreVersion(
            long planId, long historicalVersionId, PlanRestore request) {
        validation.validate(request);
        return mutations.mutate(
                "RESTORE_PLAN_VERSION",
                map("planId", planId, "versionId", historicalVersionId, "request", request),
                true,
                () -> {
                    Map<String, Object> old = version(planId, historicalVersionId);
                    expectVersion(old, request.expectedRowVersion());
                    if (!"SEALED".equals(text(old, "status")))
                        throw invalid("SEALED_VERSION_REQUIRED", "只能复制已封存历史版本");
                    Map<String, Object> plan = store.lockOwned("plan", planId, uid());
                    expectActive(plan, request.expectedActiveVersionId());
                    Set<Long> affected = idSet(request.affectedTaskIds(), 500);
                    Map<Long, Map<String, Object>> current = currentTasks(planId);
                    List<TaskSnapshotInput> tasks = new ArrayList<>();
                    for (Map<String, Object> snapshot : snapshotRows(historicalVersionId)) {
                        if ("REMOVE".equals(text(snapshot, "change_action"))) continue;
                        TaskSnapshotInput input = snapshot(snapshot);
                        long task = Ids.parse(input.taskId());
                        Map<String, Object> row = current.get(task);
                        if (row == null)
                            throw BusinessException.conflict(
                                    "RESTORE_SOURCE_MISSING", "历史任务已不在可恢复数据中");
                        if (affected.contains(task)
                                && !Set.of("COMPLETED", "SKIPPED").contains(text(row, "status"))
                                && !input.scheduledStartAt().isBefore(request.effectiveFrom()))
                            tasks.add(withTask(input, task, number(row, "row_version")));
                        else tasks.add(taskSnapshot(row));
                    }
                    long no = number(plan, "latest_version_no") + 1;
                    store.update(
                            "plan",
                            planId,
                            Ids.parse(request.expectedPlanRowVersion()),
                            map("latest_version_no", no));
                    long id =
                            createVersionRow(
                                    planId,
                                    no,
                                    nullableId(plan, "active_version_id"),
                                    "RESTORE",
                                    request.reason(),
                                    request.effectiveFrom(),
                                    constraintSnapshot(plan));
                    store.execute(
                            "UPDATE plan_version SET restore_from_version_id=? WHERE id=?",
                            historicalVersionId,
                            id);
                    saveSnapshots(planId, id, tasks);
                    syncVersion(id);
                    syncPlan(planId);
                    return MutationResult.of("PLAN_VERSION", id, 1);
                });
    }

    public MutationResult discard(long planId, long versionId, VersionBody request) {
        validation.validate(request);
        return mutations.mutate(
                "DISCARD_PLAN_VERSION",
                map("planId", planId, "versionId", versionId, "request", request),
                true,
                () -> {
                    Map<String, Object> version = version(planId, versionId);
                    requireDraft(version);
                    long value =
                            store.update(
                                    "plan_version",
                                    versionId,
                                    Ids.parse(request.expectedRowVersion()),
                                    map("status", "DISCARDED"));
                    syncVersion(versionId);
                    return MutationResult.of("PLAN_VERSION", versionId, value);
                });
    }

    public MutationResult archive(long planId, PlanArchive request) {
        validation.validate(request);
        return mutations.mutate(
                "ARCHIVE_PLAN",
                map("planId", planId, "request", request),
                true,
                () -> {
                    Map<String, Object> plan = store.lockOwned("plan", planId, uid());
                    expectActive(plan, request.expectedActiveVersionId());
                    expectVersion(plan, request.expectedRowVersion());
                    List<Map<String, Object>> candidates =
                            store.query(
                                    "SELECT * FROM task WHERE user_id=? AND plan_id=? AND"
                                            + " status='PENDING' AND scheduled_start_at>=? AND"
                                            + " deleted_at IS NULL",
                                    uid(),
                                    planId,
                                    utc(request.effectiveFrom()));
                    Map<Long, Long> expected = expectedMap(request.affectedTasks());
                    Set<Long> actual =
                            candidates.stream()
                                    .map(row -> number(row, "id"))
                                    .collect(Collectors.toSet());
                    if (!expected.keySet().equals(actual))
                        throw BusinessException.conflict(
                                "ARCHIVE_SCOPE_CHANGED", "待取消任务集合已变化，请重新确认");
                    long no = number(plan, "latest_version_no") + 1;
                    long versionId =
                            createVersionRow(
                                    planId,
                                    no,
                                    nullableId(plan, "active_version_id"),
                                    "MANUAL",
                                    request.reason() == null ? "归档计划" : request.reason(),
                                    request.effectiveFrom(),
                                    constraintSnapshot(plan));
                    Set<LocalDate> dates = new HashSet<>();
                    for (Map<String, Object> row : currentTasks(planId).values()) {
                        long taskId = number(row, "id");
                        TaskSnapshotInput input = taskSnapshot(row);
                        boolean remove = actual.contains(taskId);
                        long snap =
                                saveSnapshot(planId, versionId, input, remove ? "REMOVE" : "KEEP");
                        if (remove) {
                            if (expected.get(taskId) != number(row, "row_version"))
                                throw BusinessException.conflict("TASK_VERSION_CHANGED", "任务版本已变化");
                            if (bool(row, "is_fixed")
                                    && (!Boolean.TRUE.equals(request.allowFixedTaskChanges())
                                            || request.fixedChangeReason() == null
                                            || request.fixedChangeReason().isBlank()))
                                throw invalid("FIXED_TASK_CONFIRMATION_REQUIRED", "归档固定任务需确认原因");
                            long revision =
                                    store.update(
                                            "task",
                                            taskId,
                                            number(row, "row_version"),
                                            map(
                                                    "status",
                                                    "CANCELLED",
                                                    "cancelled_at",
                                                    Instant.now(),
                                                    "current_plan_version_id",
                                                    versionId));
                            event(
                                    taskId,
                                    "CANCEL",
                                    number(row, "row_version"),
                                    revision,
                                    text(row, "status"),
                                    "CANCELLED",
                                    null,
                                    request.reason(),
                                    null,
                                    versionId,
                                    snap);
                            cancelReminders(taskId, number(row, "row_version"));
                            dates.add(date(row, "task_local_date"));
                            syncTask(taskId);
                        }
                    }
                    store.update(
                            "plan_version",
                            versionId,
                            1,
                            map(
                                    "status",
                                    "SEALED",
                                    "sealed_at",
                                    Instant.now(),
                                    "sealed_by_operation_id",
                                    RequestContext.idempotencyKey(),
                                    "content_digest",
                                    crypto.digest(actual),
                                    "affected_task_ids_json",
                                    actual.stream().map(String::valueOf).toList()));
                    long value =
                            store.update(
                                    "plan",
                                    planId,
                                    number(plan, "row_version"),
                                    map(
                                            "status",
                                            "ARCHIVED",
                                            "archived_at",
                                            Instant.now(),
                                            "active_version_id",
                                            versionId,
                                            "latest_version_no",
                                            no));
                    syncVersion(versionId);
                    syncPlan(planId);
                    invalidate("PLAN", planId, dates);
                    return MutationResult.of("PLAN", planId, value)
                            .withExtra(
                                    map(
                                            "planVersionId",
                                            Long.toString(versionId),
                                            "affectedTaskIds",
                                            actual.stream().map(String::valueOf).toList()));
                });
    }

    public PageResult<TaskView> listTasks(TaskQuery query) {
        validation.validate(query);
        RecordService.requireRange(query.from(), query.to(), 93);
        if (query.timeZone() != null) zone(query.timeZone());
        List<Object> args = new ArrayList<>(List.of(uid(), query.from(), query.to()));
        StringBuilder sql =
                new StringBuilder(
                        "SELECT * FROM task WHERE user_id=? AND task_local_date BETWEEN ? AND ? AND"
                                + " deleted_at IS NULL");
        if (query.planId() != null) {
            store.owned("plan", Ids.parse(query.planId()), uid());
            sql.append(" AND plan_id=?");
            args.add(Ids.parse(query.planId()));
        }
        if (query.status() != null && !query.status().isEmpty()) {
            sql.append(" AND status IN (")
                    .append(String.join(",", Collections.nCopies(query.status().size(), "?")))
                    .append(")");
            query.status().forEach(value -> args.add(value.name()));
        }
        String scope =
                "tasks:"
                        + query.from()
                        + ":"
                        + query.to()
                        + ":"
                        + query.planId()
                        + ":"
                        + query.status();
        long last = after(query.cursor(), scope);
        sql.append(" AND id>? ORDER BY id LIMIT ?");
        args.add(last);
        int count = limit(query.limit());
        args.add(count + 1);
        List<Map<String, Object>> rows = store.query(sql.toString(), args.toArray());
        boolean more = rows.size() > count;
        if (more) rows = rows.subList(0, count);
        return PageResult.of(
                rows.stream().map(this::taskView).toList(), next(rows, scope, more), more);
    }

    public List<TaskView> tasksOn(LocalDate day) {
        return store
                .query(
                        "SELECT * FROM task WHERE user_id=? AND task_local_date=? AND deleted_at IS"
                                + " NULL ORDER BY scheduled_start_at,id",
                        uid(),
                        day)
                .stream()
                .map(this::taskView)
                .toList();
    }

    public TaskView task(long id) {
        return taskView(store.owned("task", id, uid()));
    }

    public TaskDetailView taskDetail(long id) {
        Map<String, Object> row = store.owned("task", id, uid());
        List<Long> records =
                store
                        .query(
                                "SELECT x.record_id FROM task_record_link x JOIN health_record r ON"
                                    + " r.id=x.record_id AND r.user_id=x.user_id WHERE x.user_id=?"
                                    + " AND x.task_id=? AND r.deleted_at IS NULL ORDER BY"
                                    + " x.record_id",
                                uid(),
                                id)
                        .stream()
                        .map(r -> number(r, "record_id"))
                        .toList();
        List<Map<String, Object>> rules =
                store
                        .query(
                                "SELECT id,row_version,is_enabled,channel,offset_minutes,time_zone"
                                        + " FROM reminder_rule WHERE user_id=? AND task_id=? AND"
                                        + " deleted_at IS NULL",
                                uid(),
                                id)
                        .stream()
                        .map(
                                r ->
                                        map(
                                                "id",
                                                Long.toString(number(r, "id")),
                                                "rowVersion",
                                                Long.toString(number(r, "row_version")),
                                                "enabled",
                                                bool(r, "is_enabled"),
                                                "channel",
                                                text(r, "channel"),
                                                "offsetMinutes",
                                                r.get("offset_minutes"),
                                                "timeZone",
                                                text(r, "time_zone")))
                        .toList();
        TaskEventView last =
                store.find(
                                "SELECT * FROM task_event WHERE user_id=? AND task_id=? ORDER BY id"
                                        + " DESC LIMIT 1",
                                uid(),
                                id)
                        .map(this::eventView)
                        .orElse(null);
        return new TaskDetailView(
                taskView(row), crypto.decrypt(row.get("instruction_cipher")), records, rules, last);
    }

    public MutationResult completeTask(long id, TaskStateCommand request) {
        return state(id, request, "COMPLETED", "COMPLETE");
    }

    public MutationResult skipTask(long id, TaskStateCommand request) {
        return state(id, request, "SKIPPED", "SKIP");
    }

    private MutationResult state(
            long id, TaskStateCommand request, String status, String eventType) {
        validation.validate(request);
        return mutations.mutate(
                "TASK_" + eventType,
                map("id", id, "request", request),
                true,
                () -> {
                    Map<String, Object> row = store.lockOwned("task", id, uid());
                    if (!"PENDING".equals(text(row, "status")))
                        throw BusinessException.conflict("TASK_STATE_CONFLICT", "只有待执行任务可以完成或跳过");
                    long version =
                            store.update(
                                    "task",
                                    id,
                                    Ids.parse(request.expectedRowVersion()),
                                    map(
                                            "status",
                                            status,
                                            "completed_at",
                                            "COMPLETED".equals(status) ? Instant.now() : null));
                    Map<String, Object> snapshot = executionSnapshot(row);
                    event(
                            id,
                            eventType,
                            number(row, "row_version"),
                            version,
                            "PENDING",
                            status,
                            request.reasonCode(),
                            request.reasonText(),
                            request.performedAt(),
                            number(snapshot, "version_id"),
                            number(snapshot, "id"));
                    cancelReminders(id, number(row, "row_version"));
                    syncTask(id);
                    invalidate("TASK", id, Set.of(date(row, "task_local_date")));
                    return MutationResult.of("TASK", id, version);
                });
    }

    public MutationResult reopenTask(long id, TaskReopen request) {
        validation.validate(request);
        return mutations.mutate(
                "TASK_REOPEN",
                map("id", id, "request", request),
                true,
                () -> {
                    Map<String, Object> row = store.lockOwned("task", id, uid());
                    if (!Set.of("COMPLETED", "SKIPPED").contains(text(row, "status")))
                        throw BusinessException.conflict("TASK_STATE_CONFLICT", "只能重新打开已完成或跳过任务");
                    long value =
                            store.update(
                                    "task",
                                    id,
                                    Ids.parse(request.expectedRowVersion()),
                                    map("status", "PENDING", "completed_at", null));
                    Map<String, Object> snapshot = executionSnapshot(row);
                    event(
                            id,
                            "REOPEN",
                            number(row, "row_version"),
                            value,
                            text(row, "status"),
                            "PENDING",
                            null,
                            request.reason(),
                            null,
                            number(snapshot, "version_id"),
                            number(snapshot, "id"));
                    for (Map<String, Object> rule :
                            store.query(
                                    "SELECT id,row_version FROM reminder_rule WHERE user_id=? AND"
                                            + " task_id=? AND is_enabled=1 AND deleted_at IS NULL",
                                    uid(),
                                    id)) {
                        mutations.outbox(
                                "REMINDER_RULE_CHANGED",
                                "REMINDER_RULE",
                                number(rule, "id"),
                                number(rule, "row_version"),
                                map(
                                        "taskId",
                                        Long.toString(id),
                                        "ruleId",
                                        Long.toString(number(rule, "id"))));
                    }
                    syncTask(id);
                    invalidate("TASK", id, Set.of(date(row, "task_local_date")));
                    return MutationResult.of("TASK", id, value);
                });
    }

    public PageResult<TaskEventView> events(long id, String cursor, Integer requestedLimit) {
        store.owned("task", id, uid());
        String scope = "task-events:" + id;
        long before = after(cursor, scope);
        int count = limit(requestedLimit);
        List<Map<String, Object>> rows =
                store.query(
                        "SELECT * FROM task_event WHERE user_id=? AND task_id=? AND (?=0 OR id<?)"
                                + " ORDER BY id DESC LIMIT ?",
                        uid(),
                        id,
                        before,
                        before,
                        count + 1);
        boolean more = rows.size() > count;
        if (more) rows = rows.subList(0, count);
        return PageResult.of(
                rows.stream().map(this::eventView).toList(), next(rows, scope, more), more);
    }

    public MutationResult recordLinks(long id, TaskRecordLinksPut request) {
        validation.validate(request);
        return mutations.mutate(
                "TASK_RECORD_LINKS_REPLACE",
                map("id", id, "request", request),
                true,
                () -> {
                    Map<String, Object> task = store.lockOwned("task", id, uid());
                    Set<Long> ids = idSet(request.recordIds(), 20);
                    for (long record : ids) {
                        Map<String, Object> row = store.owned("health_record", record, uid());
                        if (!"VALID".equals(text(row, "quality_status")))
                            throw invalid("INVALID_RECORD_LINK", "无效记录不能作为任务事实关联");
                        String type = text(row, "record_type"), taskType = text(task, "task_type");
                        if (("ACTIVITY".equals(taskType) && !"ACTIVITY".equals(type))
                                || ("MEAL_HABIT".equals(taskType) && !"MEAL".equals(type)))
                            throw invalid("RECORD_TASK_TYPE_MISMATCH", "实际记录与任务语义不兼容");
                    }
                    store.execute(
                            "DELETE FROM task_record_link WHERE user_id=? AND task_id=?",
                            uid(),
                            id);
                    for (long record : ids)
                        store.insert(
                                "task_record_link",
                                map(
                                        "user_id",
                                        uid(),
                                        "task_id",
                                        id,
                                        "record_id",
                                        record,
                                        "link_type",
                                        "USER_CONFIRMED",
                                        "operation_id",
                                        RequestContext.idempotencyKey(),
                                        "linked_at",
                                        Instant.now()));
                    long value =
                            store.update(
                                    "task", id, Ids.parse(request.expectedRowVersion()), Map.of());
                    syncTask(id);
                    return MutationResult.of("TASK", id, value);
                });
    }

    public MutationResult ruleCommand(long planId, PlanRuleCommand request) {
        validation.validate(request);
        RecordService.requireRange(request.effectiveFrom(), request.effectiveTo(), 31);
        if ("CREATE".equals(request.action())
                ? (request.ruleId() != null || request.rule() == null)
                : (request.ruleId() == null || request.expectedRuleRowVersion() == null))
            throw invalid("INVALID_RULE_COMMAND", "循环命令字段不完整");
        if (!"STOP".equals(request.action()) && request.rule() == null)
            throw invalid("RULE_REQUIRED", "请提供循环模板");
        return mutations.mutate(
                "PLAN_RULE_COMMAND",
                map("planId", planId, "request", request),
                true,
                () -> {
                    Map<String, Object> plan = store.lockOwned("plan", planId, uid());
                    expectActive(plan, request.expectedActiveVersionId());
                    expectVersion(plan, request.expectedPlanRowVersion());
                    long ruleId;
                    PlanRuleInput input = request.rule();
                    if (input != null) validateRule(input);
                    if ("CREATE".equals(request.action()))
                        ruleId = store.insert("plan_rule", ruleValues(planId, input, request));
                    else {
                        ruleId = Ids.parse(request.ruleId());
                        Map<String, Object> old = store.lockOwned("plan_rule", ruleId, uid());
                        if (number(old, "plan_id") != planId) throw BusinessException.notFound();
                        Map<String, Object> values =
                                "STOP".equals(request.action())
                                        ? map("status", "PAUSED")
                                        : ruleValues(planId, input, request);
                        values.remove("user_id");
                        values.remove("plan_id");
                        values.remove("rule_key");
                        values.put("template_revision", number(old, "template_revision") + 1);
                        store.update(
                                "plan_rule",
                                ruleId,
                                Ids.parse(request.expectedRuleRowVersion()),
                                values);
                    }
                    List<TaskSnapshotInput> tasks = new ArrayList<>();
                    for (Map<String, Object> task : currentTasks(planId).values()) {
                        boolean matching =
                                number(task, "rule_id") == ruleId
                                        && !date(task, "task_local_date")
                                                .isBefore(request.effectiveFrom())
                                        && !date(task, "task_local_date")
                                                .isAfter(request.effectiveTo())
                                        && !Set.of("COMPLETED", "SKIPPED")
                                                .contains(text(task, "status"));
                        if (!matching && !"CANCELLED".equals(text(task, "status")))
                            tasks.add(taskSnapshot(task));
                    }
                    if (!"STOP".equals(request.action())) {
                        for (LocalDate day :
                                request.effectiveFrom()
                                        .datesUntil(request.effectiveTo().plusDays(1))
                                        .toList())
                            if (input.weekdays().contains(day.getDayOfWeek().getValue())) {
                                Instant start =
                                        resolveLocal(
                                                day, input.startTime(), zone(input.timeZone()));
                                Instant end =
                                        resolveLocal(
                                                day.plusDays(
                                                        input.endDayOffset() == null
                                                                ? 0
                                                                : input.endDayOffset()),
                                                input.endTime(),
                                                zone(input.timeZone()));
                                Map<String, Object> existing =
                                        store.find(
                                                        "SELECT * FROM task WHERE user_id=? AND"
                                                            + " rule_id=? AND occurrence_key=? AND"
                                                            + " deleted_at IS NULL",
                                                        uid(),
                                                        ruleId,
                                                        day.toString())
                                                .orElse(null);
                                if (existing != null
                                        && Set.of("COMPLETED", "SKIPPED")
                                                .contains(text(existing, "status"))) continue;
                                tasks.add(
                                        new TaskSnapshotInput(
                                                UUID.randomUUID(),
                                                existing == null
                                                        ? null
                                                        : Long.toString(number(existing, "id")),
                                                existing == null
                                                        ? null
                                                        : Long.toString(
                                                                number(existing, "row_version")),
                                                input.title(),
                                                input.taskType(),
                                                start,
                                                end,
                                                input.plannedDurationMinutes(),
                                                input.timeZone(),
                                                input.isFixed(),
                                                3,
                                                tasks.size(),
                                                input.instructions(),
                                                input.reminder(),
                                                Long.toString(ruleId),
                                                day.toString()));
                            }
                    }
                    long no = number(plan, "latest_version_no") + 1;
                    store.update(
                            "plan",
                            planId,
                            number(plan, "row_version"),
                            map("latest_version_no", no));
                    long version =
                            createVersionRow(
                                    planId,
                                    no,
                                    nullableId(plan, "active_version_id"),
                                    "MANUAL",
                                    request.reason(),
                                    request.effectiveFrom()
                                            .atStartOfDay(zone(text(plan, "plan_time_zone")))
                                            .toInstant(),
                                    constraintSnapshot(plan));
                    saveSnapshots(planId, version, tasks);
                    syncVersion(version);
                    syncPlan(planId);
                    return MutationResult.of("PLAN_VERSION", version, 1)
                            .withExtra(
                                    map(
                                            "ruleId",
                                            Long.toString(ruleId),
                                            "planVersionId",
                                            Long.toString(version),
                                            "requiresActivation",
                                            true));
                });
    }

    public MutationResult applyAiCreate(
            PlanCreate request, List<TaskSnapshotInput> tasks, ApplyScope scope) {
        return mutations.mutate(
                "AI_PLAN_CREATE",
                map("plan", request, "tasks", tasks, "scope", scope),
                true,
                () -> {
                    assertAiScope(scope);
                    MutationResult created = createPlan(request, "AI_CONFIRMED");
                    long planId = Ids.parse(created.id());
                    long versionId =
                            Long.parseLong(created.extra().get("planVersionId").toString());
                    replaceVersion(
                            planId,
                            versionId,
                            new PlanVersionReplace("1", null, "AI草稿经用户确认", tasks));
                    return activate(
                            planId,
                            versionId,
                            new PlanActivate(
                                    "2",
                                    null,
                                    scope.effectiveFrom(),
                                    List.of(),
                                    scope.allowFixedTaskChanges(),
                                    scope.fixedChangeReason(),
                                    List.of(),
                                    Long.toString(mutations.currentSeq()),
                                    true));
                });
    }

    public MutationResult applyAiAdjustment(
            String planIdText,
            String expectedActiveVersionId,
            List<DraftTaskChange> changes,
            ApplyScope scope) {
        return mutations.mutate(
                "AI_PLAN_ADJUST",
                map(
                        "planId",
                        planIdText,
                        "base",
                        expectedActiveVersionId,
                        "changes",
                        changes,
                        "scope",
                        scope),
                true,
                () -> {
                    assertAiScope(scope);
                    long planId = Ids.parse(planIdText);
                    Map<String, Object> plan = store.lockOwned("plan", planId, uid());
                    expectActive(plan, expectedActiveVersionId);
                    Map<Long, TaskSnapshotInput> existing = new LinkedHashMap<>();
                    for (Map<String, Object> row : currentTasks(planId).values())
                        if (!"CANCELLED".equals(text(row, "status")))
                            existing.put(number(row, "id"), taskSnapshot(row));
                    List<TaskSnapshotInput> additions = new ArrayList<>();
                    for (DraftTaskChange change : changes) {
                        if ("ADD".equals(change.action())) {
                            if (change.snapshot() == null || change.snapshot().taskId() != null)
                                throw invalid("INVALID_AI_CHANGE", "新增必须是无taskId的完整快照");
                            additions.add(change.snapshot());
                        } else {
                            long task = Ids.parse(change.taskId());
                            Map<String, Object> row = store.owned("task", task, uid());
                            if (number(row, "plan_id") != planId)
                                throw BusinessException.notFound();
                            expectVersion(row, change.expectedTaskRowVersion());
                            if ("REMOVE".equals(change.action())) existing.remove(task);
                            else if ("UPDATE".equals(change.action()) && change.snapshot() != null)
                                existing.put(
                                        task,
                                        withTask(
                                                change.snapshot(),
                                                task,
                                                number(row, "row_version")));
                            else throw invalid("INVALID_AI_CHANGE", "调整动作不支持或快照缺失");
                        }
                    }
                    List<TaskSnapshotInput> tasks = new ArrayList<>(existing.values());
                    tasks.addAll(additions);
                    MutationResult draft =
                            createVersion(
                                    planId,
                                    new PlanVersionCreate(
                                            Long.toString(number(plan, "row_version")),
                                            expectedActiveVersionId,
                                            null,
                                            "AI调整经用户确认",
                                            tasks));
                    long versionId = Ids.parse(draft.id());
                    store.execute(
                            "UPDATE plan_version SET source_type='AI_CONFIRMED' WHERE id=?",
                            versionId);
                    List<TaskVersionRef> refs =
                            scope.affectedTasks() == null ? List.of() : scope.affectedTasks();
                    return activate(
                            planId,
                            versionId,
                            new PlanActivate(
                                    "1",
                                    expectedActiveVersionId,
                                    scope.effectiveFrom(),
                                    refs.stream().map(TaskVersionRef::taskId).toList(),
                                    scope.allowFixedTaskChanges(),
                                    scope.fixedChangeReason(),
                                    refs,
                                    Long.toString(mutations.currentSeq()),
                                    true));
                });
    }

    private void assertAiScope(ApplyScope scope) {
        if (scope == null
                || scope.effectiveFrom() == null
                || Ids.nonNegative(scope.expectedDataSeq()) != mutations.currentSeq())
            throw BusinessException.conflict("PREVIEW_STALE", "草稿依据已变化，请重新预览");
    }

    private long createVersionRow(
            long plan,
            long no,
            Long base,
            String source,
            String reason,
            Instant effective,
            Map<String, Object> constraints) {
        return store.insert(
                "plan_version",
                map(
                        "user_id",
                        uid(),
                        "plan_id",
                        plan,
                        "version_no",
                        no,
                        "status",
                        "DRAFT",
                        "based_on_version_id",
                        base,
                        "source_type",
                        source,
                        "effective_from",
                        effective,
                        "affected_task_ids_json",
                        List.of(),
                        "change_summary",
                        reason,
                        "constraint_snapshot_json",
                        constraints));
    }

    private void saveSnapshots(long plan, long version, List<TaskSnapshotInput> tasks) {
        validateShapes(plan, tasks);
        for (TaskSnapshotInput task : tasks)
            saveSnapshot(plan, version, task, task.taskId() == null ? "ADD" : "KEEP");
    }

    private long saveSnapshot(long plan, long version, TaskSnapshotInput input, String action) {
        return store.insert(
                "plan_version_task",
                map(
                        "user_id",
                        uid(),
                        "plan_id",
                        plan,
                        "version_id",
                        version,
                        "task_id",
                        Ids.optional(input.taskId()),
                        "draft_task_key",
                        input.draftTaskKey().toString(),
                        "sort_order",
                        input.sortOrder(),
                        "title",
                        input.title(),
                        "task_type",
                        input.taskType().name(),
                        "scheduled_start_at",
                        input.scheduledStartAt(),
                        "scheduled_end_at",
                        input.scheduledEndAt(),
                        "planned_duration_minutes",
                        input.plannedDurationMinutes(),
                        "task_time_zone",
                        input.taskTimeZone(),
                        "task_local_date",
                        input.scheduledStartAt().atZone(zone(input.taskTimeZone())).toLocalDate(),
                        "is_fixed",
                        Boolean.TRUE.equals(input.isFixed()),
                        "priority",
                        input.priority() == null ? 3 : input.priority(),
                        "instruction_cipher",
                        crypto.encrypt(input.instructions()),
                        "encryption_key_ref",
                        input.instructions() == null ? null : crypto.keyRef(),
                        "reminder_snapshot_json",
                        input.reminder() == null ? Map.of() : mapper.valueToTree(input.reminder()),
                        "change_action",
                        action,
                        "rule_id",
                        Ids.optional(input.ruleId()),
                        "occurrence_key",
                        input.occurrenceKey()));
    }

    private Map<String, Object> taskValues(
            long plan, long version, TaskSnapshotInput input, String source) {
        return map(
                "user_id",
                uid(),
                "plan_id",
                plan,
                "current_plan_version_id",
                version,
                "rule_id",
                Ids.optional(input.ruleId()),
                "occurrence_key",
                input.occurrenceKey(),
                "title",
                input.title(),
                "task_type",
                input.taskType().name(),
                "scheduled_start_at",
                input.scheduledStartAt(),
                "scheduled_end_at",
                input.scheduledEndAt(),
                "task_local_date",
                input.scheduledStartAt().atZone(zone(input.taskTimeZone())).toLocalDate(),
                "task_time_zone",
                input.taskTimeZone(),
                "planned_duration_minutes",
                input.plannedDurationMinutes(),
                "status",
                "PENDING",
                "priority",
                input.priority() == null ? 3 : input.priority(),
                "is_fixed",
                Boolean.TRUE.equals(input.isFixed()),
                "instruction_cipher",
                crypto.encrypt(input.instructions()),
                "encryption_key_ref",
                input.instructions() == null ? null : crypto.keyRef(),
                "completed_at",
                null,
                "cancelled_at",
                null,
                "source_type",
                input.ruleId() != null
                        ? "RULE_EXPANDED"
                        : "AI_CONFIRMED".equals(source) ? "AI_CONFIRMED" : "MANUAL");
    }

    private void validateShapes(long planId, List<TaskSnapshotInput> tasks) {
        if (tasks.size() > 500) throw invalid("TOO_MANY_TASKS", "单版本任务过多");
        Set<UUID> keys = new HashSet<>();
        Set<Long> ids = new HashSet<>();
        for (TaskSnapshotInput input : tasks) {
            validation.validate(input);
            validateTaskTime(input);
            if (!keys.add(input.draftTaskKey()))
                throw invalid("DUPLICATE_DRAFT_TASK_KEY", "版本内草稿编号不能重复");
            if (input.taskId() != null) {
                long id = Ids.parse(input.taskId());
                if (!ids.add(id) || input.expectedTaskRowVersion() == null)
                    throw invalid("DUPLICATE_TASK_OR_VERSION_MISSING", "同一任务重复或缺少源版本");
                Map<String, Object> row = store.owned("task", id, uid());
                if (number(row, "plan_id") != planId) throw BusinessException.notFound();
            }
            if ((input.ruleId() == null) != (input.occurrenceKey() == null))
                throw invalid("RULE_OCCURRENCE_PAIR_REQUIRED", "循环规则和发生键必须同时提供");
            if (input.ruleId() != null
                    && number(store.owned("plan_rule", Ids.parse(input.ruleId()), uid()), "plan_id")
                            != planId) throw BusinessException.notFound();
        }
    }

    public static void validateTaskTime(TaskSnapshotInput input) {
        zone(input.taskTimeZone());
        long minutes =
                Duration.between(input.scheduledStartAt(), input.scheduledEndAt()).toMinutes();
        if (minutes < 1 || minutes > 1440 || input.plannedDurationMinutes() > minutes)
            throw invalid("INVALID_TASK_INTERVAL", "计划时长须在有效起止窗口内");
        if (input.reminder() != null) {
            zone(input.reminder().timeZone());
            if (input.reminder().offsetMinutes() != null
                    && (input.reminder().offsetMinutes() > 0
                            || input.reminder().offsetMinutes() < -1440))
                throw invalid("INVALID_REMINDER_OFFSET", "提醒只能提前一天内或准时");
        }
    }

    private void checkAvailability(
            TaskSnapshotInput task, String path, List<ValidationIssue> issues) {
        ZoneId zone = zone(task.taskTimeZone());
        LocalDate day = task.scheduledStartAt().atZone(zone).toLocalDate();
        List<Instant[]> available = new ArrayList<>();
        Instant dayStart = day.atStartOfDay(zone).toInstant(),
                dayEnd =
                        task.scheduledEndAt()
                                .atZone(zone)
                                .toLocalDate()
                                .plusDays(1)
                                .atStartOfDay(zone)
                                .toInstant();
        // Read adjacent origins so a previous day's cross-midnight constraint is not lost.
        for (LocalDate origin = day.minusDays(1);
                !origin.isAfter(task.scheduledEndAt().atZone(zone).toLocalDate().plusDays(1));
                origin = origin.plusDays(1)) {
            Optional<Map<String, Object>> exception =
                    store.find(
                            "SELECT * FROM availability_exception WHERE user_id=? AND"
                                    + " exception_date=? AND is_enabled=1 AND deleted_at IS NULL",
                            uid(),
                            origin);
            boolean replace =
                    exception.isPresent() && "REPLACE_DAY".equals(text(exception.get(), "mode"));
            if (!replace) {
                List<Map<String, Object>> rules =
                        store.query(
                                "SELECT * FROM availability_rule WHERE user_id=? AND weekday=? AND"
                                    + " is_enabled=1 AND deleted_at IS NULL AND valid_from<=? AND"
                                    + " (valid_until IS NULL OR valid_until>=?)",
                                uid(),
                                origin.getDayOfWeek().getValue(),
                                origin,
                                origin);
                for (Map<String, Object> rule : rules) {
                    String kind = text(rule, "rule_kind");
                    ZoneId ruleZone = zone(text(rule, "time_zone"));
                    Instant start =
                            resolveLocal(
                                    origin,
                                    LocalTime.ofSecondOfDay(60L * integer(rule, "start_minute")),
                                    ruleZone);
                    Instant end =
                            resolveLocal(
                                    origin.plusDays(integer(rule, "end_day_offset")),
                                    LocalTime.ofSecondOfDay(60L * integer(rule, "end_minute")),
                                    ruleZone);
                    if ("AVAILABLE".equals(kind)) {
                        if (overlaps(dayStart, dayEnd, start, end))
                            available.add(new Instant[] {start, end});
                    } else if (overlaps(task.scheduledStartAt(), task.scheduledEndAt(), start, end))
                        issues.add(
                                issue(
                                        "AVAILABILITY_CONFLICT",
                                        path,
                                        "任务与忙碌或保留休息时间冲突",
                                        task.taskId()));
                }
            }
            if (exception.isPresent()) {
                JsonNode slots = json(exception.get(), "slots_json");
                ZoneId exceptionZone = zone(text(exception.get(), "time_zone"));
                for (JsonNode slot : slots) {
                    Instant start =
                            resolveSlot(
                                    origin,
                                    LocalTime.parse(slot.path("startTime").asText()),
                                    exceptionZone,
                                    slot.get("startOffsetMinutes"));
                    Instant end =
                            resolveSlot(
                                    origin.plusDays(slot.path("endDayOffset").asInt(0)),
                                    LocalTime.parse(slot.path("endTime").asText()),
                                    exceptionZone,
                                    slot.get("endOffsetMinutes"));
                    if ("AVAILABLE".equals(slot.path("kind").asText())) {
                        if (overlaps(dayStart, dayEnd, start, end))
                            available.add(new Instant[] {start, end});
                    } else if (overlaps(task.scheduledStartAt(), task.scheduledEndAt(), start, end))
                        issues.add(
                                issue(
                                        "DATE_EXCEPTION_CONFLICT",
                                        path,
                                        "任务与日期例外安排冲突",
                                        task.taskId()));
                }
            }
        }
        if (!available.isEmpty()) {
            available.sort(Comparator.comparing(window -> window[0]));
            Instant coveredUntil = task.scheduledStartAt();
            for (Instant[] window : available)
                if (!window[0].isAfter(coveredUntil) && window[1].isAfter(coveredUntil))
                    coveredUntil = window[1];
            if (coveredUntil.isBefore(task.scheduledEndAt()))
                issues.add(
                        issue(
                                "OUTSIDE_AVAILABLE_WINDOW",
                                path,
                                "任务未完整落在已明确设置的可用时间内",
                                task.taskId()));
        }
    }

    private void event(
            long task,
            String event,
            long before,
            long after,
            String oldStatus,
            String newStatus,
            String reasonCode,
            String reason,
            Instant performed,
            long version,
            long snapshot) {
        String source = "MOBILE";
        if (Set.of("CREATE", "EDIT", "RESCHEDULE", "CANCEL").contains(event)
                && "AI_CONFIRMED"
                        .equals(text(store.owned("plan_version", version, uid()), "source_type"))) {
            source = "AI_APPLY";
        }
        store.insert(
                "task_event",
                map(
                        "user_id",
                        uid(),
                        "task_id",
                        task,
                        "event_type",
                        event,
                        "operation_id",
                        RequestContext.idempotencyKey(),
                        "source_type",
                        source,
                        "before_row_version",
                        before,
                        "after_row_version",
                        after,
                        "occurred_at",
                        Instant.now(),
                        "client_occurred_at",
                        performed,
                        "old_status",
                        oldStatus,
                        "new_status",
                        newStatus,
                        "reason_code",
                        reasonCode,
                        "reason_cipher",
                        crypto.encrypt(reason),
                        "encryption_key_ref",
                        reason == null ? null : crypto.keyRef(),
                        "plan_version_id",
                        version,
                        "plan_version_task_id",
                        snapshot));
    }

    private void cancelReminders(long task, long oldVersion) {
        store.execute(
                "UPDATE reminder_delivery SET"
                    + " status='CANCEL_REQUESTED',updated_at=?,row_version=row_version+1 WHERE"
                    + " user_id=? AND task_id=? AND status IN ('PENDING','REGISTERED','UNKNOWN')",
                utc(Instant.now()),
                uid(),
                task);
        mutations.outbox(
                "REMINDER_CANCEL", "TASK", task, oldVersion, map("taskId", Long.toString(task)));
    }

    private void applyReminder(long task, TaskSnapshotInput input) {
        List<Map<String, Object>> disabled =
                store.query(
                        "SELECT id,row_version FROM reminder_rule WHERE user_id=? AND task_id=? AND"
                                + " is_enabled=1 AND deleted_at IS NULL",
                        uid(),
                        task);
        store.execute(
                "UPDATE reminder_rule SET is_enabled=0,updated_at=?,row_version=row_version+1 WHERE"
                        + " user_id=? AND task_id=? AND is_enabled=1 AND deleted_at IS NULL",
                utc(Instant.now()),
                uid(),
                task);
        for (Map<String, Object> row : disabled)
            mutations.upsert(
                    "REMINDER_RULE",
                    number(row, "id"),
                    number(row, "row_version") + 1,
                    map(
                            "id",
                            Long.toString(number(row, "id")),
                            "rowVersion",
                            Long.toString(number(row, "row_version") + 1),
                            "taskId",
                            Long.toString(task),
                            "enabled",
                            false));
        ReminderSnapshotInput reminder = input.reminder();
        if (reminder == null || !Boolean.TRUE.equals(reminder.enabled())) return;
        long rule =
                store.insert(
                        "reminder_rule",
                        map(
                                "user_id",
                                uid(),
                                "rule_key",
                                UUID.randomUUID().toString(),
                                "task_id",
                                task,
                                "rule_type",
                                "TASK_START",
                                "channel",
                                reminder.channel() == null ? "IN_APP" : reminder.channel(),
                                "target_installation_id",
                                AuthContext.current().installationId(),
                                "trigger_kind",
                                "OFFSET",
                                "offset_minutes",
                                reminder.offsetMinutes() == null ? 0 : reminder.offsetMinutes(),
                                "time_zone",
                                reminder.timeZone(),
                                "is_enabled",
                                1,
                                "respect_quiet_hours",
                                1,
                                "quiet_policy",
                                reminder.quietPolicy() == null
                                        ? "SUPPRESS"
                                        : reminder.quietPolicy(),
                                "valid_from",
                                input.scheduledStartAt()
                                        .atZone(zone(input.taskTimeZone()))
                                        .toLocalDate()));
        mutations.upsert(
                "REMINDER_RULE",
                rule,
                1,
                map(
                        "id",
                        Long.toString(rule),
                        "rowVersion",
                        "1",
                        "taskId",
                        Long.toString(task),
                        "enabled",
                        true,
                        "channel",
                        reminder.channel() == null ? "IN_APP" : reminder.channel()));
        // P0 saves intent only; the verified system adapter consumes this event when enabled.
        mutations.outbox(
                "REMINDER_RULE_CHANGED",
                "REMINDER_RULE",
                rule,
                1L,
                map("taskId", Long.toString(task), "ruleId", Long.toString(rule)));
    }

    private void invalidate(String type, long id, Set<LocalDate> dates) {
        for (LocalDate date : dates) {
            store.execute(
                    "UPDATE daily_stat SET status='STALE',updated_at=? WHERE user_id=? AND"
                            + " stat_local_date=?",
                    utc(Instant.now()),
                    uid(),
                    date);
            store.execute(
                    "UPDATE weekly_review SET status='STALE',updated_at=? WHERE user_id=? AND"
                            + " week_start_date<=? AND week_end_date>=? AND deleted_at IS NULL",
                    utc(Instant.now()),
                    uid(),
                    date,
                    date);
        }
        if (!dates.isEmpty())
            mutations.outbox(
                    "STAT_INVALIDATED",
                    type,
                    id,
                    null,
                    map("dates", dates.stream().map(LocalDate::toString).sorted().toList()));
    }

    private Map<String, Object> executionSnapshot(Map<String, Object> task) {
        return store.one(
                "SELECT s.* FROM plan_version_task s JOIN plan_version v ON v.id=s.version_id AND"
                    + " v.user_id=s.user_id WHERE s.user_id=? AND s.plan_id=? AND s.task_id=? AND"
                    + " s.version_id=? AND v.status='SEALED'",
                uid(),
                number(task, "plan_id"),
                number(task, "id"),
                number(task, "current_plan_version_id"));
    }

    private Map<String, Object> version(long planId, long versionId) {
        Map<String, Object> row = store.owned("plan_version", versionId, uid());
        if (number(row, "plan_id") != planId) throw BusinessException.notFound();
        return row;
    }

    private List<Map<String, Object>> snapshotRows(long versionId) {
        return store.query(
                "SELECT * FROM plan_version_task WHERE user_id=? AND version_id=? AND deleted_at IS"
                        + " NULL ORDER BY sort_order,id",
                uid(),
                versionId);
    }

    private Map<Long, Map<String, Object>> currentTasks(long planId) {
        return store
                .query(
                        "SELECT * FROM task WHERE user_id=? AND plan_id=? AND deleted_at IS NULL"
                                + " ORDER BY id",
                        uid(),
                        planId)
                .stream()
                .collect(
                        Collectors.toMap(
                                row -> number(row, "id"),
                                Function.identity(),
                                (a, b) -> a,
                                LinkedHashMap::new));
    }

    private Map<String, Object> constraintSnapshot(Map<String, Object> plan) {
        Long active = nullableId(plan, "active_version_id");
        Map<String, Object> source =
                active == null
                        ? store.find(
                                        "SELECT * FROM plan_version WHERE user_id=? AND plan_id=?"
                                            + " AND status='DRAFT' AND deleted_at IS NULL ORDER BY"
                                            + " version_no DESC LIMIT 1",
                                        uid(),
                                        number(plan, "id"))
                                .orElse(null)
                        : version(number(plan, "id"), active);
        if (source == null) return map("goalVersions", List.of());
        JsonNode constraints = json(source, "constraint_snapshot_json");
        return constraints == null
                ? Map.of()
                : mapper.convertValue(constraints, new TypeReference<>() {});
    }

    private TaskSnapshotInput snapshot(Map<String, Object> row) {
        ReminderSnapshotInput reminder = null;
        JsonNode raw = json(row, "reminder_snapshot_json");
        if (raw != null && raw.has("enabled"))
            reminder = mapper.convertValue(raw, ReminderSnapshotInput.class);
        Long task = nullableId(row, "task_id");
        Map<String, Object> current = task == null ? null : store.owned("task", task, uid());
        return new TaskSnapshotInput(
                UUID.fromString(text(row, "draft_task_key")),
                task == null ? null : Long.toString(task),
                current == null ? null : Long.toString(number(current, "row_version")),
                text(row, "title"),
                TaskType.valueOf(text(row, "task_type")),
                instant(row, "scheduled_start_at"),
                instant(row, "scheduled_end_at"),
                integer(row, "planned_duration_minutes"),
                text(row, "task_time_zone"),
                bool(row, "is_fixed"),
                integer(row, "priority"),
                integer(row, "sort_order"),
                crypto.decrypt(row.get("instruction_cipher")),
                reminder,
                row.get("rule_id") == null ? null : Long.toString(number(row, "rule_id")),
                text(row, "occurrence_key"));
    }

    private TaskSnapshotInput taskSnapshot(Map<String, Object> task) {
        Map<String, Object> historical =
                store.find(
                                "SELECT * FROM plan_version_task WHERE user_id=? AND task_id=? AND"
                                        + " version_id=?",
                                uid(),
                                number(task, "id"),
                                number(task, "current_plan_version_id"))
                        .orElse(null);
        if (historical != null) return snapshot(historical);
        return new TaskSnapshotInput(
                UUID.randomUUID(),
                Long.toString(number(task, "id")),
                Long.toString(number(task, "row_version")),
                text(task, "title"),
                TaskType.valueOf(text(task, "task_type")),
                instant(task, "scheduled_start_at"),
                instant(task, "scheduled_end_at"),
                integer(task, "planned_duration_minutes"),
                text(task, "task_time_zone"),
                bool(task, "is_fixed"),
                integer(task, "priority"),
                0,
                crypto.decrypt(task.get("instruction_cipher")),
                null,
                task.get("rule_id") == null ? null : Long.toString(number(task, "rule_id")),
                text(task, "occurrence_key"));
    }

    private TaskSnapshotInput withTask(TaskSnapshotInput input, long task, long version) {
        return new TaskSnapshotInput(
                input.draftTaskKey(),
                Long.toString(task),
                Long.toString(version),
                input.title(),
                input.taskType(),
                input.scheduledStartAt(),
                input.scheduledEndAt(),
                input.plannedDurationMinutes(),
                input.taskTimeZone(),
                input.isFixed(),
                input.priority(),
                input.sortOrder(),
                input.instructions(),
                input.reminder(),
                input.ruleId(),
                input.occurrenceKey());
    }

    private boolean different(TaskSnapshotInput input, Map<String, Object> task) {
        boolean columnsChanged =
                !input.title().equals(text(task, "title"))
                        || !input.taskType().name().equals(text(task, "task_type"))
                        || !input.scheduledStartAt().equals(instant(task, "scheduled_start_at"))
                        || !input.scheduledEndAt().equals(instant(task, "scheduled_end_at"))
                        || input.plannedDurationMinutes()
                                != integer(task, "planned_duration_minutes")
                        || !input.taskTimeZone().equals(text(task, "task_time_zone"))
                        || Boolean.TRUE.equals(input.isFixed()) != bool(task, "is_fixed")
                        || (input.priority() == null ? 3 : input.priority())
                                != integer(task, "priority")
                        || !Objects.equals(
                                input.instructions(),
                                crypto.decrypt(task.get("instruction_cipher")))
                        || !Objects.equals(
                                input.ruleId(),
                                task.get("rule_id") == null
                                        ? null
                                        : Long.toString(number(task, "rule_id")))
                        || !Objects.equals(input.occurrenceKey(), text(task, "occurrence_key"));
        if (columnsChanged) return true;
        TaskSnapshotInput old = taskSnapshot(task);
        return !Objects.equals(
                normalizedReminder(input.reminder()), normalizedReminder(old.reminder()));
    }

    private Map<String, Object> normalizedReminder(ReminderSnapshotInput reminder) {
        if (reminder == null || !Boolean.TRUE.equals(reminder.enabled()))
            return Map.of("enabled", false);
        return map(
                "enabled",
                true,
                "offsetMinutes",
                reminder.offsetMinutes() == null ? 0 : reminder.offsetMinutes(),
                "channel",
                reminder.channel() == null ? "IN_APP" : reminder.channel(),
                "timeZone",
                reminder.timeZone(),
                "quietPolicy",
                reminder.quietPolicy() == null ? "SUPPRESS" : reminder.quietPolicy());
    }

    private PlanView planView(Map<String, Object> row) {
        List<Long> goals = new ArrayList<>();
        Map<String, Object> constraints = constraintSnapshot(row);
        Object values = constraints.get("goalVersions");
        if (values instanceof List<?> list)
            for (Object value : list)
                if (value instanceof Map<?, ?> entry && entry.get("id") != null)
                    goals.add(Long.parseLong(entry.get("id").toString()));
        return new PlanView(
                number(row, "id"),
                number(row, "row_version"),
                text(row, "name"),
                text(row, "plan_kind"),
                text(row, "plan_time_zone"),
                date(row, "range_start_date"),
                date(row, "range_end_date"),
                text(row, "status"),
                nullableId(row, "active_version_id"),
                goals,
                instant(row, "updated_at"));
    }

    private PlanVersionView versionView(Map<String, Object> row, Long active) {
        return new PlanVersionView(
                number(row, "id"),
                number(row, "row_version"),
                number(row, "plan_id"),
                number(row, "version_no"),
                text(row, "status"),
                text(row, "change_summary"),
                text(row, "source_type"),
                instant(row, "created_at"),
                instant(row, "sealed_at"),
                Objects.equals(active, number(row, "id")),
                (int)
                        store.count(
                                "SELECT COUNT(*) FROM plan_version_task WHERE user_id=? AND"
                                        + " version_id=? AND deleted_at IS NULL",
                                uid(),
                                number(row, "id")));
    }

    private TaskView taskView(Map<String, Object> row) {
        Instant skipped = null;
        if ("SKIPPED".equals(text(row, "status")))
            skipped =
                    store.find(
                                    "SELECT occurred_at FROM task_event WHERE user_id=? AND"
                                        + " task_id=? AND event_type='SKIP' ORDER BY id DESC LIMIT"
                                        + " 1",
                                    uid(),
                                    number(row, "id"))
                            .map(event -> instant(event, "occurred_at"))
                            .orElse(null);
        return new TaskView(
                number(row, "id"),
                number(row, "row_version"),
                number(row, "plan_id"),
                number(row, "current_plan_version_id"),
                nullableId(row, "rule_id"),
                text(row, "occurrence_key"),
                text(row, "title"),
                text(row, "task_type"),
                instant(row, "scheduled_start_at"),
                instant(row, "scheduled_end_at"),
                date(row, "task_local_date"),
                text(row, "task_time_zone"),
                integer(row, "planned_duration_minutes"),
                bool(row, "is_fixed"),
                text(row, "status"),
                instant(row, "completed_at"),
                skipped,
                text(row, "source_type"),
                instant(row, "updated_at"));
    }

    private TaskEventView eventView(Map<String, Object> row) {
        return new TaskEventView(
                number(row, "id"),
                number(row, "task_id"),
                text(row, "event_type"),
                text(row, "operation_id"),
                text(row, "old_status"),
                text(row, "new_status"),
                text(row, "reason_code"),
                crypto.decrypt(row.get("reason_cipher")),
                instant(row, "client_occurred_at"),
                instant(row, "occurred_at"),
                text(row, "source_type"),
                number(row, "after_row_version"),
                number(row, "plan_version_id"),
                number(row, "plan_version_task_id"));
    }

    private void syncPlan(long id) {
        Map<String, Object> row = store.owned("plan", id, uid());
        mutations.upsert("PLAN", id, number(row, "row_version"), projection(planView(row)));
    }

    private void syncVersion(long id) {
        Map<String, Object> row = store.owned("plan_version", id, uid());
        mutations.upsert(
                "PLAN_VERSION",
                id,
                number(row, "row_version"),
                projection(getVersion(number(row, "plan_id"), id)));
    }

    private void syncTask(long id) {
        Map<String, Object> row = store.owned("task", id, uid());
        mutations.upsert("TASK", id, number(row, "row_version"), projection(taskView(row)));
    }

    private Map<String, Object> projection(Object view) {
        return mapper.convertValue(view, new TypeReference<>() {});
    }

    private Map<String, Object> ruleValues(
            long plan, PlanRuleInput input, PlanRuleCommand request) {
        return map(
                "user_id",
                uid(),
                "plan_id",
                plan,
                "rule_key",
                UUID.randomUUID().toString(),
                "title",
                input.title(),
                "task_type",
                input.taskType().name(),
                "recurrence_kind",
                "WEEKLY",
                "weekdays_json",
                input.weekdays(),
                "start_local_time",
                input.startTime(),
                "end_local_time",
                input.endTime(),
                "end_day_offset",
                input.endDayOffset() == null ? 0 : input.endDayOffset(),
                "planned_duration_minutes",
                input.plannedDurationMinutes(),
                "time_zone",
                input.timeZone(),
                "valid_from",
                request.effectiveFrom(),
                "valid_until",
                request.effectiveTo(),
                "status",
                "ACTIVE",
                "is_fixed",
                Boolean.TRUE.equals(input.isFixed()),
                "instruction_cipher",
                crypto.encrypt(input.instructions()),
                "encryption_key_ref",
                input.instructions() == null ? null : crypto.keyRef(),
                "reminder_snapshot_json",
                input.reminder() == null ? Map.of() : mapper.valueToTree(input.reminder()));
    }

    private void validateRule(PlanRuleInput input) {
        zone(input.timeZone());
        long minutes =
                Duration.between(input.startTime(), input.endTime()).toMinutes()
                        + 1440L * (input.endDayOffset() == null ? 0 : input.endDayOffset());
        if (minutes < 1
                || minutes > 1440
                || input.plannedDurationMinutes() > minutes
                || new HashSet<>(input.weekdays()).size() != input.weekdays().size())
            throw invalid("INVALID_RULE_INTERVAL", "循环时间窗口或星期集合无效");
    }

    public static Instant resolveLocal(LocalDate day, LocalTime time, ZoneId zone) {
        return resolveSlot(day, time, zone, null);
    }

    private static Instant resolveSlot(
            LocalDate day, LocalTime time, ZoneId zone, JsonNode offset) {
        LocalDateTime local = LocalDateTime.of(day, time);
        List<ZoneOffset> valid = zone.getRules().getValidOffsets(local);
        if (valid.isEmpty()) throw invalid("LOCAL_TIME_INVALID", "该本地时刻不存在，请修改时间");
        if (valid.size() > 1 && (offset == null || offset.isNull()))
            throw invalid("LOCAL_TIME_AMBIGUOUS", "该本地时刻重复，请在日期例外中明确偏移");
        ZoneOffset selected =
                offset == null || offset.isNull()
                        ? valid.getFirst()
                        : ZoneOffset.ofTotalSeconds(offset.asInt() * 60);
        if (!valid.contains(selected)) throw invalid("LOCAL_TIME_OFFSET_INVALID", "所选偏移不适用于该时刻");
        return local.toInstant(selected);
    }

    private static boolean overlaps(Instant a, Instant b, Instant c, Instant d) {
        return a.isBefore(d) && c.isBefore(b);
    }

    private static boolean fixedAllowed(PlanScopeRequest request) {
        return Boolean.TRUE.equals(request.allowFixedTaskChanges())
                && request.fixedChangeReason() != null
                && !request.fixedChangeReason().isBlank();
    }

    private static ZoneId zone(String input) {
        try {
            return ZoneId.of(input);
        } catch (Exception e) {
            throw invalid("INVALID_TIME_ZONE", "IANA时区无效");
        }
    }

    private static void expectVersion(Map<String, Object> row, String expected) {
        if (Ids.parse(expected) != number(row, "row_version"))
            throw BusinessException.conflict("VERSION_CONFLICT", "数据版本已变化，请刷新");
    }

    private static void expectActive(Map<String, Object> plan, String expected) {
        if (!Objects.equals(nullableId(plan, "active_version_id"), Ids.optional(expected)))
            throw BusinessException.conflict("ACTIVE_VERSION_CHANGED", "现行计划版本已变化");
    }

    private static void requireDraft(Map<String, Object> version) {
        if (!"DRAFT".equals(text(version, "status")))
            throw BusinessException.conflict("VERSION_IMMUTABLE", "仅未封存草稿可以修改或应用");
    }

    private static Long nullableId(Map<String, Object> row, String key) {
        return row.get(key) == null ? null : number(row, key);
    }

    private static Set<Long> idSet(List<String> values, int max) {
        List<String> list = safe(values);
        if (list.size() > max) throw invalid("TOO_MANY_IDS", "对象集合过大");
        Set<Long> result =
                list.stream().map(Ids::parse).collect(Collectors.toCollection(LinkedHashSet::new));
        if (result.size() != list.size()) throw invalid("DUPLICATE_ID", "对象集合不能重复");
        return result;
    }

    private static Map<Long, Long> expectedMap(List<TaskVersionRef> refs) {
        Map<Long, Long> result = new LinkedHashMap<>();
        for (TaskVersionRef ref : safe(refs))
            if (result.put(Ids.parse(ref.taskId()), Ids.parse(ref.expectedRowVersion())) != null)
                throw invalid("DUPLICATE_TASK", "任务版本引用不能重复");
        return result;
    }

    private long after(String cursor, String scope) {
        Map<String, Object> state = cursors.decode(cursor, scope);
        return state.isEmpty() ? 0 : Ids.parse(state.get("id").toString());
    }

    private String next(List<Map<String, Object>> rows, String scope, boolean more) {
        return more
                ? cursors.encode(scope, map("id", Long.toString(number(rows.getLast(), "id"))))
                : null;
    }

    private static int limit(Integer value) {
        int result = value == null ? 20 : value;
        if (result < 1 || result > 100) throw invalid("INVALID_LIMIT", "分页大小应为1到100");
        return result;
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static ValidationIssue issue(String code, String path, String message, String id) {
        return new ValidationIssue(
                code,
                "ERROR",
                path,
                message,
                id == null ? List.of() : List.of(id),
                code.contains("FIXED") ? "CONFIRM_FIXED_CHANGE" : "EDIT");
    }

    private static BusinessException invalid(String code, String message) {
        return BusinessException.badRequest(code, message);
    }

    private static long uid() {
        return AuthContext.userId();
    }
}
