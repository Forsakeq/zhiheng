package com.yqsoft.service.statistics;

import com.yqsoft.dao.SqlStore;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.persistence.MutationExecutor;
import com.yqsoft.persistence.WorkerGuard;
import com.yqsoft.resource.common.*;
import com.yqsoft.resource.statistics.StatisticsDtos;
import com.yqsoft.security.AuthContext;

import org.springframework.stereotype.Service;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class StatisticsService {
    public static final String METRIC_VERSION = "DAILY_V1";
    private final SqlStore sql;
    private final MutationExecutor mutations;
    private final WorkerGuard guard;

    public StatisticsService(SqlStore sql, MutationExecutor mutations, WorkerGuard guard) {
        this.sql = sql;
        this.mutations = mutations;
        this.guard = guard;
    }

    public record Snapshot(
            long userId,
            long epoch,
            long seq,
            String timeZone,
            LocalDate from,
            LocalDate to,
            Map<LocalDate, FactsCalculator.DailyFact> days) {}

    public Snapshot snapshot(long userId, LocalDate from, LocalDate to, int maxDays) {
        dateRange(from, to, maxDays);
        Map<String, Object> clock =
                sql.one("SELECT * FROM user_sync_clock WHERE user_id=?", userId);
        String zone =
                Rows.text(
                        sql.one(
                                "SELECT time_zone FROM user_profile WHERE user_id=? AND deleted_at"
                                        + " IS NULL",
                                userId),
                        "time_zone");
        List<Map<String, Object>> records =
                sql.query(
                        "SELECT"
                            + " r.*,s.elapsed_minutes,s.sleep_kind,a.actual_duration_minutes,e.energy_level"
                            + " FROM health_record r LEFT JOIN sleep_record_detail s ON"
                            + " s.record_id=r.id LEFT JOIN activity_record_detail a ON"
                            + " a.record_id=r.id LEFT JOIN energy_record_detail e ON"
                            + " e.record_id=r.id WHERE r.user_id=? AND r.record_local_date BETWEEN"
                            + " ? AND ? AND r.deleted_at IS NULL AND r.quality_status='VALID' ORDER"
                            + " BY r.id",
                        userId,
                        from,
                        to);
        List<Map<String, Object>> taskRows =
                sql.query(
                        "SELECT task_local_date,status,COUNT(*) amount FROM task WHERE user_id=?"
                            + " AND task_local_date BETWEEN ? AND ? AND deleted_at IS NULL AND"
                            + " status IN ('PENDING','COMPLETED','SKIPPED','CANCELLED') GROUP BY"
                            + " task_local_date,status",
                        userId,
                        from,
                        to);
        Map<LocalDate, List<FactsCalculator.Sample>> samples = new HashMap<>();
        for (Map<String, Object> r : records) {
            String type = Rows.text(r, "record_type");
            FactsCalculator.Sample sample =
                    new FactsCalculator.Sample(
                            Rows.number(r, "id"),
                            type,
                            Rows.instant(r, "started_at"),
                            Rows.instant(r, "ended_at"),
                            nullableInt(r, "actual_duration_minutes"),
                            Rows.text(r, "sleep_kind"),
                            nullableInt(r, "energy_level"),
                            Rows.text(r, "record_time_zone"));
            samples.computeIfAbsent(Rows.date(r, "record_local_date"), k -> new ArrayList<>())
                    .add(sample);
        }
        Map<LocalDate, Map<String, Integer>> counts = new HashMap<>();
        for (Map<String, Object> r : taskRows)
            counts.computeIfAbsent(Rows.date(r, "task_local_date"), k -> new HashMap<>())
                    .put(Rows.text(r, "status"), Rows.integer(r, "amount"));
        Map<LocalDate, FactsCalculator.DailyFact> days = new LinkedHashMap<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            Map<String, Integer> task = counts.getOrDefault(date, Map.of());
            days.put(
                    date,
                    FactsCalculator.calculate(
                            samples.getOrDefault(date, List.of()),
                            new FactsCalculator.TaskCounts(
                                    task.getOrDefault("PENDING", 0),
                                    task.getOrDefault("COMPLETED", 0),
                                    task.getOrDefault("SKIPPED", 0),
                                    task.getOrDefault("CANCELLED", 0))));
        }
        Map<String, Object> after =
                sql.one("SELECT * FROM user_sync_clock WHERE user_id=?", userId);
        if (Rows.number(clock, "committed_seq") != Rows.number(after, "committed_seq")
                || Rows.number(clock, "dataset_epoch") != Rows.number(after, "dataset_epoch")) {
            throw BusinessException.conflict("STATISTICS_SOURCE_CHANGED", "记录刚发生变化，请重试读取");
        }
        return new Snapshot(
                userId,
                Rows.number(clock, "dataset_epoch"),
                Rows.number(clock, "committed_seq"),
                zone,
                from,
                to,
                days);
    }

    public Map<String, Object> trends(
            LocalDate from, LocalDate to, List<String> metrics, String timeZone) {
        Snapshot snap = snapshot(AuthContext.userId(), from, to, 93);
        Set<String> known =
                Set.of(
                        "SLEEP_INTERVAL_MINUTES",
                        "ACTIVITY_MINUTES",
                        "TASK_COMPLETION",
                        "ENERGY_LEVEL");
        if (metrics == null
                || metrics.isEmpty()
                || metrics.size() > 4
                || !known.containsAll(metrics)
                || metrics.stream().distinct().count() != metrics.size())
            throw invalid("请选择不重复的有效指标");
        String displayZone = timeZone == null ? snap.timeZone() : checkedZone(timeZone);
        List<Map<String, Object>> series = new ArrayList<>();
        for (String metric : metrics) {
            List<Map<String, Object>> points = new ArrayList<>();
            double sum = 0;
            int validDays = 0;
            for (Map.Entry<LocalDate, FactsCalculator.DailyFact> entry : snap.days().entrySet()) {
                FactsCalculator.DailyFact fact = entry.getValue();
                Number value = metricValue(metric, fact);
                if (value != null) {
                    sum += value.doubleValue();
                    validDays++;
                }
                int samples =
                        switch (metric) {
                            case "SLEEP_INTERVAL_MINUTES" -> fact.recordCounts().get("SLEEP");
                            case "ACTIVITY_MINUTES" -> fact.recordCounts().get("ACTIVITY");
                            case "ENERGY_LEVEL" -> fact.energySamples();
                            default -> fact.tasks().denominator();
                        };
                boolean conflict =
                        (metric.equals("SLEEP_INTERVAL_MINUTES")
                                        && fact.qualityFlags().contains("MAIN_SLEEP_CONFLICT"))
                                || (metric.equals("ACTIVITY_MINUTES")
                                        && fact.qualityFlags()
                                                .contains("ACTIVITY_OVERLAP_EXCLUDED"));
                points.add(
                        Rows.map(
                                "date",
                                entry.getKey(),
                                "value",
                                value,
                                "sampleCount",
                                samples,
                                "valid",
                                value != null,
                                "coverageState",
                                conflict ? "CONFLICT" : value == null ? "MISSING" : "VALID",
                                "numerator",
                                metric.equals("TASK_COMPLETION") ? fact.tasks().completed() : null,
                                "denominator",
                                metric.equals("TASK_COMPLETION")
                                        ? fact.tasks().denominator()
                                        : null,
                                "sourceRecordIds",
                                fact.recordIds(),
                                "sourceTaskIds",
                                taskIds(snap.userId(), entry.getKey())));
            }
            series.add(
                    Rows.map(
                            "definition",
                            definition(metric),
                            "points",
                            points,
                            "validDays",
                            validDays,
                            "totalDays",
                            snap.days().size(),
                            "average",
                            validDays == 0 ? null : sum / validDays));
        }
        ensureCurrent(snap);
        return Rows.map(
                "from",
                from,
                "to",
                to,
                "businessDatePolicy",
                "FROZEN_LOCAL_DATE",
                "displayTimeZone",
                displayZone,
                "series",
                series,
                "dataAsOfSeq",
                snap.seq(),
                "computedAt",
                Instant.now(),
                "stale",
                false,
                "refreshJobId",
                null);
    }

    public Map<String, Object> daily(LocalDate date, String version) {
        if (version != null && !METRIC_VERSION.equals(version)) throw invalid("不支持的统计口径版本");
        rebuildForUser(AuthContext.userId(), date, date);
        return dailyView(
                sql.one(
                        "SELECT * FROM daily_stat WHERE user_id=? AND stat_local_date=? AND"
                                + " metric_version=? AND deleted_at IS NULL",
                        AuthContext.userId(),
                        date,
                        METRIC_VERSION));
    }

    public Map<String, Object> requestRebuild(StatisticsDtos.Rebuild request) {
        dateRange(request.from(), request.to(), 366);
        MutationResult result =
                mutations.mutate(
                        "STATISTICS_REBUILD",
                        request,
                        true,
                        () -> {
                            long id =
                                    sql.insert(
                                            "outbox_event",
                                            Rows.map(
                                                    "user_id",
                                                    AuthContext.userId(),
                                                    "event_key",
                                                    UUID.randomUUID().toString(),
                                                    "event_type",
                                                    "STATISTICS_REBUILD",
                                                    "aggregate_type",
                                                    "STATISTICS",
                                                    "aggregate_id",
                                                    AuthContext.userId(),
                                                    "payload_schema_version",
                                                    1,
                                                    "payload_json",
                                                    Rows.map(
                                                            "from",
                                                            request.from(),
                                                            "to",
                                                            request.to()),
                                                    "status",
                                                    "PENDING",
                                                    "available_at",
                                                    Instant.now(),
                                                    "attempt_count",
                                                    0,
                                                    "captured_dataset_epoch",
                                                    mutations.currentEpoch()));
                            return MutationResult.of("OUTBOX_EVENT", id, 1);
                        });
        return Rows.map(
                "jobId",
                result.id(),
                "jobType",
                "STATISTICS_REBUILD",
                "status",
                "PENDING",
                "pollPath",
                "/api/v1/statistics/days/" + request.from(),
                "pollAfterSeconds",
                2,
                "mutation",
                result);
    }

    public void rebuildForUser(long userId, LocalDate from, LocalDate to) {
        Snapshot snap = snapshot(userId, from, to, 366);
        guard.withUser(
                userId,
                snap.epoch(),
                null,
                null,
                () -> {
                    Map<String, Object> clock =
                            sql.one("SELECT * FROM user_sync_clock WHERE user_id=?", userId);
                    if (Rows.number(clock, "committed_seq") != snap.seq()) {
                        throw BusinessException.conflict(
                                "STATISTICS_SOURCE_CHANGED", "记录更新，统计将重新计算");
                    }
                    for (Map.Entry<LocalDate, FactsCalculator.DailyFact> day :
                            snap.days().entrySet()) {
                        FactsCalculator.DailyFact fact = day.getValue();
                        Map<String, Object> values =
                                Rows.map(
                                        "stat_time_zone",
                                        snap.timeZone(),
                                        "data_as_of_seq",
                                        snap.seq(),
                                        "dataset_epoch",
                                        snap.epoch(),
                                        "status",
                                        "FRESH",
                                        "computed_at",
                                        Instant.now(),
                                        "valid_record_count",
                                        fact.recordIds().size(),
                                        "coverage_json",
                                        coverageForDay(fact),
                                        "main_sleep_elapsed_minutes",
                                        fact.mainSleepMinutes(),
                                        "nap_elapsed_minutes",
                                        fact.napMinutes(),
                                        "actual_activity_minutes",
                                        fact.activityMinutes(),
                                        "meal_record_count",
                                        fact.meals(),
                                        "energy_sample_count",
                                        fact.energySamples(),
                                        "energy_level_sum",
                                        fact.energySum(),
                                        "task_denominator",
                                        fact.tasks().denominator(),
                                        "task_completed_count",
                                        fact.tasks().completed(),
                                        "task_skipped_count",
                                        fact.tasks().skipped(),
                                        "quality_flags_json",
                                        fact.qualityFlags(),
                                        "source_time_zones_json",
                                        fact.timeZones());
                        Optional<Map<String, Object>> old =
                                sql.find(
                                        "SELECT * FROM daily_stat WHERE user_id=? AND"
                                            + " stat_local_date=? AND metric_version=? FOR UPDATE",
                                        userId,
                                        day.getKey(),
                                        METRIC_VERSION);
                        if (old.isPresent()) {
                            if ("FRESH".equals(old.get().get("status"))
                                    && Rows.number(old.get(), "data_as_of_seq") == snap.seq()
                                    && Rows.number(old.get(), "dataset_epoch") == snap.epoch()
                                    && old.get().get("deleted_at") == null) continue;
                            values.put("deleted_at", null);
                            sql.update(
                                    "daily_stat",
                                    Rows.number(old.get(), "id"),
                                    Rows.number(old.get(), "row_version"),
                                    values);
                        } else {
                            values.putAll(
                                    Rows.map(
                                            "user_id",
                                            userId,
                                            "stat_local_date",
                                            day.getKey(),
                                            "metric_version",
                                            METRIC_VERSION));
                            sql.insert("daily_stat", values);
                        }
                    }
                    return null;
                });
    }

    public Map<String, Object> dailyView(Map<String, Object> row) {
        long userId = Rows.number(row, "user_id");
        LocalDate date = Rows.date(row, "stat_local_date");
        Snapshot current = snapshot(userId, date, date, 1);
        if (Rows.number(row, "dataset_epoch") != current.epoch()
                || Rows.number(row, "data_as_of_seq") != current.seq())
            throw BusinessException.conflict("STATISTICS_SOURCE_CHANGED", "统计来源刚发生变化，请重新读取");
        FactsCalculator.DailyFact fact = current.days().get(date);
        Map<String, Object> result =
                Rows.map(
                        "id",
                        Rows.number(row, "id"),
                        "rowVersion",
                        Rows.number(row, "row_version"),
                        "date",
                        date,
                        "metricDefinitionVersion",
                        METRIC_VERSION,
                        "dataAsOfSeq",
                        Rows.number(row, "data_as_of_seq"),
                        "computedAt",
                        Rows.instant(row, "computed_at"),
                        "stale",
                        !"FRESH".equals(row.get("status")),
                        "sleepIntervalMinutes",
                        row.get("main_sleep_elapsed_minutes"),
                        "activityMinutes",
                        row.get("actual_activity_minutes"),
                        "energyMedian",
                        fact.energyMedian(),
                        "taskSummary",
                        taskSummary(fact.tasks()),
                        "coverage",
                        coverage(current),
                        "recordIds",
                        fact.recordIds(),
                        "taskEventIds",
                        sql
                                .query(
                                        "SELECT e.id FROM task_event e JOIN task t ON"
                                                + " t.id=e.task_id WHERE e.user_id=? AND"
                                                + " t.task_local_date=?",
                                        userId,
                                        date)
                                .stream()
                                .map(r -> Rows.number(r, "id"))
                                .toList(),
                        "warnings",
                        fact.qualityFlags());
        ensureCurrent(current);
        return result;
    }

    public List<Map<String, Object>> coverage(Snapshot snap) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String type : List.of("SLEEP", "ACTIVITY", "MEAL", "ENERGY")) {
            List<LocalDate> missing =
                    snap.days().entrySet().stream()
                            .filter(e -> e.getValue().recordCounts().get(type) == 0)
                            .map(Map.Entry::getKey)
                            .toList();
            int records =
                    snap.days().values().stream().mapToInt(f -> f.recordCounts().get(type)).sum();
            int valid = snap.days().size() - missing.size();
            result.add(
                    Rows.map(
                            "recordType",
                            type,
                            "validDays",
                            valid,
                            "validRecords",
                            records,
                            "missingDays",
                            missing,
                            "coverageRatio",
                            valid / (double) snap.days().size()));
        }
        return result;
    }

    public Map<String, Object> coverageView(Snapshot snap) {
        return Rows.map(
                "from",
                snap.from(),
                "to",
                snap.to(),
                "totalDays",
                snap.days().size(),
                "items",
                coverage(snap),
                "dataAsOfSeq",
                snap.seq());
    }

    private Map<String, Object> coverageForDay(FactsCalculator.DailyFact fact) {
        Map<String, Object> map = new LinkedHashMap<>();
        fact.recordCounts()
                .forEach(
                        (type, count) ->
                                map.put(type, Rows.map("validCount", count, "hasData", count > 0)));
        map.put("excludedRecordIds", fact.excludedRecordIds());
        return map;
    }

    public Map<String, Object> taskSummary(FactsCalculator.TaskCounts counts) {
        return Rows.map(
                "pending",
                counts.pending(),
                "completed",
                counts.completed(),
                "skipped",
                counts.skipped(),
                "cancelled",
                counts.cancelled(),
                "executionDenominator",
                counts.denominator(),
                "completionRate",
                counts.denominator() == 0
                        ? null
                        : counts.completed() / (double) counts.denominator(),
                "metricDefinitionVersion",
                METRIC_VERSION);
    }

    private Number metricValue(String metric, FactsCalculator.DailyFact fact) {
        return switch (metric) {
            case "SLEEP_INTERVAL_MINUTES" -> fact.mainSleepMinutes();
            case "ACTIVITY_MINUTES" -> fact.activityMinutes();
            case "ENERGY_LEVEL" -> fact.energyMedian();
            default ->
                    fact.tasks().denominator() == 0
                            ? null
                            : fact.tasks().completed() / (double) fact.tasks().denominator();
        };
    }

    private Map<String, Object> definition(String metric) {
        String label =
                switch (metric) {
                    case "SLEEP_INTERVAL_MINUTES" -> "主睡眠填写区间";
                    case "ACTIVITY_MINUTES" -> "已记录实际活动";
                    case "ENERGY_LEVEL" -> "主观精力中位等级";
                    default -> "任务完成比例";
                };
        String unit =
                switch (metric) {
                    case "TASK_COMPLETION" -> "RATIO";
                    case "ENERGY_LEVEL" -> "LEVEL";
                    default -> "MINUTES";
                };
        return Rows.map(
                "code",
                metric,
                "version",
                METRIC_VERSION,
                "label",
                label,
                "unit",
                unit,
                "aggregation",
                "按冻结业务日聚合有效记录；冲突排除，缺失保留空白；任务分母含待办、完成和跳过。",
                "missingPolicy",
                "NULL_NOT_ZERO",
                "sourceTypes",
                List.of("MANUAL", "AI_CONFIRMED", "EXTERNAL"));
    }

    private List<Long> taskIds(long userId, LocalDate date) {
        return sql
                .query(
                        "SELECT id FROM task WHERE user_id=? AND task_local_date=? AND deleted_at"
                                + " IS NULL AND status IN ('PENDING','COMPLETED','SKIPPED')",
                        userId,
                        date)
                .stream()
                .map(r -> Rows.number(r, "id"))
                .toList();
    }

    private void ensureCurrent(Snapshot snapshot) {
        Map<String, Object> latest =
                sql.one(
                        "SELECT dataset_epoch,committed_seq FROM user_sync_clock WHERE user_id=?",
                        snapshot.userId());
        if (Rows.number(latest, "dataset_epoch") != snapshot.epoch()
                || Rows.number(latest, "committed_seq") != snapshot.seq())
            throw BusinessException.conflict("STATISTICS_SOURCE_CHANGED", "统计来源刚发生变化，请重新读取");
    }

    public static void dateRange(LocalDate from, LocalDate to, int maxDays) {
        if (from == null
                || to == null
                || to.isBefore(from)
                || ChronoUnit.DAYS.between(from, to) + 1 > maxDays) throw invalid("日期范围无效或超过限制");
    }

    public static String checkedZone(String zone) {
        try {
            return ZoneId.of(zone).getId();
        } catch (DateTimeException ex) {
            throw invalid("无效时区");
        }
    }

    private Integer nullableInt(Map<String, Object> row, String key) {
        return row.get(key) == null ? null : Rows.integer(row, key);
    }

    private static BusinessException invalid(String message) {
        return BusinessException.badRequest("VALIDATION_FAILED", message);
    }
}
