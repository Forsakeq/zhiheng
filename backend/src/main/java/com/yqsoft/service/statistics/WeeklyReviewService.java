package com.yqsoft.service.statistics;

import com.fasterxml.jackson.databind.JsonNode;
import com.yqsoft.dao.SqlStore;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.resource.common.*;
import com.yqsoft.security.AuthContext;
import com.yqsoft.service.ai.EncryptedJson;
import com.yqsoft.service.common.CryptoService;
import com.yqsoft.service.common.CursorService;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
public class WeeklyReviewService {
    private final SqlStore sql;
    private final StatisticsService statistics;
    private final CryptoService crypto;
    private final EncryptedJson encrypted;
    private final CursorService cursors;

    public WeeklyReviewService(
            SqlStore sql,
            StatisticsService statistics,
            CryptoService crypto,
            EncryptedJson encrypted,
            CursorService cursors) {
        this.sql = sql;
        this.statistics = statistics;
        this.crypto = crypto;
        this.encrypted = encrypted;
        this.cursors = cursors;
    }

    public record Prepared(long reviewId, Long existingRunId, Map<String, Object> context) {}

    /** Executed in the caller's user-clock transaction; this never calls a model. */
    public Prepared prepare(long userId, LocalDate start, boolean forceNew) {
        LocalDate end = start.plusDays(6);
        statistics.rebuildForUser(userId, start, end);
        StatisticsService.Snapshot snapshot = statistics.snapshot(userId, start, end, 7);
        if (!forceNew) {
            Optional<Map<String, Object>> old =
                    sql.find(
                            "SELECT * FROM weekly_review WHERE user_id=? AND week_start_date=? AND"
                                + " metric_version=? AND dataset_epoch=? AND data_as_of_seq=? AND"
                                + " deleted_at IS NULL AND status IN ('GENERATING','READY') ORDER"
                                + " BY revision_no DESC LIMIT 1",
                            userId,
                            start,
                            StatisticsService.METRIC_VERSION,
                            snapshot.epoch(),
                            snapshot.seq());
            if (old.isPresent() && old.get().get("ai_run_id") != null)
                return new Prepared(
                        Rows.number(old.get(), "id"),
                        Rows.number(old.get(), "ai_run_id"),
                        Map.of());
        }
        int revision =
                (int)
                        sql.count(
                                "SELECT COALESCE(MAX(revision_no),0)+1 FROM weekly_review WHERE"
                                        + " user_id=? AND week_start_date=? AND metric_version=?",
                                userId,
                                start,
                                StatisticsService.METRIC_VERSION);
        long id =
                sql.insert(
                        "weekly_review",
                        Rows.map(
                                "user_id",
                                userId,
                                "week_start_date",
                                start,
                                "week_end_date",
                                end,
                                "review_time_zone",
                                snapshot.timeZone(),
                                "revision_no",
                                revision,
                                "status",
                                "GENERATING",
                                "metric_version",
                                StatisticsService.METRIC_VERSION,
                                "data_as_of_seq",
                                snapshot.seq(),
                                "dataset_epoch",
                                snapshot.epoch(),
                                "coverage_json",
                                statistics.coverageView(snapshot)));
        List<Map<String, Object>> daily =
                sql.query(
                        "SELECT"
                            + " id,row_version,stat_local_date,main_sleep_elapsed_minutes,actual_activity_minutes,task_denominator,task_completed_count"
                            + " FROM daily_stat WHERE user_id=? AND stat_local_date BETWEEN ? AND ?"
                            + " AND metric_version=? ORDER BY stat_local_date",
                        userId,
                        start,
                        end,
                        StatisticsService.METRIC_VERSION);
        List<String> codes =
                sql
                        .query(
                                "SELECT code FROM goal_definition WHERE status='ACTIVE' AND"
                                        + " deleted_at IS NULL ORDER BY sort_order,id")
                        .stream()
                        .map(r -> Rows.text(r, "code"))
                        .toList();
        List<Map<String, Object>> values =
                daily.stream()
                        .map(
                                r ->
                                        Rows.map(
                                                "date",
                                                Rows.date(r, "stat_local_date"),
                                                "sleepIntervalMinutes",
                                                r.get("main_sleep_elapsed_minutes"),
                                                "activityMinutes",
                                                r.get("actual_activity_minutes"),
                                                "taskDenominator",
                                                Rows.integer(r, "task_denominator"),
                                                "taskCompleted",
                                                Rows.integer(r, "task_completed_count")))
                        .toList();
        List<Map<String, Object>> versions =
                daily.stream()
                        .map(
                                r ->
                                        Rows.map(
                                                "id",
                                                Rows.number(r, "id"),
                                                "rowVersion",
                                                Rows.number(r, "row_version")))
                        .toList();
        return new Prepared(
                id,
                null,
                Rows.map(
                        "weeklyReviewId",
                        id,
                        "weeklyStatistics",
                        values,
                        "statVersions",
                        versions,
                        "availableGoalCodes",
                        codes));
    }

