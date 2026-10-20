package com.yqsoft.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yqsoft.config.AppProperties;
import com.yqsoft.dao.SqlStore;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.persistence.MutationExecutor;
import com.yqsoft.persistence.WorkerGuard;
import com.yqsoft.resource.ai.AiDtos;
import com.yqsoft.resource.common.*;
import com.yqsoft.resource.statistics.StatisticsDtos;
import com.yqsoft.security.AuthContext;
import com.yqsoft.service.common.CryptoService;
import com.yqsoft.service.common.CursorService;
import com.yqsoft.service.knowledge.KnowledgeService;
import com.yqsoft.service.statistics.StatisticsService;
import com.yqsoft.service.statistics.WeeklyReviewService;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
public class AiService {
    private final SqlStore sql;
    private final MutationExecutor mutations;
    private final WorkerGuard guard;
    private final CryptoService crypto;
    private final CursorService cursors;
    private final ObjectMapper mapper;
    private final ValidationSupport validation;
    private final AiProviderClient provider;
    private final AiDraftService drafts;
    private final KnowledgeService knowledge;
    private final WeeklyReviewService weekly;
    private final AppProperties properties;

    public AiService(
            SqlStore sql,
            MutationExecutor mutations,
            WorkerGuard guard,
            CryptoService crypto,
            CursorService cursors,
            ObjectMapper mapper,
            ValidationSupport validation,
            AiProviderClient provider,
            AiDraftService drafts,
            KnowledgeService knowledge,
            WeeklyReviewService weekly,
            AppProperties properties) {
        this.sql = sql;
        this.mutations = mutations;
        this.guard = guard;
        this.crypto = crypto;
        this.cursors = cursors;
        this.mapper = mapper;
        this.validation = validation;
        this.provider = provider;
        this.drafts = drafts;
        this.knowledge = knowledge;
        this.weekly = weekly;
        this.properties = properties;
    }

    public PageResult<Map<String, Object>> conversations(String cursor, int limit) {
        pageLimit(limit);
        String scope = "ai-conversations";
        long after = Rows.number(cursors.decode(cursor, scope), "id");
        List<Map<String, Object>> rows =
                sql.query(
                        "SELECT * FROM ai_conversation WHERE user_id=? AND deleted_at IS NULL AND"
                                + " id>? ORDER BY id LIMIT ?",
                        AuthContext.userId(),
                        after,
                        limit + 1);
        boolean more = rows.size() > limit;
        if (more) rows = rows.subList(0, limit);
        return PageResult.of(
                rows.stream().map(this::conversationView).toList(),
                more
                        ? cursors.encode(scope, Rows.map("id", Rows.number(rows.getLast(), "id")))
                        : null,
                more);
    }

    public MutationResult createConversation(AiDtos.ConversationCreate request) {
        return mutations.mutate(
                "AI_CONVERSATION_CREATE",
                request,
                true,
                () -> {
                    mutations.assertConsent("AI_PROCESSING");
                    String title =
                            request.title() == null || request.title().isBlank()
                                    ? "新的对话"
                                    : request.title();
                    long id =
                            sql.insert(
                                    "ai_conversation",
                                    Rows.map(
                                            "user_id",
                                            AuthContext.userId(),
                                            "conversation_key",
                                            UUID.randomUUID().toString(),
                                            "title_cipher",
                                            crypto.encrypt(title),
                                            "encryption_key_ref",
                                            crypto.keyRef(),
                                            "status",
                                            "ACTIVE",
                                            "context_policy",
                                            "MINIMUM_NECESSARY",
                                            "message_count",
                                            0,
                                            "scene",
                                            request.scene() == null ? "GENERAL" : request.scene()));
                    return MutationResult.of("AI_CONVERSATION", id, 1);
                });
    }

    public Map<String, Object> conversation(long id) {
        return conversationView(sql.owned("ai_conversation", id, AuthContext.userId()));
    }

    public MutationResult archive(long id, AiDtos.VersionBody request) {
        return mutations.mutate(
                "AI_CONVERSATION_ARCHIVE",
                Rows.map("id", id, "request", request),
                true,
                () -> {
                    Map<String, Object> row =
                            sql.lockOwned("ai_conversation", id, AuthContext.userId());
                    expected(row, request.expectedRowVersion());
                    long version =
                            sql.update(
                                    "ai_conversation",
                                    id,
                                    Rows.number(row, "row_version"),
                                    Rows.map("status", "ARCHIVED"));
                    return MutationResult.of("AI_CONVERSATION", id, version);
                });
    }

    public PageResult<Map<String, Object>> messages(
            long conversationId, String beforeId, String cursor, int limit) {
        sql.owned("ai_conversation", conversationId, AuthContext.userId());
        pageLimit(limit);
        if (beforeId != null && cursor != null) throw invalid("beforeMessageId和cursor不能同时使用");
        String scope = "ai-messages:" + conversationId;
        Map<String, Object> position = cursors.decode(cursor, scope);
        long after = Rows.number(position, "id");
        boolean backward = beforeId != null || Boolean.TRUE.equals(position.get("backward"));
        long upperBound = beforeId == null ? after : Ids.parse(beforeId);
        List<Map<String, Object>> rows =
                !backward
                        ? sql.query(
                                "SELECT * FROM ai_message WHERE user_id=? AND conversation_id=? AND"
                                        + " id>? ORDER BY id LIMIT ?",
                                AuthContext.userId(),
                                conversationId,
                                after,
                                limit + 1)
                        : sql.query(
                                "SELECT * FROM ai_message WHERE user_id=? AND conversation_id=? AND"
                                        + " id<? ORDER BY id DESC LIMIT ?",
                                AuthContext.userId(),
                                conversationId,
                                upperBound,
                                limit + 1);
        boolean more = rows.size() > limit;
        if (more) rows = rows.subList(0, limit);
        return PageResult.of(
                rows.stream().map(this::messageView).toList(),
                more
                        ? cursors.encode(
                                scope,
                                Rows.map(
                                        "id",
                                        Rows.number(rows.getLast(), "id"),
                                        "backward",
                                        backward))
                        : null,
                more);
    }

