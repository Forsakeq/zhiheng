package com.yqsoft.resource.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.yqsoft.resource.plan.PlanDtos.DraftTaskChange;
import com.yqsoft.resource.plan.PlanDtos.TaskSnapshotInput;
import com.yqsoft.resource.plan.PlanDtos.TaskVersionRef;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class AiDtos {
    private AiDtos() {}

    public record ConversationCreate(
            @Size(max = 80) String title,
            @Pattern(regexp = "GENERAL|PLAN|RECORD|REVIEW") String scene) {}

    public record VersionBody(@NotBlank String expectedRowVersion) {}

    public record MessageCreate(
            @NotNull UUID clientMessageId,
            @NotBlank @Size(max = 4000) String text,
            @Size(max = 10) List<@NotBlank String> contextTaskIds,
            @Size(max = 10) List<@NotBlank String> contextRecordIds,
            Boolean allowKnowledgeSearch) {}

    public record RunCreate(
            @NotBlank
                    @Pattern(
                            regexp =
                                    "PLAN_GENERATE|PLAN_ADJUST|RECORD_EXTRACT|CHAT|WEEKLY_REVIEW|VOICE_TRANSCRIBE|COURSE_IMPORT")
                    String kind,
            String conversationId,
            @NotNull JsonNode input,
            @NotNull UUID clientRequestId) {}

    public record PlanGenerateInput(
            @NotEmpty @Size(max = 5) List<String> goalIds,
            @NotNull LocalDate from,
            @NotNull LocalDate to,
            @NotBlank String timeZone,
            @NotNull @Min(1) @Max(12) Integer dailyTaskLimit,
            @Size(max = 2000) String requestText,
            @NotBlank String expectedDataSeq,
            String planId,
            String expectedActiveVersionId) {}

    public record PlanAdjustInput(
            @NotBlank String planId,
            @NotBlank String expectedActiveVersionId,
            @NotBlank String expectedDataSeq,
            @NotNull Instant effectiveFrom,
            @NotEmpty @Size(max = 100) List<@Valid TaskVersionRef> affectedTasks,
            @NotBlank @Size(max = 2000) String requestText,
            Boolean allowFixedTaskChanges,
            @Size(max = 300) String fixedChangeReason) {}

    public record RecordExtractInput(
            @NotBlank @Size(max = 4000) String text,
            @NotNull Instant referenceInstant,
            @NotBlank String timeZone,
            @NotNull UUID clientRecordId) {}

    public record ChatInput(
            @NotBlank @Size(max = 4000) String text,
            @Size(max = 10) List<String> contextTaskIds,
            @Size(max = 10) List<String> contextRecordIds,
            Boolean allowKnowledgeSearch) {}

    public record WeeklyReviewInput(
            @NotNull LocalDate weekStart,
            @NotBlank String expectedDataSeq,
            @Size(max = 3) List<@Size(max = 80) String> requestedFocus) {}

    public record RunCancel(@NotBlank String expectedRowVersion, @Size(max = 200) String reason) {}

    public record RunRetry(@NotBlank String expectedRowVersion, @AssertTrue Boolean reuseInput) {}

    public record DraftRevision(
            @NotBlank String expectedRowVersion,
            @NotBlank String expectedDraftRevisionNo,
            @NotBlank @Pattern(regexp = "1") String payloadSchemaVersion,
            @NotNull JsonNode payload,
            @Size(max = 500) String editReason) {}

    public record DraftValidate(
            @NotBlank String expectedRowVersion,
            @NotBlank String expectedDraftRevisionNo,
            @NotBlank String expectedDataSeq) {}

    public record DraftApply(
            @NotBlank String expectedRowVersion,
            @NotBlank String expectedDraftRevisionNo,
            @NotBlank String expectedDataSeq,
            @NotNull @AssertTrue Boolean userConfirmed,
            String expectedActiveVersionId,
            Instant effectiveFrom,
            @Size(max = 500) List<@Valid TaskVersionRef> affectedTasks,
            Boolean allowFixedTaskChanges,
            @Size(max = 300) String fixedChangeReason,
            UUID clientRecordId) {}

    public record DraftCancel(
            @NotBlank String expectedRowVersion,
            @NotBlank String expectedDraftRevisionNo,
            @Size(max = 200) String reason) {}

    public record PlanCreatePayload(
            @NotBlank @Pattern(regexp = "PLAN_CREATE") String kind,
            @NotBlank @Size(max = 80) String planTitle,
            @NotBlank String timeZone,
            @NotNull LocalDate from,
            @NotNull LocalDate to,
            @NotEmpty @Size(max = 5) List<String> goalIds,
            @NotEmpty @Size(max = 168) List<@Valid TaskSnapshotInput> tasks,
            @Size(max = 1000) String explanation) {}

    public record PlanAdjustPayload(
            @NotBlank @Pattern(regexp = "PLAN_ADJUST") String kind,
            @NotBlank String planId,
            @NotBlank String baseActiveVersionId,
            @NotNull Instant effectiveFrom,
            @NotEmpty @Size(max = 100) List<@Valid DraftTaskChange> changes,
            @Size(max = 1000) String explanation) {}

    public record RecordPayload(
            @NotBlank @Pattern(regexp = "RECORD_EXTRACT") String kind,
            @NotNull JsonNode record,
            @NotNull @Size(max = 20) List<String> unresolvedFields,
            @NotNull @Size(max = 10) List<String> questions) {}
}