    public void attachRun(long reviewId, long runId) {
        Map<String, Object> row = sql.one("SELECT * FROM weekly_review WHERE id=?", reviewId);
        sql.update(
                "weekly_review",
                reviewId,
                Rows.number(row, "row_version"),
                Rows.map("ai_run_id", runId));
    }

    /**
     * All numbers and evidence are computed by the server; the model only chooses known goal codes.
     */
    public void complete(long userId, long runId, JsonNode input, JsonNode output) {
        Map<String, Object> row =
                sql.one(
                        "SELECT * FROM weekly_review WHERE user_id=? AND ai_run_id=? AND deleted_at"
                                + " IS NULL FOR UPDATE",
                        userId,
                        runId);
        if ("READY".equals(row.get("status"))) return;
        long sourceSeq = input.path("base").path("sourceDataSeq").asLong(-1);
        if (sourceSeq
                != sql.count("SELECT committed_seq FROM user_sync_clock WHERE user_id=?", userId))
            throw BusinessException.conflict("SOURCE_CHANGED", "回顾依据已变化，请重新生成");
        JsonNode codesNode = output.path("suggestedGoalCodes");
        if (!codesNode.isArray() || codesNode.size() > 3) throw invalid();
        Set<String> allowed = new HashSet<>();
        input.path("availableGoalCodes").forEach(n -> allowed.add(n.asText()));
        List<String> codes = new ArrayList<>();
        for (JsonNode code : codesNode) {
            if (!code.isTextual()
                    || !allowed.contains(code.asText())
                    || codes.contains(code.asText())) throw invalid();
            codes.add(code.asText());
        }
        List<Map<String, Object>> daily = new ArrayList<>();
        for (JsonNode ref : input.path("statVersions")) {
            Map<String, Object> stat =
                    sql.owned("daily_stat", Ids.parse(ref.path("id").asText()), userId);
            if (Rows.number(stat, "row_version") != Ids.parse(ref.path("rowVersion").asText())
                    || !"FRESH".equals(stat.get("status"))) {
                throw BusinessException.conflict("SOURCE_CHANGED", "统计依据已过期，请重新生成");
            }
            daily.add(stat);
        }
        int activityDays = 0;
        int activityMinutes = 0;
        int sleepDays = 0;
        int sleepMinutes = 0;
        int completed = 0;
        int denominator = 0;
        long reviewId = Rows.number(row, "id");
        List<String> highlights = new ArrayList<>();
        for (Map<String, Object> day : daily) {
            if (day.get("actual_activity_minutes") != null) {
                activityDays++;
                activityMinutes += Rows.integer(day, "actual_activity_minutes");
                evidence(
                        userId,
                        reviewId,
                        day,
                        "activity.total",
                        "MINUTES",
                        Rows.integer(day, "actual_activity_minutes"));
            }
            if (day.get("main_sleep_elapsed_minutes") != null) {
                sleepDays++;
                sleepMinutes += Rows.integer(day, "main_sleep_elapsed_minutes");
                evidence(
                        userId,
                        reviewId,
                        day,
                        "sleep.interval",
                        "MINUTES",
                        Rows.integer(day, "main_sleep_elapsed_minutes"));
            }
            completed += Rows.integer(day, "task_completed_count");
            denominator += Rows.integer(day, "task_denominator");
            if (Rows.integer(day, "task_denominator") > 0)
                evidence(
                        userId,
                        reviewId,
                        day,
                        "task.completed",
                        "COUNT",
                        Rows.integer(day, "task_completed_count"));
        }
        highlights.add(
                activityDays == 0
                        ? "本周没有符合口径的实际活动记录。"
                        : "有实际活动记录 " + activityDays + " 天，记录合计 " + activityMinutes + " 分钟。");
        highlights.add(
                sleepDays == 0
                        ? "本周没有可用于汇总的主睡眠填写区间。"
                        : "有明确主睡眠填写区间 "
                                + sleepDays
                                + " 天，已记录日平均 "
                                + (sleepMinutes / sleepDays)
                                + " 分钟。");
        highlights.add(
                denominator == 0
                        ? "本周没有进入执行集合的任务。"
                        : "完成 " + completed + " 项，执行集合共 " + denominator + " 项；跳过仍在分母。");
        String summary = String.join("\n", highlights) + "\n缺失记录不代表没有发生相应行为，以上内容不用于医学诊断。";
        List<String> suggestions = new ArrayList<>();
        for (String code : codes) {
            Optional<Map<String, Object>> goal =
                    sql.find(
                            "SELECT name FROM goal_definition WHERE code=? AND status='ACTIVE' AND"
                                    + " deleted_at IS NULL",
                            code);
            if (goal.isPresent())
                suggestions.add("可选择下周目标：" + Rows.text(goal.get(), "name") + "；是否启用仍需你确认。");
        }
        sql.update(
                "weekly_review",
                reviewId,
                Rows.number(row, "row_version"),
                Rows.map(
                        "status",
                        "READY",
                        "summary_cipher",
                        crypto.encrypt(summary),
                        "encryption_key_ref",
                        crypto.keyRef(),
                        "suggestions_json",
                        encrypted.seal(
                                Rows.map(
                                        "highlights",
                                        highlights,
                                        "suggestions",
                                        suggestions,
                                        "goalCodes",
                                        codes)),
                        "generated_at",
                        Instant.now()));
    }