    public Map<String, Object> sendMessage(long id, AiDtos.MessageCreate request) {
        provider.assertAvailable();
        MutationResult result =
                mutations.mutate(
                        "AI_MESSAGE_SEND",
                        Rows.map("conversationId", id, "request", request),
                        true,
                        () -> {
                            mutations.assertConsent("AI_PROCESSING");
                            Map<String, Object> conversation =
                                    sql.lockOwned("ai_conversation", id, AuthContext.userId());
                            if (!"ACTIVE".equals(conversation.get("status")))
                                throw BusinessException.conflict(
                                        "CONVERSATION_ARCHIVED", "请新建对话后继续提问");
                            Optional<Map<String, Object>> existing =
                                    sql.find(
                                            "SELECT * FROM ai_message WHERE user_id=? AND"
                                                    + " client_message_id=?",
                                            AuthContext.userId(),
                                            request.clientMessageId().toString());
                            if (existing.isPresent()) {
                                if (Rows.number(existing.get(), "conversation_id") != id
                                        || !Objects.equals(
                                                crypto.decrypt(
                                                        existing.get().get("content_cipher")),
                                                request.text())) {
                                    throw BusinessException.conflict(
                                            "CLIENT_MESSAGE_ID_REUSED", "消息编号已用于不同内容");
                                }
                                long oldRun = Rows.number(existing.get(), "ai_run_id");
                                Map<String, Object> run =
                                        sql.owned("ai_run", oldRun, AuthContext.userId());
                                return MutationResult.of(
                                        "AI_RUN", oldRun, Rows.number(run, "row_version"));
                            }
                            long messageId =
                                    sql.insert(
                                            "ai_message",
                                            Rows.map(
                                                    "user_id",
                                                    AuthContext.userId(),
                                                    "conversation_id",
                                                    id,
                                                    "client_message_id",
                                                    request.clientMessageId().toString(),
                                                    "role",
                                                    "USER",
                                                    "message_type",
                                                    "TEXT",
                                                    "content_cipher",
                                                    crypto.encrypt(request.text()),
                                                    "encryption_key_ref",
                                                    crypto.keyRef(),
                                                    "delivery_status",
                                                    "READY",
                                                    "content_digest",
                                                    crypto.digest(request.text()),
                                                    "finish_reason",
                                                    "STOP"));
                            JsonNode input =
                                    mapper.valueToTree(
                                            new AiDtos.ChatInput(
                                                    request.text(),
                                                    request.contextTaskIds(),
                                                    request.contextRecordIds(),
                                                    request.allowKnowledgeSearch()));
                            MutationResult accepted =
                                    queue(
                                            new AiDtos.RunCreate(
                                                    "CHAT",
                                                    Long.toString(id),
                                                    input,
                                                    UUID.randomUUID()),
                                            messageId,
                                            false);
                            sql.execute(
                                    "UPDATE ai_message SET ai_run_id=? WHERE id=? AND user_id=?",
                                    Ids.parse(accepted.id()),
                                    messageId,
                                    AuthContext.userId());
                            sql.update(
                                    "ai_conversation",
                                    id,
                                    Rows.number(conversation, "row_version"),
                                    Rows.map(
                                            "last_message_at",
                                            Instant.now(),
                                            "last_message_id",
                                            messageId,
                                            "message_count",
                                            Rows.integer(conversation, "message_count") + 1));
                            return accepted;
                        });
        return accepted(result);
    }

    public Map<String, Object> createRun(AiDtos.RunCreate request) {
        if (Set.of("VOICE_TRANSCRIBE", "COURSE_IMPORT").contains(request.kind()))
            throw BusinessException.unavailable("FEATURE_NOT_ENABLED", "该扩展功能尚未接入设备与权限验证");
        provider.assertAvailable();
        MutationResult result =
                mutations.mutate("AI_RUN_CREATE", request, true, () -> queue(request, null, false));
        return accepted(result);
    }

    public Map<String, Object> createWeekly(StatisticsDtos.WeeklyCreate request) {
        provider.assertAvailable();
        AiDtos.WeeklyReviewInput input =
                new AiDtos.WeeklyReviewInput(
                        request.weekStart(), request.expectedDataSeq(), request.focus());
        MutationResult result =
                mutations.mutate(
                        "WEEKLY_REVIEW_CREATE",
                        request,
                        true,
                        () ->
                                queue(
                                        new AiDtos.RunCreate(
                                                "WEEKLY_REVIEW",
                                                null,
                                                mapper.valueToTree(input),
                                                UUID.randomUUID()),
                                        null,
                                        Boolean.TRUE.equals(request.forceNewRevision())));
        Map<String, Object> review =
                sql.one(
                        "SELECT * FROM weekly_review WHERE user_id=? AND ai_run_id=?",
                        AuthContext.userId(),
                        Ids.parse(result.id()));
        return Rows.map(
                "jobId",
                Rows.number(review, "id"),
                "jobType",
                "WEEKLY_REVIEW",
                "status",
                review.get("status"),
                "pollPath",
                "/api/v1/weekly-reviews/" + Rows.number(review, "id"),
                "pollAfterSeconds",
                2,
                "mutation",
                result);
    }

    public Map<String, Object> run(long id) {
        return runForUser(AuthContext.userId(), id);
    }

