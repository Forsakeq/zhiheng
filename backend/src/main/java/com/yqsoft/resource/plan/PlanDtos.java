package com.yqsoft.resource.plan;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.*;
import java.util.List;
import java.util.UUID;

public final class PlanDtos {
    private PlanDtos() {}

    public enum PlanKind {
        DAY,
        WEEK,
        CUSTOM
    }

    public enum TaskType {
        ROUTINE,
        ACTIVITY,
        MEAL_HABIT,
        RECORDING,
        CUSTOM
    }

    public enum PlanStatus {
        DRAFT,
        ACTIVE,
        PAUSED,
        ARCHIVED
    }

    public enum TaskStatus {
        DRAFT,
        PENDING,
        COMPLETED,
        SKIPPED,
        CANCELLED
    }

    public record VersionBody(@NotBlank String expectedRowVersion) {}

    public record TaskVersionRef(@NotBlank String taskId, @NotBlank String expectedRowVersion) {}

    public record ReminderSnapshotInput(
            @NotNull Boolean enabled,
            @Min(-1440) @Max(0) Integer offsetMinutes,
            @Pattern(regexp = "LOCAL_SYSTEM|IN_APP") String channel,
            @NotBlank String timeZone,
            @Pattern(regexp = "SUPPRESS|NEXT_ALLOWED") String quietPolicy) {}

    public record TaskSnapshotInput(
            @NotNull UUID draftTaskKey,
            String taskId,
            String expectedTaskRowVersion,
            @NotBlank @Size(max = 100) String title,
            @NotNull TaskType taskType,
            @NotNull Instant scheduledStartAt,
            @NotNull Instant scheduledEndAt,
            @NotNull @Min(1) @Max(1440) Integer plannedDurationMinutes,
            @NotBlank String taskTimeZone,
            Boolean isFixed,
            @Min(1) @Max(5) Integer priority,
            @NotNull @Min(0) @Max(1000) Integer sortOrder,
            @Size(max = 1000) String instructions,
            @Valid ReminderSnapshotInput reminder,
            String ruleId,
            @Size(max = 80) String occurrenceKey) {}

    public record PlanQuery(PlanStatus status, String cursor, @Min(1) @Max(100) Integer limit) {}

    public record PlanCreate(
            @NotBlank @Size(max = 80) String title,
            @NotNull PlanKind planKind,
            @NotBlank String timeZone,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @Size(max = 5) List<@NotBlank String> goalIds) {}

    public record PlanVersionCreate(
            @NotBlank String expectedPlanRowVersion,
            String expectedActiveVersionId,
            String copyFromVersionId,
            @NotBlank @Size(max = 500) String changeReason,
            @Size(max = 500) List<@Valid TaskSnapshotInput> tasks) {}

    public record PlanVersionReplace(
            @NotBlank String expectedRowVersion,
            String expectedActiveVersionId,
            @NotBlank @Size(max = 500) String changeReason,
            @NotNull @Size(max = 500) List<@Valid TaskSnapshotInput> tasks) {}

    public record PlanValidationRequest(
            @NotBlank String expectedRowVersion,
            String expectedActiveVersionId,
            @NotNull Instant effectiveFrom,
            @NotNull @Size(max = 500) List<String> affectedTaskIds,
            Boolean allowFixedTaskChanges,
            @Size(max = 300) String fixedChangeReason) {}

    public record PlanScopeRequest(
            @NotBlank String expectedRowVersion,
            String expectedActiveVersionId,
            @NotNull Instant effectiveFrom,
            @NotNull @Size(max = 500) List<String> affectedTaskIds,
            Boolean allowFixedTaskChanges,
            @Size(max = 300) String fixedChangeReason,
            @NotNull @Size(max = 500) List<@Valid TaskVersionRef> expectedTasks) {}

    public record PlanActivate(
            @NotBlank String expectedRowVersion,
            String expectedActiveVersionId,
            @NotNull Instant effectiveFrom,
            @NotNull @Size(max = 500) List<String> affectedTaskIds,
            Boolean allowFixedTaskChanges,
            @Size(max = 300) String fixedChangeReason,
            @NotNull @Size(max = 500) List<@Valid TaskVersionRef> expectedTasks,
            @NotBlank String expectedDataSeq,
            @NotNull @AssertTrue Boolean userConfirmed) {
        public PlanScopeRequest scope() {
            return new PlanScopeRequest(
                    expectedRowVersion,
                    expectedActiveVersionId,
                    effectiveFrom,
                    affectedTaskIds,
                    allowFixedTaskChanges,
                    fixedChangeReason,
                    expectedTasks);
        }
    }

    public record PlanArchive(
            @NotBlank String expectedRowVersion,
            String expectedActiveVersionId,
            @NotNull Instant effectiveFrom,
            @NotNull @Size(max = 500) List<@Valid TaskVersionRef> affectedTasks,
            @NotNull @AssertTrue Boolean userConfirmed,
            @Size(max = 300) String reason,
            Boolean allowFixedTaskChanges,
            @Size(max = 300) String fixedChangeReason) {}

    public record PlanRestore(
            @NotBlank String expectedRowVersion,
            @NotBlank String expectedPlanRowVersion,
            String expectedActiveVersionId,
            @NotNull Instant effectiveFrom,
            @NotNull @Size(max = 500) List<String> affectedTaskIds,
            @NotBlank @Size(max = 300) String reason,
            @NotNull @AssertTrue Boolean userConfirmed) {}