    public void failed(long runId) {
        sql.execute(
                "UPDATE weekly_review SET status='FAILED',updated_at=?,row_version=row_version+1"
                        + " WHERE ai_run_id=? AND status='GENERATING'",
                Rows.utc(Instant.now()),
                runId);
    }

    public PageResult<Map<String, Object>> list(
            LocalDate from, LocalDate to, String cursor, int limit) {
        if ((from == null) != (to == null) || limit < 1 || limit > 100)
            throw BusinessException.badRequest("VALIDATION_FAILED", "筛选条件无效");
        String scope = "weekly:" + from + ":" + to;
        Map<String, Object> position = cursors.decode(cursor, scope);
        ZoneId zone =
                ZoneId.of(
                        Rows.text(
                                sql.one(
                                        "SELECT time_zone FROM user_profile WHERE user_id=?",
                                        AuthContext.userId()),
                                "time_zone"));
        LocalDate last =
                to == null
                        ? position.containsKey("to")
                                ? LocalDate.parse(position.get("to").toString())
                                : LocalDate.now(zone)
                        : to;
        LocalDate first =
                from == null
                        ? position.containsKey("from")
                                ? LocalDate.parse(position.get("from").toString())
                                : last.minusWeeks(12)
                        : from;
        StatisticsService.dateRange(first, last, 732);
        long after = Rows.number(position, "id");
        List<Map<String, Object>> rows =
                sql.query(
                        "SELECT * FROM weekly_review WHERE user_id=? AND week_start_date BETWEEN ?"
                                + " AND ? AND id>? AND deleted_at IS NULL ORDER BY id LIMIT ?",
                        AuthContext.userId(),
                        first,
                        last,
                        after,
                        limit + 1);
        boolean more = rows.size() > limit;
        if (more) rows = rows.subList(0, limit);
        String next =
                more
                        ? cursors.encode(
                                scope,
                                Rows.map(
                                        "id",
                                        Rows.number(rows.getLast(), "id"),
                                        "from",
                                        first.toString(),
                                        "to",
                                        last.toString()))
                        : null;
        return PageResult.of(rows.stream().map(this::view).toList(), next, more);
    }

    public Map<String, Object> get(long id) {
        return view(sql.owned("weekly_review", id, AuthContext.userId()));
    }