    public Map<String, Object> runForUser(long userId, long id) {
        Map<String, Object> r = sql.owned("ai_run", id, userId);
        Optional<Map<String, Object>> draft =
                sql.find(
                        "SELECT id FROM ai_draft WHERE user_id=? AND source_run_id=? AND deleted_at"
                                + " IS NULL ORDER BY id DESC LIMIT 1",
                        userId,
                        id);
        Optional<Map<String, Object>> message =
                sql.find(
                        "SELECT id FROM ai_message WHERE user_id=? AND ai_run_id=? AND"
                                + " role='ASSISTANT' ORDER BY id DESC LIMIT 1",
                        userId,
                        id);
        Optional<Map<String, Object>> review =
                sql.find(
                        "SELECT id FROM weekly_review WHERE user_id=? AND ai_run_id=? AND"
                                + " deleted_at IS NULL",
                        userId,
                        id);
        String status = Rows.text(r, "status");
        return Rows.map(
                "id",
                id,
                "rowVersion",
                Rows.number(r, "row_version"),
                "kind",
                r.get("kind"),
                "status",
                status,
                "conversationId",
                r.get("conversation_id"),
                "draftId",
                draft.map(x -> x.get("id")).orElse(null),
                "resultMessageId",
                message.map(x -> x.get("id")).orElse(null),
                "weeklyReviewId",
                review.map(x -> x.get("id")).orElse(null),
                "progressStage",
                switch (status) {
                    case "QUEUED" -> "QUEUED";
                    case "RUNNING", "CANCEL_REQUESTED" -> "GENERATING";
                    default -> "DONE";
                },
                "createdAt",
                Rows.instant(r, "created_at"),
                "startedAt",
                Rows.instant(r, "started_at"),
                "finishedAt",
                Rows.instant(r, "finished_at"),
                "expiresAt",
                Rows.instant(r, "timeout_at"),
                "cancelRequested",
                "CANCEL_REQUESTED".equals(status),
                "error",
                r.get("error_code") == null
                        ? null
                        : Rows.map(
                                "code",
                                r.get("error_code"),
                                "message",
                                "生成未完成，可查看当前数据后重试",
                                "missingFields",
                                List.of()),
                "retryable",
                Set.of("FAILED", "CANCELLED").contains(status));
    }

    public MutationResult cancelRun(long id, AiDtos.RunCancel request) {
        return mutations.mutate(
                "AI_RUN_CANCEL",
                Rows.map("id", id, "request", request),
                true,
                () -> {
                    Map<String, Object> row = sql.lockOwned("ai_run", id, AuthContext.userId());
                    expected(row, request.expectedRowVersion());
                    String state = Rows.text(row, "status");
                    if (!Set.of("QUEUED", "RUNNING", "CANCEL_REQUESTED").contains(state))
                        throw BusinessException.conflict("RUN_ALREADY_FINISHED", "该生成已结束");
                    Map<String, Object> values =
                            "QUEUED".equals(state)
                                    ? Rows.map(
                                            "status",
                                            "CANCELLED",
                                            "finished_at",
                                            Instant.now(),
                                            "error_code",
                                            "CANCELLED")
                                    : Rows.map("status", "CANCEL_REQUESTED");
                    long version =
                            sql.update("ai_run", id, Rows.number(row, "row_version"), values);
                    if ("QUEUED".equals(state)) {
                        sql.execute(
                                "UPDATE outbox_event SET"
                                    + " status='CANCELLED',updated_at=?,row_version=row_version+1"
                                    + " WHERE user_id=? AND event_type='AI_RUN_QUEUED' AND"
                                    + " aggregate_id=? AND status IN ('PENDING','RETRY')",
                                Rows.utc(Instant.now()),
                                AuthContext.userId(),
                                id);
                        weekly.failed(id);
                    }
                    return MutationResult.of("AI_RUN", id, version);
                });
    }

    public Map<String, Object> retryRun(long id, AiDtos.RunRetry request) {
        provider.assertAvailable();
        MutationResult result =
                mutations.mutate(
                        "AI_RUN_RETRY",
                        Rows.map("id", id, "request", request),
                        true,
                        () -> {
                            if (!Boolean.TRUE.equals(request.reuseInput()))
                                throw invalid("重试须复用原输入，修改输入请新建生成");
                            Map<String, Object> old =
                                    sql.lockOwned("ai_run", id, AuthContext.userId());
                            expected(old, request.expectedRowVersion());
                            if (!Set.of("FAILED", "CANCELLED").contains(Rows.text(old, "status")))
                                throw BusinessException.conflict("RUN_NOT_RETRYABLE", "当前生成不能重试");
                            if (Rows.instant(old, "created_at")
                                    .isBefore(Instant.now().minus(Duration.ofDays(7))))
                                throw BusinessException.conflict("INPUT_EXPIRED", "输入已过期，请重新填写");
                            JsonNode input = readInput(old);
                            return queue(
                                    new AiDtos.RunCreate(
                                            Rows.text(old, "kind"),
                                            old.get("conversation_id") == null
                                                    ? null
                                                    : Rows.text(old, "conversation_id"),
                                            input.path("request"),
                                            UUID.randomUUID()),
                                    null,
                                    true);
                        });
        return accepted(result);
    }

