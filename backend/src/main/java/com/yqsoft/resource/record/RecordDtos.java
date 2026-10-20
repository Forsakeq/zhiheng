package com.yqsoft.resource.record;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Public record contracts. Source and ownership are always server controlled. */
public final class RecordDtos {
    private RecordDtos() {}

    public enum RecordType {
        SLEEP,
        ACTIVITY,
        MEAL,
        ENERGY
    }

    public enum ActivityType {
        WALK,
        RUN,
        CYCLING,
        STRETCH,
        SPORT,
        OTHER
    }

    public enum Intensity {
        EASY,
        MODERATE,
        HARD
    }

    public enum MealType {
        BREAKFAST,
        LUNCH,
        DINNER,
        SNACK,
        OTHER
    }

    public enum MealContext {
        CANTEEN,
        HOME,
        TAKEAWAY,
        RESTAURANT,
        OTHER
    }

    public enum EnergyContext {
        WAKE,
        STUDY,
        ACTIVITY,
        EVENING,
        OTHER
    }

    public enum Color {
        TEAL,
        BLUE,
        AMBER,
        PURPLE,
        GRAY
    }

    @JsonTypeInfo(
            use = JsonTypeInfo.Id.NAME,
            include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "detailType",
            visible = true)
    @JsonSubTypes({
        @JsonSubTypes.Type(value = SleepDetailInput.class, name = "SLEEP"),
        @JsonSubTypes.Type(value = ActivityDetailInput.class, name = "ACTIVITY"),
        @JsonSubTypes.Type(value = MealDetailInput.class, name = "MEAL"),
        @JsonSubTypes.Type(value = EnergyDetailInput.class, name = "ENERGY")
    })
    public sealed interface DetailInput
            permits SleepDetailInput, ActivityDetailInput, MealDetailInput, EnergyDetailInput {
        String detailType();
    }

    public record SleepDetailInput(
            @NotNull @Pattern(regexp = "SLEEP") String detailType,
            @Min(1) @Max(5) Integer restedLevel,
            @Pattern(regexp = "MAIN|NAP") String sleepKind,
            @Size(max = 1000) String interruptionNote)
            implements DetailInput {}

    public record ActivityDetailInput(
            @NotNull @Pattern(regexp = "ACTIVITY") String detailType,
            @NotNull ActivityType activityType,
            @NotNull @Min(1) @Max(1440) Integer actualDurationMinutes,
            Intensity intensity,
            @Size(max = 80) String activityName,
            @DecimalMin("0") @DecimalMax("1000000") @Digits(integer = 7, fraction = 2)
                    BigDecimal distanceMeters,
            @Size(max = 200) String locationLabel)
            implements DetailInput {}

    public record MealDetailInput(
            @NotNull @Pattern(regexp = "MEAL") String detailType,
            @NotNull MealType mealType,
            @NotBlank @Size(max = 2000) String description,
            Boolean vegetablePresent,
            Boolean fruitPresent,
            Boolean proteinFoodPresent,
            Boolean wholeGrainPresent,
            @Min(1) @Max(5) Integer satietyLevel,
            MealContext mealContext)
            implements DetailInput {}

    public record EnergyDetailInput(
            @NotNull @Pattern(regexp = "ENERGY") String detailType,
            @NotNull @Min(1) @Max(5) Integer energyLevel,
            EnergyContext contextCode,
            @Size(max = 1000) String description)
            implements DetailInput {}

    public record RecordCreate(
            @NotNull UUID clientRecordId,
            @NotNull RecordType recordType,
            @NotNull Instant startedAt,
            Instant endedAt,
            @NotBlank String recordTimeZone,
            @NotNull LocalDate recordLocalDate,
            @NotNull @Min(-840) @Max(840) Integer utcOffsetMinutes,
            @Size(max = 2000) String notes,
            @Size(max = 10) List<@NotBlank String> tagIds,
            @NotNull @Valid DetailInput detail) {}