    public PageResult<Map<String, Object>> evidence(long id, String cursor, int limit) {
        sql.owned("weekly_review", id, AuthContext.userId());
        if (limit < 1 || limit > 100)
            throw BusinessException.badRequest("VALIDATION_FAILED", "分页数量无效");
        String scope = "weekly-evidence:" + id;
        long after = Rows.number(cursors.decode(cursor, scope), "id");
        List<Map<String, Object>> rows =
                sql.query(
                        "SELECT * FROM weekly_review_evidence WHERE user_id=? AND review_id=? AND"
                                + " id>? ORDER BY id LIMIT ?",
                        AuthContext.userId(),
                        id,
                        after,
                        limit + 1);
        boolean more = rows.size() > limit;
        if (more) rows = rows.subList(0, limit);
        List<Map<String, Object>> items =
                rows.stream()
                        .map(
                                r -> {
                                    JsonNode value = Rows.json(r, "value_snapshot_json");
                                    return Rows.map(
                                            "id",
                                            Rows.number(r, "id"),
                                            "reviewId",
                                            id,
                                            "claimKey",
                                            r.get("claim_key"),
                                            "claimText",
                                            value.path("label").asText(),
                                            "evidenceType",
                                            r.get("evidence_type"),
                                            "evidenceId",
                                            r.get("daily_stat_id"),
                                            "sourceRowVersion",
                                            r.get("evidence_row_version"),
                                            "sourceDate",
                                            value.path("date").asText(),
                                            "value",
                                            value.get("value"),
                                            "unit",
                                            value.path("unit").asText());
                                })
                        .toList();
        String next =
                more
                        ? cursors.encode(scope, Rows.map("id", Rows.number(rows.getLast(), "id")))
                        : null;
        return PageResult.of(items, next, more);
    }

    private void evidence(
            long userId,
            long reviewId,
            Map<String, Object> stat,
            String metric,
            String unit,
            int value) {
        LocalDate date = Rows.date(stat, "stat_local_date");
        sql.insert(
                "weekly_review_evidence",
                Rows.map(
                        "user_id",
                        userId,
                        "review_id",
                        reviewId,
                        "claim_key",
                        metric + "." + date,
                        "evidence_type",
                        "DAILY_STAT",
                        "daily_stat_id",
                        Rows.number(stat, "id"),
                        "evidence_row_version",
                        Rows.number(stat, "row_version"),
                        "evidence_as_of_seq",
                        Rows.number(stat, "data_as_of_seq"),
                        "value_snapshot_json",
                        Rows.map(
                                "metric",
                                metric,
                                "date",
                                date,
                                "value",
                                value,
                                "unit",
                                unit,
                                "label",
                                "该日" + metric + "的真实统计依据")));
    }

    private Map<String, Object> view(Map<String, Object> row) {
        JsonNode stored = encrypted.open(Rows.json(row, "suggestions_json"));
        long seq =
                sql.count(
                        "SELECT committed_seq FROM user_sync_clock WHERE user_id=?",
                        Rows.number(row, "user_id"));
        boolean stale =
                "STALE".equals(row.get("status")) || seq != Rows.number(row, "data_as_of_seq");
        return Rows.map(
                "id",
                Rows.number(row, "id"),
                "rowVersion",
                Rows.number(row, "row_version"),
                "weekStart",
                Rows.date(row, "week_start_date"),
                "weekEnd",
                Rows.date(row, "week_end_date"),
                "status",
                stale && "READY".equals(row.get("status")) ? "STALE" : row.get("status"),
                "summary",
                crypto.decrypt(row.get("summary_cipher")),
                "highlights",
                stored == null ? List.of() : stored.path("highlights"),
                "adjustmentSuggestions",
                stored == null ? List.of() : stored.path("suggestions"),
                "coverage",
                Rows.json(row, "coverage_json"),
                "sourceDataSeq",
                Rows.number(row, "data_as_of_seq"),
                "metricDefinitionVersion",
                row.get("metric_version"),
                "stale",
                stale,
                "generatedAt",
                Rows.instant(row, "generated_at"),
                "aiRunId",
                row.get("ai_run_id"));
    }

    private BusinessException invalid() {
        return BusinessException.badRequest("OUTPUT_INVALID", "回顾建议包含未经核对的目标，未保存生成结果");
    }
}