    private MutationResult queue(
            AiDtos.RunCreate request, Long inputMessageId, boolean forceWeekly) {
        assertFeature(request.kind());
        Map<String, Object> consent = mutations.assertConsent("AI_PROCESSING");
        long userId = AuthContext.userId();
        Long conversationId = Ids.optional(request.conversationId());
        if (conversationId == null && "CHAT".equals(request.kind())) {
            conversationId =
                    Ids.parse(
                            createConversation(new AiDtos.ConversationCreate("新的对话", "GENERAL"))
                                    .id());
        }
        if (conversationId != null) {
            Map<String, Object> conversation = sql.owned("ai_conversation", conversationId, userId);
            if (!"ACTIVE".equals(conversation.get("status")))
                throw BusinessException.conflict("CONVERSATION_ARCHIVED", "对话已归档");
        }
        Map<String, Object> context = prepare(request, forceWeekly);
        Long existingRun = context.get("existingRunId") instanceof Number n ? n.longValue() : null;
        if (existingRun != null) {
            Map<String, Object> old = sql.owned("ai_run", existingRun, userId);
            return MutationResult.of("AI_RUN", existingRun, Rows.number(old, "row_version"));
        }
        int configuredLimit =
                sql.find(
                                "SELECT value_json FROM app_configuration WHERE environment=? AND"
                                    + " config_key='ai.max_concurrent_runs_per_user' AND"
                                    + " is_enabled=1 AND deleted_at IS NULL",
                                properties.getEnvironment())
                        .map(row -> Rows.json(row, "value_json").path("numberValue").asInt(2))
                        .orElse(2);
        int limit = Math.clamp(configuredLimit, 1, 2);
        if (sql.count(
                        "SELECT COUNT(*) FROM ai_run WHERE user_id=? AND deleted_at IS NULL "
                                + "AND status IN ('QUEUED','RUNNING','CANCEL_REQUESTED')",
                        userId)
                >= limit) {
            throw new BusinessException(429, "AI_CONCURRENCY_LIMIT", "已有生成任务正在处理，请稍后重试");
        }
        long id =
                sql.insert(
                        "ai_run",
                        Rows.map(
                                "user_id",
                                userId,
                                "run_key",
                                request.clientRequestId().toString(),
                                "kind",
                                request.kind(),
                                "conversation_id",
                                conversationId,
                                "input_message_id",
                                inputMessageId,
                                "status",
                                "QUEUED",
                                "provider_code",
                                "OPENAI_COMPATIBLE",
                                "model_name",
                                provider.model(),
                                "prompt_template_version",
                                "LIFE_V1",
                                "input_snapshot_cipher",
                                crypto.encrypt(json(context)),
                                "encryption_key_ref",
                                crypto.keyRef(),
                                "captured_dataset_epoch",
                                mutations.currentEpoch(),
                                "captured_consent_version",
                                Rows.number(consent, "consent_version"),
                                "attempt_count",
                                0,
                                "timeout_at",
                                Instant.now()
                                        .plusSeconds(
                                                Math.max(30, properties.getAi().getTimeoutSeconds())
                                                        + 120)));
        if (context.get("weeklyReviewId") instanceof Number reviewId)
            weekly.attachRun(reviewId.longValue(), id);
        mutations.outbox("AI_RUN_QUEUED", "AI_RUN", id, 1L, Rows.map("runId", id));
        return MutationResult.of("AI_RUN", id, 1);
    }

