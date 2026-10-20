package com.yqsoft.service.ai;

import com.yqsoft.config.AppProperties;
import com.yqsoft.dao.SqlStore;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.resource.common.Rows;

import jakarta.annotation.PreDestroy;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.*;

@Component
public class AiJobWorker {
    private final AppProperties properties;
    private final SqlStore sql;
    private final AiService service;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor =
            Executors.newFixedThreadPool(2, Thread.ofPlatform().name("ai-http-", 0).factory());

    public AiJobWorker(AppProperties properties, SqlStore sql, AiService service) {
        this.properties = properties;
        this.sql = sql;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${zhiheng.ai.poll-interval-ms:1500}")
    public void poll() {
        if (!properties.isJobsEnabled()) return;
        for (var expired :
                sql.query(
                        "SELECT id FROM ai_run WHERE deleted_at IS NULL AND status IN"
                            + " ('QUEUED','RUNNING','CANCEL_REQUESTED') AND timeout_at<? LIMIT 20",
                        Instant.now())) {
            service.fail(Rows.number(expired, "id"), "PROVIDER_TIMEOUT");
        }
        if (inFlight.size() >= 2) return;
        for (var candidate :
                sql.query(
                        "SELECT id FROM ai_run WHERE status='QUEUED' AND deleted_at IS NULL ORDER"
                                + " BY id LIMIT 10")) {
            long id = Rows.number(candidate, "id");
            if (inFlight.size() >= 2) break;
            if (!inFlight.add(id)) continue;
            executor.submit(
                    () -> {
                        try {
                            if (service.claim(id)) service.executeClaimed(id);
                        } catch (BusinessException ex) {
                            service.fail(id, ex.getCode());
                        } catch (Exception ex) {
                            service.fail(id, "OUTPUT_INVALID");
                        } finally {
                            inFlight.remove(id);
                        }
                    });
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