    public record RecordReplace(
            @NotBlank String expectedRowVersion,
            @NotNull Instant startedAt,
            Instant endedAt,
            @NotBlank String recordTimeZone,
            @NotNull LocalDate recordLocalDate,
            @NotNull @Min(-840) @Max(840) Integer utcOffsetMinutes,
            @Size(max = 2000) String notes,
            @Size(max = 10) List<@NotBlank String> tagIds,
            @NotNull @Valid DetailInput detail) {}

    public record VersionBody(@NotBlank String expectedRowVersion) {}

    public record RecordQuery(
            LocalDate from,
            LocalDate to,
            RecordType recordType,
            String tagId,
            Boolean includeDeleted,
            String sort,
            String cursor,
            @Min(1) @Max(100) Integer limit) {}

    public record RecordTagCreate(
            @NotBlank @Size(max = 24) String name,
            Color color,
            @Min(0) @Max(1000) Integer sortOrder) {}

    public record RecordTagPatch(
            @NotBlank String expectedRowVersion,
            @Size(min = 1, max = 24) String name,
            Color color,
            @Min(0) @Max(1000) Integer sortOrder) {}

    public record RecordTagsPut(
            @NotBlank String expectedRowVersion,
            @NotNull @Size(max = 10) List<@NotBlank String> tagIds) {}

    public record AttachmentCreate(
            @NotBlank String parentRecordRowVersion,
            @NotBlank String fileId,
            @Size(max = 200) String caption,
            @Min(0) @Max(20) Integer sortOrder) {}

    public record AttachmentRemove(@NotBlank String parentRecordRowVersion) {}

    public record RecordTagView(
            Long id, Long rowVersion, String name, String color, Integer sortOrder) {}

    public sealed interface DetailView
            permits SleepDetailView, ActivityDetailView, MealDetailView, EnergyDetailView {}

    public record SleepDetailView(
            String detailType,
            Integer intervalMinutes,
            Integer restedLevel,
            String sleepKind,
            String interruptionNote,
            boolean overlapWarning,
            String measurementMethod)
            implements DetailView {}

    public record ActivityDetailView(
            String detailType,
            String activityType,
            Integer actualDurationMinutes,
            String intensity,
            String activityName,
            BigDecimal distanceMeters,
            String locationLabel,
            String durationMethod)
            implements DetailView {}

    public record MealDetailView(
            String detailType,
            String mealType,
            String description,
            Boolean vegetablePresent,
            Boolean fruitPresent,
            Boolean proteinFoodPresent,
            Boolean wholeGrainPresent,
            Integer satietyLevel,
            String mealContext)
            implements DetailView {}

    public record EnergyDetailView(
            String detailType,
            Integer energyLevel,
            String energyLabel,
            String contextCode,
            String description,
            Integer scaleVersion)
            implements DetailView {}

    public record AttachmentView(
            Long id,
            Long parentRecordRowVersion,
            Long fileId,
            String caption,
            Integer sortOrder,
            String mediaType,
            Integer sizeBytes) {}

    public record RecordView(
            Long id,
            Long rowVersion,
            UUID clientRecordId,
            RecordType recordType,
            Instant startedAt,
            Instant endedAt,
            LocalDate recordLocalDate,
            String recordTimeZone,
            Integer utcOffsetMinutes,
            String sourceType,
            Long sourceConnectionId,
            String qualityStatus,
            String invalidReason,
            String notes,
            DetailView detail,
            List<Long> tagIds,
            List<AttachmentView> attachments,
            Instant createdAt,
            Instant updatedAt,
            Instant deletedAt) {}

    public record CoverageItem(
            RecordType recordType,
            Integer validDays,
            Integer validRecords,
            List<LocalDate> missingDays,
            BigDecimal coverageRatio) {}

    public record CoverageView(
            LocalDate from,
            LocalDate to,
            Integer totalDays,
            List<CoverageItem> items,
            Long dataAsOfSeq) {}
}