    private Map<String, Object> prepare(AiDtos.RunCreate request, boolean forceWeekly) {
        long userId = AuthContext.userId();
        Map<String, Object> base =
                Rows.map(
                        "datasetEpoch",
                        mutations.currentEpoch(),
                        "sourceDataSeq",
                        mutations.currentSeq(),
                        "planId",
                        null,
                        "activeVersionId",
                        null,
                        "tasks",
                        List.of(),
                        "consentPurpose",
                        "AI_PROCESSING",
                        "consentRevision",
                        Rows.number(mutations.assertConsent("AI_PROCESSING"), "consent_version"));
        Map<String, Object> context =
                Rows.map("request", request.input(), "base", base, "retrievedSources", List.of());
        List<Map<String, Object>> goals = new ArrayList<>();
        List<Map<String, Object>> goalVersions = new ArrayList<>();
        switch (request.kind()) {
            case "PLAN_GENERATE" -> {
                AiDtos.PlanGenerateInput value =
                        validation.convert(request.input(), AiDtos.PlanGenerateInput.class);
                requireSeq(value.expectedDataSeq());
                StatisticsService.dateRange(value.from(), value.to(), 14);
                StatisticsService.checkedZone(value.timeZone());
                if (value.planId() != null) throw invalid("已有计划请使用PLAN_ADJUST，避免生成请求隐式替换现行计划");
                if (value.goalIds().stream().distinct().count() != value.goalIds().size())
                    throw invalid("目标不能重复");
                for (String goalId : value.goalIds()) {
                    Map<String, Object> goal = sql.owned("user_goal", Ids.parse(goalId), userId);
                    if (!"ACTIVE".equals(goal.get("status"))) throw invalid("请选择仍在启用的目标");
                    goals.add(
                            Rows.map(
                                    "id",
                                    Rows.number(goal, "id"),
                                    "title",
                                    goal.get("display_name"),
                                    "parameters",
                                    Rows.json(goal, "parameters_json")));
                    goalVersions.add(ref(goal, "user_goal"));
                }
                context.put("goals", goals);
            }
            case "PLAN_ADJUST" -> {
                AiDtos.PlanAdjustInput value =
                        validation.convert(request.input(), AiDtos.PlanAdjustInput.class);
                requireSeq(value.expectedDataSeq());
                Map<String, Object> plan = sql.owned("plan", Ids.parse(value.planId()), userId);
                if (Rows.number(plan, "active_version_id")
                        != Ids.parse(value.expectedActiveVersionId()))
                    throw BusinessException.conflict("ACTIVE_VERSION_CONFLICT", "计划已更新");
                base.put("planId", Ids.parse(value.planId()));
                base.put("activeVersionId", Ids.parse(value.expectedActiveVersionId()));
                List<Map<String, Object>> tasks = new ArrayList<>();
                for (var expectedTask : value.affectedTasks()) {
                    Map<String, Object> task =
                            sql.owned("task", Ids.parse(expectedTask.taskId()), userId);
                    if (Rows.number(task, "plan_id") != Ids.parse(value.planId())
                            || Rows.number(task, "row_version")
                                    != Ids.parse(expectedTask.expectedRowVersion())
                            || !"PENDING".equals(task.get("status")))
                        throw BusinessException.conflict("TASK_CHANGED", "涉及任务已变化");
                    tasks.add(taskProjection(task));
                }
                context.put("affectedTasks", tasks);
                base.put("tasks", value.affectedTasks());
            }
            case "RECORD_EXTRACT" -> {
                AiDtos.RecordExtractInput value =
                        validation.convert(request.input(), AiDtos.RecordExtractInput.class);
                StatisticsService.checkedZone(value.timeZone());
            }
            case "CHAT" -> {
                AiDtos.ChatInput value =
                        validation.convert(request.input(), AiDtos.ChatInput.class);
                List<Map<String, Object>> taskViews = new ArrayList<>();
                List<Map<String, Object>> recordViews = new ArrayList<>();
                if (value.contextTaskIds() != null)
                    for (String id : value.contextTaskIds())
                        taskViews.add(taskProjection(sql.owned("task", Ids.parse(id), userId)));
                if (value.contextRecordIds() != null)
                    for (String id : value.contextRecordIds()) {
                        Map<String, Object> record =
                                sql.owned("health_record", Ids.parse(id), userId);
                        recordViews.add(
                                Rows.map(
                                        "id",
                                        Rows.number(record, "id"),
                                        "rowVersion",
                                        Rows.number(record, "row_version"),
                                        "recordType",
                                        record.get("record_type"),
                                        "startedAt",
                                        Rows.instant(record, "started_at"),
                                        "endedAt",
                                        Rows.instant(record, "ended_at"),
                                        "businessDate",
                                        Rows.date(record, "record_local_date")));
                    }
                context.put("contextTasks", taskViews);
                context.put("contextRecords", recordViews);
                if (!Boolean.FALSE.equals(value.allowKnowledgeSearch()))
                    context.put("retrievedSources", knowledge.retrieve(value.text(), 6));
            }
            case "WEEKLY_REVIEW" -> {
                AiDtos.WeeklyReviewInput value =
                        validation.convert(request.input(), AiDtos.WeeklyReviewInput.class);
                requireSeq(value.expectedDataSeq());
                WeeklyReviewService.Prepared prepared =
                        weekly.prepare(userId, value.weekStart(), forceWeekly);
                if (prepared.existingRunId() != null)
                    context.put("existingRunId", prepared.existingRunId());
                context.putAll(prepared.context());
            }
            default -> throw BusinessException.unavailable("FEATURE_NOT_ENABLED", "该扩展功能尚未启用");
        }
        base.put("goalVersions", goalVersions);
        if (Set.of("PLAN_GENERATE", "PLAN_ADJUST").contains(request.kind())) {
            List<Map<String, Object>> rules =
                    sql.query(
                            "SELECT * FROM availability_rule WHERE user_id=? AND deleted_at IS NULL"
                                    + " ORDER BY id",
                            userId);
            List<Map<String, Object>> exceptions =
                    sql.query(
                            "SELECT * FROM availability_exception WHERE user_id=? AND deleted_at IS"
                                    + " NULL ORDER BY id",
                            userId);
            List<Map<String, Object>> refs = new ArrayList<>();
            rules.forEach(r -> refs.add(ref(r, "availability_rule")));
            exceptions.forEach(r -> refs.add(ref(r, "availability_exception")));
            base.put("availabilityVersions", refs);
            context.put(
                    "availabilityRules",
                    rules.stream()
                            .map(
                                    r ->
                                            Rows.map(
                                                    "weekday",
                                                    r.get("weekday"),
                                                    "kind",
                                                    r.get("rule_kind"),
                                                    "startMinute",
                                                    r.get("start_minute"),
                                                    "endMinute",
                                                    r.get("end_minute"),
                                                    "endDayOffset",
                                                    r.get("end_day_offset"),
                                                    "timeZone",
                                                    r.get("time_zone"),
                                                    "validFrom",
                                                    r.get("valid_from"),
                                                    "validUntil",
                                                    r.get("valid_until"),
                                                    "enabled",
                                                    Rows.bool(r, "is_enabled")))
                            .toList());
            context.put(
                    "availabilityExceptions",
                    exceptions.stream()
                            .map(
                                    r ->
                                            Rows.map(
                                                    "date",
                                                    Rows.date(r, "exception_date"),
                                                    "mode",
                                                    r.get("mode"),
                                                    "timeZone",
                                                    r.get("time_zone"),
                                                    "slots",
                                                    Rows.json(r, "slots_json")))
                            .toList());
            Map<String, Object> preference =
                    sql.one(
                            "SELECT * FROM user_preference WHERE user_id=? AND deleted_at IS NULL",
                            userId);
            Map<String, Object> profile =
                    sql.one(
                            "SELECT * FROM user_profile WHERE user_id=? AND deleted_at IS NULL",
                            userId);
            base.put(
                    "preferenceVersions",
                    List.of(ref(preference, "user_preference"), ref(profile, "user_profile")));
            context.put(
                    "preferences",
                    Rows.map(
                            "dailyTaskLimit",
                            Rows.integer(preference, "daily_task_limit"),
                            "preferredActivityTypes",
                            Rows.json(preference, "preferred_activity_types_json"),
                            "excludedActivityTypes",
                            Rows.json(preference, "excluded_activity_types_json")));
        }
        return context;
    }

    public JsonNode readInput(Map<String, Object> run) {
        if (run.get("input_snapshot_cipher") == null)
            throw BusinessException.conflict("INPUT_EXPIRED", "生成输入已清除，请重新填写");
        try {
            return mapper.readTree(crypto.decrypt(run.get("input_snapshot_cipher")));
        } catch (Exception ex) {
            throw BusinessException.conflict("INPUT_EXPIRED", "生成输入已失效，请重新填写");
        }
    }

    /**
     * Only durable state is resumed; partial provider tokens are never advertised as verified
     * content.
     */
    public Map<String, Object> event(long userId, long sessionId, long runId) {
        Map<String, Object> session =
                sql.one(
                        "SELECT s.revoked_at,s.security_version,u.status,u.security_version"
                                + " account_version FROM auth_session s JOIN app_user u ON"
                                + " u.id=s.user_id WHERE s.id=? AND s.user_id=?",
                        sessionId,
                        userId);
        if (session.get("revoked_at") != null
                || !"ACTIVE".equals(session.get("status"))
                || Rows.number(session, "security_version")
                        != Rows.number(session, "account_version"))
            throw new BusinessException(401, "SESSION_REVOKED", "会话已失效");
        Map<String, Object> view = runForUser(userId, runId);
        String status = Rows.text(view, "status");
        return Rows.map(
                "eventId",
                runId + ":" + view.get("rowVersion"),
                "runId",
                runId,
                "eventType",
                switch (status) {
                    case "SUCCEEDED" -> "COMPLETED";
                    case "FAILED" -> "FAILED";
                    case "CANCELLED" -> "CANCELLED";
                    default -> "STATUS";
                },
                "status",
                status,
                "textDelta",
                null,
                "draftId",
                view.get("draftId"),
                "messageId",
                view.get("resultMessageId"),
                "error",
                view.get("error"),
                "serverTime",
                Instant.now());
    }

