package com.yqsoft.resource.profile;

import com.yqsoft.resource.plan.PlanDtos.TaskView;
import com.yqsoft.resource.record.RecordDtos.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record HomeView(
        LocalDate date,
        String displayTimeZone,
        TaskSummary taskSummary,
        TaskView nextTask,
        List<RecordView> recentRecords,
        CoverageView recordCoverage,
        String reminderCapability,
        long committedSeq,
        long datasetEpoch,
        boolean statsStale) {
    public record TaskSummary(
            int pending,
            int completed,
            int skipped,
            int cancelled,
            int executionDenominator,
            BigDecimal completionRate,
            String metricDefinitionVersion) {}
}