    public record TaskQuery(
            @NotNull LocalDate from,
            @NotNull LocalDate to,
            List<TaskStatus> status,
            String planId,
            String timeZone,
            String cursor,
            @Min(1) @Max(100) Integer limit) {}

    public record TaskStateCommand(
            @NotBlank String expectedRowVersion,
            Instant performedAt,
            @Pattern(regexp = "DONE|NO_TIME|FORGOT|TOO_DIFFICULT|OTHER") String reasonCode,
            @Size(max = 300) String reasonText) {}

    public record TaskReopen(
            @NotBlank String expectedRowVersion, @NotBlank @Size(max = 300) String reason) {}

    public record TaskRecordLinksPut(
            @NotBlank String expectedRowVersion, @NotNull @Size(max = 20) List<String> recordIds) {}

    public record PlanRuleInput(
            @NotBlank @Size(max = 100) String title,
            @NotNull TaskType taskType,
            @NotNull @Size(min = 1, max = 7) List<@Min(1) @Max(7) Integer> weekdays,
            @NotNull LocalTime startTime,
            @NotNull LocalTime endTime,
            @Min(0) @Max(1) Integer endDayOffset,
            @NotNull @Min(1) @Max(1440) Integer plannedDurationMinutes,
            @NotBlank String timeZone,
            Boolean isFixed,
            @Size(max = 1000) String instructions,
            @Valid ReminderSnapshotInput reminder) {}

    public record PlanRuleCommand(
            @NotNull @Pattern(regexp = "CREATE|REPLACE|STOP") String action,
            String ruleId,
            String expectedRuleRowVersion,
            @NotBlank String expectedPlanRowVersion,
            String expectedActiveVersionId,
            @NotNull LocalDate effectiveFrom,
            @NotNull LocalDate effectiveTo,
            @Valid PlanRuleInput rule,
            @NotBlank @Size(max = 300) String reason) {}

    /** Internal typed bridge; caller still obtains all business checks and the same transaction. */
    public record ApplyScope(
            String expectedDataSeq,
            String expectedActiveVersionId,
            Instant effectiveFrom,
            List<TaskVersionRef> affectedTasks,
            Boolean allowFixedTaskChanges,
            String fixedChangeReason) {}

    public record DraftTaskChange(
            String action,
            UUID draftTaskKey,
            String taskId,
            String expectedTaskRowVersion,
            TaskSnapshotInput snapshot,
            String reason) {}

    public record PlanView(
            Long id,
            Long rowVersion,
            String title,
            String planKind,
            String timeZone,
            LocalDate startDate,
            LocalDate endDate,
            String status,
            Long activeVersionId,
            List<Long> goalIds,
            Instant updatedAt) {}

    public record PlanDetailView(
            @JsonUnwrapped PlanView plan,
            PlanVersionDetail activeVersion,
            List<PlanVersionView> draftVersions) {}

    public record PlanVersionView(
            Long id,
            Long rowVersion,
            Long planId,
            Long versionNo,
            String status,
            String changeReason,
            String sourceType,
            Instant createdAt,
            Instant sealedAt,
            boolean isActive,
            Integer taskCount) {}

    public record PlanVersionDetail(
            @JsonUnwrapped PlanVersionView version,
            Long baseActiveVersionId,
            List<TaskSnapshotInput> tasks,
            Long sourceDraftId,
            Instant effectiveFrom) {}

    public record ValidationIssue(
            String code,
            String severity,
            String fieldPath,
            String message,
            List<String> relatedResourceIds,
            String suggestedAction) {}

    public record PlanValidationView(
            boolean valid,
            List<ValidationIssue> issues,
            Long checkedVersionId,
            Long checkedRowVersion,
            Long checkedActiveVersionId,
            Long checkedDataSeq,
            Instant checkedAt,
            Instant expiresAt) {}

    public record PlanChangeView(
            String action,
            String taskId,
            UUID draftTaskKey,
            TaskSnapshotInput before,
            TaskSnapshotInput after,
            String reason,
            boolean fixedTaskChange) {}

    public record ReminderChangeView(
            String taskId,
            String action,
            String deliveryId,
            Instant fireAt,
            String channel,
            String reason) {}

    public record PlanPreview(
            PlanValidationView validation,
            List<PlanChangeView> changes,
            List<String> affectedTaskIds,
            List<String> unchangedHistoricalTaskIds,
            List<ReminderChangeView> reminderChanges,
            Instant effectiveFrom,
            boolean canApply) {}

    public record TaskView(
            Long id,
            Long rowVersion,
            Long planId,
            Long currentPlanVersionId,
            Long ruleId,
            String occurrenceKey,
            String title,
            String taskType,
            Instant scheduledStartAt,
            Instant scheduledEndAt,
            LocalDate taskLocalDate,
            String taskTimeZone,
            Integer plannedDurationMinutes,
            boolean isFixed,
            String status,
            Instant completedAt,
            Instant skippedAt,
            String sourceType,
            Instant updatedAt) {}

    public record TaskDetailView(
            @JsonUnwrapped TaskView task,
            String instructions,
            List<Long> linkedRecordIds,
            List<java.util.Map<String, Object>> reminderRules,
            TaskEventView lastEvent) {}

    public record TaskEventView(
            Long id,
            Long taskId,
            String eventType,
            String operationId,
            String fromStatus,
            String toStatus,
            String reasonCode,
            String reasonText,
            Instant clientOccurredAt,
            Instant receivedAt,
            String sourceType,
            Long taskRowVersion,
            Long planVersionId,
            Long planVersionTaskId) {}
}