    public boolean claim(long id) {
        Map<String, Object> run =
                sql.one("SELECT * FROM ai_run WHERE id=? AND deleted_at IS NULL", id);
        return guard.withUser(
                Rows.number(run, "user_id"),
                Rows.number(run, "captured_dataset_epoch"),
                "AI_PROCESSING",
                Rows.number(run, "captured_consent_version"),
                () -> {
                    Map<String, Object> latest =
                            sql.one("SELECT * FROM ai_run WHERE id=? FOR UPDATE", id);
                    if (!"QUEUED".equals(latest.get("status"))) return false;
                    if (Rows.instant(latest, "timeout_at").isBefore(Instant.now()))
                        throw BusinessException.conflict("INPUT_EXPIRED", "生成已过期");
                    sql.update(
                            "ai_run",
                            id,
                            Rows.number(latest, "row_version"),
                            Rows.map(
                                    "status",
                                    "RUNNING",
                                    "started_at",
                                    Instant.now(),
                                    "attempt_count",
                                    Rows.integer(latest, "attempt_count") + 1));
                    sql.execute(
                            "UPDATE outbox_event SET"
                                + " status='CLAIMED',lease_owner='ai-worker',lease_until=?,updated_at=?,row_version=row_version+1"
                                + " WHERE user_id=? AND event_type='AI_RUN_QUEUED' AND"
                                + " aggregate_id=? AND status IN ('PENDING','RETRY')",
                            Rows.utc(Instant.now().plusSeconds(180)),
                            Rows.utc(Instant.now()),
                            Rows.number(run, "user_id"),
                            id);
                    return true;
                });
    }

