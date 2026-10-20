package com.yqsoft.service.reminder;

import com.yqsoft.config.AppProperties;
import com.yqsoft.dao.SqlStore;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.resource.common.Rows;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;

@Component
public class ReminderJobWorker {
    private final SqlStore sql;
    private final ReminderService service;
    private final AppProperties properties;

    public ReminderJobWorker(SqlStore sql, ReminderService service, AppProperties properties) {
        this.sql = sql;
        this.service = service;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${zhiheng.reminder.poll-interval-ms:2000}")
    public void poll() {
        if (!properties.isJobsEnabled()) return;
        List<Map<String, Object>> events =
                sql.query(
                        "SELECT * FROM outbox_event WHERE event_type IN"
                            + " ('REMINDER_CANCEL','REMINDER_RULE_CHANGED','REMINDER_REBUILD_REQUESTED','INSTALLATION_REVOKED','CONSENT_WITHDRAWN')"
                            + " AND status IN ('PENDING','RETRY') AND available_at<=? ORDER BY id"
                            + " LIMIT 20",
                        Instant.now());
        for (Map<String, Object> event : events) {
            try {
                service.handleEvent(event);
            } catch (BusinessException ex) {
                retry(
                        event,
                        Set.of("DATA_EPOCH_CHANGED", "DATA_WRITE_FENCED").contains(ex.getCode())
                                ? "CANCELLED"
                                : "RETRY",
                        ex.getCode());
            } catch (Exception ex) {
                retry(event, "RETRY", "REMINDER_JOB_FAILED");
            }
        }
        service.dispatchInApp();
    }

    @Scheduled(fixedDelayString = "${zhiheng.reminder.refresh-interval-ms:60000}")
    public void refresh() {
        if (properties.isJobsEnabled()) service.refreshDaily();
    }

    private void retry(Map<String, Object> row, String status, String code) {
        int attempts = Rows.integer(row, "attempt_count") + 1;
        String safe =
                code != null && code.matches("[A-Z0-9_]{1,64}") ? code : "REMINDER_JOB_FAILED";
        sql.execute(
                "UPDATE outbox_event SET"
                    + " status=?,attempt_count=?,last_error_code=?,available_at=?,updated_at=?,row_version=row_version+1"
                    + " WHERE id=? AND status NOT IN ('DONE','CANCELLED')",
                attempts >= 5 && "RETRY".equals(status) ? "DEAD" : status,
                attempts,
                safe,
                Rows.utc(Instant.now().plusSeconds(30)),
                Rows.utc(Instant.now()),
                Rows.number(row, "id"));
    }
}
