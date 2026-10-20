package com.yqsoft.service.profile;

import com.yqsoft.dao.SqlStore;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.resource.common.Rows;
import com.yqsoft.resource.plan.PlanDtos.TaskView;
import com.yqsoft.resource.profile.HomeView;
import com.yqsoft.resource.record.RecordDtos.*;
import com.yqsoft.security.AuthContext;
import com.yqsoft.service.plan.PlanService;
import com.yqsoft.service.record.RecordService;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class HomeService {
    private final ProfileService profiles;
    private final RecordService records;
    private final PlanService plans;
    private final SqlStore sql;

    public HomeService(
            ProfileService profiles, RecordService records, PlanService plans, SqlStore sql) {
        this.profiles = profiles;
        this.records = records;
        this.plans = plans;
        this.sql = sql;
    }

    public HomeView home(LocalDate requestedDate, String requestedZone) {
        String displayZone = requestedZone == null ? profiles.timeZone() : requestedZone;
        ZoneId zone;
        try {
            zone = ZoneId.of(displayZone);
        } catch (DateTimeException exception) {
            throw BusinessException.badRequest("TIME_ZONE_INVALID", "请使用有效IANA时区");
        }
        LocalDate day = requestedDate == null ? LocalDate.now(zone) : requestedDate;
        List<TaskView> tasks = plans.tasksOn(day);
        int pending = count(tasks, "PENDING"),
                completed = count(tasks, "COMPLETED"),
                skipped = count(tasks, "SKIPPED"),
                cancelled = count(tasks, "CANCELLED");
        int denominator = pending + completed + skipped;
        BigDecimal ratio =
                denominator == 0
                        ? null
                        : BigDecimal.valueOf(completed)
                                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
        TaskView next =
                tasks.stream()
                        .filter(task -> "PENDING".equals(task.status()))
                        .min(Comparator.comparing(TaskView::scheduledStartAt))
                        .orElse(null);
        List<RecordView> recent =
                records.list(
                                new RecordQuery(
                                        day.minusDays(29),
                                        day,
                                        null,
                                        null,
                                        false,
                                        "OCCURRED_DESC",
                                        null,
                                        5))
                        .items();
        CoverageView coverage = records.coverage(day, day, List.of(RecordType.values()));
        long user = AuthContext.userId();
        Map<String, Object> clock = sql.one("SELECT * FROM user_sync_clock WHERE user_id=?", user);
        Map<String, Object> installation =
                sql.owned("client_installation", AuthContext.current().installationId(), user);
        String capability = "UNVERIFIED";
        if ("DENIED".equals(Rows.text(installation, "notification_permission")))
            capability = "DENIED";
        else if ("UNSUPPORTED"
                .equals(
                        Rows.json(installation, "capabilities_json")
                                .path("localReminder")
                                .asText())) {
            capability = "UNSUPPORTED";
        }
        boolean stale =
                sql.count(
                                "SELECT COUNT(*) FROM daily_stat WHERE user_id=? AND"
                                        + " stat_local_date=? AND deleted_at IS NULL AND"
                                        + " metric_version='DAILY_V1' AND status='FRESH'",
                                user,
                                day)
                        == 0;
        return new HomeView(
                day,
                displayZone,
                new HomeView.TaskSummary(
                        pending, completed, skipped, cancelled, denominator, ratio, "DAILY_V1"),
                next,
                recent,
                coverage,
                capability,
                Rows.number(clock, "committed_seq"),
                Rows.number(clock, "dataset_epoch"),
                stale);
    }

    private int count(List<TaskView> tasks, String status) {
        return Math.toIntExact(tasks.stream().filter(task -> status.equals(task.status())).count());
    }
}