    public void executeClaimed(long id) {
        Map<String, Object> run =
                sql.one("SELECT * FROM ai_run WHERE id=? AND deleted_at IS NULL", id);
        guard.withUser(
                Rows.number(run, "user_id"),
                Rows.number(run, "captured_dataset_epoch"),
                "AI_PROCESSING",
                Rows.number(run, "captured_consent_version"),
                () -> {
                    var currentRun =
                            sql.one(
                                    "SELECT status,timeout_at FROM ai_run WHERE id=? FOR UPDATE",
                                    id);
                    if (!"RUNNING".equals(Rows.text(currentRun, "status"))) {
                        throw BusinessException.conflict("CANCELLED", "本次生成已中止");
                    }
                    if (!Rows.instant(currentRun, "timeout_at").isAfter(Instant.now())) {
                        throw BusinessException.conflict("INPUT_EXPIRED", "生成已过期");
                    }
                    assertFeature(Rows.text(run, "kind"));
                    return null;
                });
        JsonNode input = readInput(run);
        Map<String, Object> outbound =
                mapper.convertValue(
                        input, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        outbound.remove("base");
        outbound.remove("statVersions");
        outbound.remove("weeklyReviewId");
        AiProviderClient.Completion completion =
                provider.complete(Rows.text(run, "kind"), outbound);
        long userId = Rows.number(run, "user_id");
        guard.withUser(
                userId,
                Rows.number(run, "captured_dataset_epoch"),
                "AI_PROCESSING",
                Rows.number(run, "captured_consent_version"),
                () -> {
                    assertFeature(Rows.text(run, "kind"));
                    Map<String, Object> latest =
                            sql.one(
                                    "SELECT * FROM ai_run WHERE id=? AND deleted_at IS NULL FOR"
                                            + " UPDATE",
                                    id);
                    if ("CANCEL_REQUESTED".equals(latest.get("status"))) {
                        sql.update(
                                "ai_run",
                                id,
                                Rows.number(latest, "row_version"),
                                Rows.map(
                                        "status",
                                        "CANCELLED",
                                        "finished_at",
                                        Instant.now(),
                                        "error_code",
                                        "CANCELLED"));
                        weekly.failed(id);
                        finishOutbox(userId, id, "CANCELLED");
                        return null;
                    }
                    if (!"RUNNING".equals(latest.get("status"))) return null;
                    if (Rows.number(
                                    input.path("base").isObject()
                                            ? mapper.convertValue(
                                                    input.path("base"),
                                                    new com.fasterxml.jackson.core.type
                                                                    .TypeReference<
                                                            Map<String, Object>>() {})
                                            : Map.of(),
                                    "sourceDataSeq")
                            != sql.count(
                                    "SELECT committed_seq FROM user_sync_clock WHERE user_id=?",
                                    userId))
                        throw BusinessException.conflict("SOURCE_CHANGED", "生成依据已变化，请重新生成");
                    String kind = Rows.text(run, "kind");
                    if ("WEEKLY_REVIEW".equals(kind))
                        weekly.complete(userId, id, input, completion.output());
                    else if ("CHAT".equals(kind))
                        saveAnswer(userId, latest, input, completion.output());
                    else {
                        long draftId =
                                drafts.createFromOutput(userId, latest, input, completion.output());
                        if (latest.get("conversation_id") != null)
                            saveDraftCard(userId, latest, draftId);
                    }
                    sql.update(
                            "ai_run",
                            id,
                            Rows.number(latest, "row_version"),
                            Rows.map(
                                    "status",
                                    "SUCCEEDED",
                                    "finished_at",
                                    Instant.now(),
                                    "input_tokens",
                                    completion.inputTokens(),
                                    "output_tokens",
                                    completion.outputTokens(),
                                    "provider_request_id",
                                    completion.providerRequestId()));
                    finishOutbox(userId, id, "DONE");
                    return null;
                });
    }

    public void fail(long id, String code) {
        Optional<Map<String, Object>> found = sql.find("SELECT * FROM ai_run WHERE id=?", id);
        if (found.isEmpty()) return;
        Map<String, Object> run = found.get();
        Set<String> terminal = Set.of("SUCCEEDED", "FAILED", "CANCELLED");
        if (terminal.contains(Rows.text(run, "status"))) return;
        String safe =
                Set.of(
                                        "PROVIDER_TIMEOUT",
                                        "PROVIDER_UNAVAILABLE",
                                        "AI_NOT_CONFIGURED",
                                        "FEATURE_NOT_ENABLED",
                                        "SOURCE_CHANGED",
                                        "DATA_EPOCH_CHANGED",
                                        "CONSENT_CHANGED",
                                        "DATA_WRITE_FENCED",
                                        "CANCELLED",
                                        "INPUT_EXPIRED")
                                .contains(code)
                        ? code
                        : "OUTPUT_INVALID";
        // This cleanup only removes/terminates metadata; it cannot resurrect content after an epoch
        // change.
        sql.execute(
                "UPDATE ai_run SET"
                    + " status=?,error_code=?,finished_at=?,updated_at=?,row_version=row_version+1"
                    + " WHERE id=? AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED')",
                "CANCELLED".equals(safe) ? "CANCELLED" : "FAILED",
                safe,
                Rows.utc(Instant.now()),
                Rows.utc(Instant.now()),
                id);
        weekly.failed(id);
        finishOutbox(Rows.number(run, "user_id"), id, "DEAD");
    }

    private void saveAnswer(long userId, Map<String, Object> run, JsonNode input, JsonNode output) {
        String answer = output.path("answer").asText("");
        if (answer.isBlank() || answer.length() > 6000 || answer.matches("(?s).*https?://.*"))
            throw invalid("回复格式或链接不符合来源规则");
        JsonNode citations = output.path("citations");
        if (!citations.isArray() || citations.size() > 8) throw invalid("引用结构无效");
        Set<String> allowed = new HashSet<>();
        input.path("retrievedSources")
                .forEach(
                        n ->
                                allowed.add(
                                        n.path("articleVersionId").asText()
                                                + ":"
                                                + n.path("sourceId").asText()));
        Set<Integer> numbers = new HashSet<>();
        for (JsonNode citation : citations) {
            String key =
                    citation.path("articleVersionId").asText()
                            + ":"
                            + citation.path("sourceId").asText();
            int number = citation.path("citationNo").asInt(0);
            if (!allowed.contains(key) || number < 1 || number > 8 || !numbers.add(number))
                throw invalid("生成引用不在本次真实检索结果内");
        }
        long messageId = insertAssistant(userId, run, "TEXT", answer, null);
        for (JsonNode citation : citations)
            sql.insert(
                    "ai_message_citation",
                    Rows.map(
                            "user_id",
                            userId,
                            "message_id",
                            messageId,
                            "citation_no",
                            citation.path("citationNo").asInt(),
                            "article_version_id",
                            Ids.parse(citation.path("articleVersionId").asText()),
                            "knowledge_source_id",
                            Ids.parse(citation.path("sourceId").asText()),
                            "retrieved_at",
                            Instant.now(),
                            "retrieval_method",
                            "KEYWORD"));
    }

    private void saveDraftCard(long userId, Map<String, Object> run, long draftId) {
        insertAssistant(
                userId,
                run,
                "RECORD_EXTRACT".equals(run.get("kind")) ? "RECORD_CARD" : "PLAN_CARD",
                "草稿已生成，请核对字段并查看预览，确认后才会应用。",
                draftId);
    }

    private long insertAssistant(
            long userId, Map<String, Object> run, String type, String text, Long draftId) {
        if (run.get("conversation_id") == null) throw invalid("对话生成缺少会话");
        long conversationId = Rows.number(run, "conversation_id");
        Map<String, Object> conversation = sql.owned("ai_conversation", conversationId, userId);
        long messageId =
                sql.insert(
                        "ai_message",
                        Rows.map(
                                "user_id",
                                userId,
                                "conversation_id",
                                conversationId,
                                "client_message_id",
                                UUID.randomUUID().toString(),
                                "role",
                                "ASSISTANT",
                                "message_type",
                                type,
                                "content_cipher",
                                crypto.encrypt(text),
                                "encryption_key_ref",
                                crypto.keyRef(),
                                "delivery_status",
                                "READY",
                                "reply_to_message_id",
                                run.get("input_message_id"),
                                "ai_run_id",
                                Rows.number(run, "id"),
                                "draft_id",
                                draftId,
                                "finish_reason",
                                "STOP",
                                "content_digest",
                                crypto.digest(text)));
        sql.update(
                "ai_conversation",
                conversationId,
                Rows.number(conversation, "row_version"),
                Rows.map(
                        "last_message_at",
                        Instant.now(),
                        "last_message_id",
                        messageId,
                        "message_count",
                        Rows.integer(conversation, "message_count") + 1));
        return messageId;
    }

    private Map<String, Object> accepted(MutationResult result) {
        Map<String, Object> row = sql.owned("ai_run", Ids.parse(result.id()), AuthContext.userId());
        return Rows.map(
                "mutation",
                result,
                "runId",
                result.id(),
                "messageId",
                row.get("input_message_id"),
                "conversationId",
                row.get("conversation_id"),
                "status",
                row.get("status"),
                "pollAfterSeconds",
                2,
                "streamPath",
                "/api/v1/ai/runs/" + result.id() + "/events");
    }

    private void assertFeature(String kind) {
        String flag =
                switch (kind) {
                    case "PLAN_GENERATE", "PLAN_ADJUST" -> "feature.ai_plan";
                    case "RECORD_EXTRACT" -> "feature.ai_record";
                    case "CHAT" -> "feature.ai_chat";
                    case "WEEKLY_REVIEW" -> "feature.weekly_review";
                    default ->
                            throw BusinessException.unavailable("FEATURE_NOT_ENABLED", "该智能能力尚未启用");
                };
        boolean enabled =
                sql.find(
                                "SELECT value_json FROM app_configuration WHERE environment=? AND"
                                    + " config_key=? AND is_enabled=1 AND deleted_at IS NULL",
                                properties.getEnvironment(),
                                flag)
                        .map(
                                row ->
                                        Rows.json(row, "value_json")
                                                .path("booleanValue")
                                                .asBoolean(false))
                        .orElse(false);
        if (!enabled) {
            throw BusinessException.unavailable("FEATURE_NOT_ENABLED", "该智能能力尚未启用");
        }
    }

    private Map<String, Object> conversationView(Map<String, Object> row) {
        return Rows.map(
                "id",
                Rows.number(row, "id"),
                "rowVersion",
                Rows.number(row, "row_version"),
                "title",
                crypto.decrypt(row.get("title_cipher")),
                "scene",
                row.get("scene"),
                "status",
                row.get("status"),
                "lastMessageAt",
                Rows.instant(row, "last_message_at"),
                "createdAt",
                Rows.instant(row, "created_at"));
    }

    private Map<String, Object> messageView(Map<String, Object> row) {
        List<Map<String, Object>> citations =
                sql
                        .query(
                                "SELECT c.*,v.article_id,s.title"
                                    + " source_title,s.canonical_url,s.verification_status,s.accessed_at"
                                    + " FROM ai_message_citation c JOIN knowledge_article_version v"
                                    + " ON v.id=c.article_version_id JOIN knowledge_source s ON"
                                    + " s.id=c.knowledge_source_id WHERE c.user_id=? AND"
                                    + " c.message_id=? ORDER BY c.citation_no",
                                AuthContext.userId(),
                                Rows.number(row, "id"))
                        .stream()
                        .map(
                                r ->
                                        Rows.map(
                                                "id",
                                                Rows.number(r, "id"),
                                                "label",
                                                "[" + Rows.integer(r, "citation_no") + "]",
                                                "articleId",
                                                Rows.number(r, "article_id"),
                                                "articleVersionId",
                                                Rows.number(r, "article_version_id"),
                                                "sourceId",
                                                Rows.number(r, "knowledge_source_id"),
                                                "sourceTitle",
                                                r.get("source_title"),
                                                "sourceUrl",
                                                r.get("canonical_url"),
                                                "locator",
                                                r.get("section_anchor"),
                                                "verifiedAt",
                                                Rows.instant(r, "accessed_at"),
                                                "unavailable",
                                                !"VERIFIED".equals(r.get("verification_status"))))
                        .toList();
        String type =
                switch (Rows.text(row, "message_type")) {
                    case "CLARIFICATION" -> "QUESTION";
                    case "PLAN_CARD", "RECORD_CARD" -> "DRAFT_CARD";
                    case "SOURCE_CARD" -> "SOURCE_CARD";
                    case "ERROR_NOTICE" -> "ERROR";
                    default -> "TEXT";
                };
        return Rows.map(
                "id",
                Rows.number(row, "id"),
                "conversationId",
                Rows.number(row, "conversation_id"),
                "clientMessageId",
                row.get("client_message_id"),
                "role",
                row.get("role"),
                "content",
                crypto.decrypt(row.get("content_cipher")),
                "contentType",
                type,
                "runId",
                row.get("ai_run_id"),
                "draftId",
                row.get("draft_id"),
                "citations",
                citations,
                "createdAt",
                Rows.instant(row, "created_at"),
                "final",
                Set.of("READY", "FAILED", "CANCELLED").contains(Rows.text(row, "delivery_status")));
    }

    private Map<String, Object> taskProjection(Map<String, Object> task) {
        return Rows.map(
                "taskId",
                Rows.number(task, "id"),
                "expectedTaskRowVersion",
                Rows.number(task, "row_version"),
                "title",
                task.get("title"),
                "taskType",
                task.get("task_type"),
                "scheduledStartAt",
                Rows.instant(task, "scheduled_start_at"),
                "scheduledEndAt",
                Rows.instant(task, "scheduled_end_at"),
                "plannedDurationMinutes",
                task.get("planned_duration_minutes"),
                "taskTimeZone",
                task.get("task_time_zone"),
                "isFixed",
                Rows.bool(task, "is_fixed"),
                "status",
                task.get("status"));
    }

    private Map<String, Object> ref(Map<String, Object> row, String table) {
        return Rows.map(
                "id",
                Rows.number(row, "id"),
                "rowVersion",
                Rows.number(row, "row_version"),
                "table",
                table);
    }

    private void finishOutbox(long userId, long runId, String state) {
        sql.execute(
                "UPDATE outbox_event SET"
                    + " status=?,processed_at=?,lease_owner=NULL,lease_until=NULL,updated_at=?,row_version=row_version+1"
                    + " WHERE user_id=? AND event_type='AI_RUN_QUEUED' AND aggregate_id=?",
                state,
                Rows.utc(Instant.now()),
                Rows.utc(Instant.now()),
                userId,
                runId);
    }

    private void expected(Map<String, Object> row, String version) {
        if (Rows.number(row, "row_version") != Ids.parse(version))
            throw BusinessException.conflict("ROW_VERSION_CONFLICT", "对象已更新，请刷新");
    }

    private void requireSeq(String expected) {
        long value = "0".equals(expected) ? 0 : Ids.parse(expected);
        if (value != mutations.currentSeq())
            throw BusinessException.conflict("SOURCE_CHANGED", "数据已更新，请刷新后生成");
    }

    private void pageLimit(int value) {
        if (value < 1 || value > 100) throw invalid("分页数量应为1至100");
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw invalid("内容无法保存");
        }
    }

    private BusinessException invalid(String message) {
        return BusinessException.badRequest("OUTPUT_INVALID", message);
    }
}
