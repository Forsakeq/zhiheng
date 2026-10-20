package com.yqsoft.service.statistics;

import com.fasterxml.jackson.databind.JsonNode;
import com.yqsoft.config.AppProperties;
import com.yqsoft.dao.SqlStore;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.persistence.WorkerGuard;
import com.yqsoft.resource.common.Rows;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;

@Component
public class StatisticsJobWorker {
    private final AppProperties properties;
    private final SqlStore sql;
    private final WorkerGuard guard;
    private final StatisticsService statistics;

    public StatisticsJobWorker(
            AppProperties properties,
            SqlStore sql,
            WorkerGuard guard,
            StatisticsService statistics) {
        this.properties = properties;
        this.sql = sql;
        this.guard = guard;
        this.statistics = statistics;
    }

    @Scheduled(fixedDelayString = "${zhiheng.statistics.poll-interval-ms:2000}")
    public void poll() {
        if (!properties.isJobsEnabled()) return;
        List<Map<String, Object>> jobs =
                sql.query(
                        "SELECT * FROM outbox_event WHERE event_type IN"
                            + " ('STAT_INVALIDATED','STATISTICS_REBUILD') AND ((status IN"
                            + " ('PENDING','RETRY') AND available_at<=?) OR (status='CLAIMED' AND"
                            + " lease_until<?)) ORDER BY id LIMIT 5",
                        Instant.now(),
                        Instant.now());
        for (Map<String, Object> job : jobs) {
            long id = Rows.number(job, "id");
            long user = Rows.number(job, "user_id");
            long epoch = Rows.number(job, "captured_dataset_epoch");
            try {
                boolean claimed =
                        guard.withUser(
                                user,
                                epoch,
                                null,
                                null,
                                () -> {
                                    Map<String, Object> current =
                                            sql.one(
                                                    "SELECT * FROM outbox_event WHERE id=? FOR"
                                                            + " UPDATE",
                                                    id);
                                    if (!Set.of("PENDING", "RETRY", "CLAIMED")
                                            .contains(Rows.text(current, "status"))) return false;
                                    if ("CLAIMED".equals(current.get("status"))
                                            && Rows.instant(current, "lease_until")
                                                    .isAfter(Instant.now())) return false;
                                    sql.update(
                                            "outbox_event",
                                            id,
                                            Rows.number(current, "row_version"),
                                            Rows.map(
                                                    "status",
                                                    "CLAIMED",
                                                    "lease_owner",
                                                    "statistics-worker",
                                                    "lease_until",
                                                    Instant.now().plusSeconds(60),
                                                    "attempt_count",
                                                    Rows.integer(current, "attempt_count") + 1));
                                    return true;
                                });
                if (!claimed) continue;
                for (LocalDate[] range : dateRanges(Rows.json(job, "payload_json")))
                    statistics.rebuildForUser(user, range[0], range[1]);
                guard.withUser(
                        user,
                        epoch,
                        null,
                        null,
                        () -> {
                            sql.execute(
                                    "UPDATE outbox_event SET"
                                        + " status='DONE',processed_at=?,lease_owner=NULL,lease_until=NULL,updated_at=?,row_version=row_version+1"
                                        + " WHERE id=? AND status='CLAIMED'",
                                    Rows.utc(Instant.now()),
                                    Rows.utc(Instant.now()),
                                    id);
                            return null;
                        });
            } catch (BusinessException ex) {
                boolean stale =
                        Set.of("DATA_EPOCH_CHANGED", "DATA_WRITE_FENCED").contains(ex.getCode());
                retry(
                        job,
                        stale
                                ? "CANCELLED"
                                : Rows.integer(job, "attempt_count") >= 4 ? "DEAD" : "RETRY",
                        ex.getCode());
            } catch (Exception ex) {
                retry(
                        job,
                        Rows.integer(job, "attempt_count") >= 4 ? "DEAD" : "RETRY",
                        "STATISTICS_REBUILD_FAILED");
            }
        }
    }

    static List<LocalDate[]> dateRanges(JsonNode payload) {
        if (payload == null) throw BusinessException.badRequest("JOB_PAYLOAD_INVALID", "统计日期缺失");
        if (payload.hasNonNull("from") && payload.hasNonNull("to")) {
            LocalDate from = LocalDate.parse(payload.path("from").asText());
            LocalDate to = LocalDate.parse(payload.path("to").asText());
            StatisticsService.dateRange(from, to, 366);
            return List.<LocalDate[]>of(new LocalDate[] {from, to});
        }
        List<LocalDate> dates = new ArrayList<>();
        if (payload.path("dates").isArray())
            payload.path("dates").forEach(n -> dates.add(LocalDate.parse(n.asText())));
        for (String key : List.of("date", "localDate", "oldDate", "newDate"))
            if (payload.hasNonNull(key)) dates.add(LocalDate.parse(payload.path(key).asText()));
        if (dates.isEmpty()) throw BusinessException.badRequest("JOB_PAYLOAD_INVALID", "统计日期缺失");
        return dates.stream().distinct().sorted().map(day -> new LocalDate[] {day, day}).toList();
    }

    private void retry(Map<String, Object> job, String status, String code) {
        String safeCode = code == null || code.length() > 64 ? "STATISTICS_REBUILD_FAILED" : code;
        sql.execute(
                "UPDATE outbox_event SET"
                    + " status=?,available_at=?,last_error_code=?,lease_owner=NULL,lease_until=NULL,updated_at=?,row_version=row_version+1"
                    + " WHERE id=? AND status<>'DONE'",
                status,
                Rows.utc(Instant.now().plusSeconds(30)),
                safeCode,
                Rows.utc(Instant.now()),
                Rows.number(job, "id"));
    }
}
