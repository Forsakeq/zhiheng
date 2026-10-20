package com.yqsoft.resource.profile;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class ProfileDtos {
    private ProfileDtos() {}

    public record VersionBody(
            @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String expectedRowVersion) {}

    public record ConsentChange(
            @NotBlank @Pattern(regexp = "0|[1-9][0-9]{0,18}") String expectedRowVersion,
            @NotBlank @Pattern(regexp = "GRANT|WITHDRAW") String decision,
            @NotBlank @Size(max = 32) String policyVersion,
            Boolean deleteExisting,
            Instant clientDecisionAt) {}

    public record GoalCreate(
            @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String definitionId,
            @NotBlank @Size(max = 80) String title,
            @DecimalMin("0") @DecimalMax("1000000") @Digits(integer = 7, fraction = 2)
                    BigDecimal targetValue,
            @Pattern(regexp = "MINUTES|DAYS_PER_WEEK|TIMES_PER_WEEK|CUSTOM") String unit,
            @NotNull LocalDate startDate,
            LocalDate endDate,
            @Size(max = 64) String timeZone,
            @Min(1) @Max(5) Integer priority,
            @Size(min = 1, max = 500) String note) {}

    public record GoalClose(
            @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String expectedRowVersion,
            @NotNull LocalDate closedOn,
            @Size(min = 1, max = 300) String reason) {}

    public record AvailabilityRuleInput(
            @Min(1) @Max(7) int weekday,
            @NotBlank @Pattern(regexp = "BUSY|AVAILABLE|REST") String kind,
            @NotBlank @Pattern(regexp = "([01][0-9]|2[0-3]):[0-5][0-9]") String startTime,
            @NotBlank @Pattern(regexp = "([01][0-9]|2[0-3]):[0-5][0-9]") String endTime,
            @Min(0) @Max(1) Integer endDayOffset,
            @Size(min = 1, max = 80) String label,
            @NotBlank @Size(max = 64) String timeZone,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo) {}

    public record AvailabilityRuleReplace(
            @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String expectedRowVersion,
            @Min(1) @Max(7) int weekday,
            @NotBlank @Pattern(regexp = "BUSY|AVAILABLE|REST") String kind,
            @NotBlank @Pattern(regexp = "([01][0-9]|2[0-3]):[0-5][0-9]") String startTime,
            @NotBlank @Pattern(regexp = "([01][0-9]|2[0-3]):[0-5][0-9]") String endTime,
            @Min(0) @Max(1) Integer endDayOffset,
            @Size(min = 1, max = 80) String label,
            @NotBlank @Size(max = 64) String timeZone,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo) {
        public AvailabilityRuleInput input() {
            return new AvailabilityRuleInput(
                    weekday,
                    kind,
                    startTime,
                    endTime,
                    endDayOffset,
                    label,
                    timeZone,
                    effectiveFrom,
                    effectiveTo);
        }
    }

    public record ExceptionSlot(
            @NotBlank @Pattern(regexp = "BUSY|AVAILABLE|REST") String kind,
            @NotBlank @Pattern(regexp = "([01][0-9]|2[0-3]):[0-5][0-9]") String startTime,
            @NotBlank @Pattern(regexp = "([01][0-9]|2[0-3]):[0-5][0-9]") String endTime,
            @Min(0) @Max(1) Integer endDayOffset,
            @Size(min = 1, max = 80) String label,
            @Min(-840) @Max(840) Integer startOffsetMinutes,
            @Min(-840) @Max(840) Integer endOffsetMinutes) {}

    public record ExceptionPut(
            @NotBlank @Pattern(regexp = "0|[1-9][0-9]{0,18}") String expectedRowVersion,
            @NotBlank @Size(max = 64) String timeZone,
            @NotBlank @Pattern(regexp = "REPLACE_DAY|APPEND") String mode,
            @NotNull @Size(max = 24) List<@NotNull @Valid ExceptionSlot> slots,
            @Size(min = 1, max = 200) String reason) {}

    public record ExceptionSyncPut(
            @NotNull LocalDate date,
            @NotBlank @Pattern(regexp = "0|[1-9][0-9]{0,18}") String expectedRowVersion,
            @NotBlank @Size(max = 64) String timeZone,
            @NotBlank @Pattern(regexp = "REPLACE_DAY|APPEND") String mode,
            @NotNull @Size(max = 24) List<@NotNull @Valid ExceptionSlot> slots,
            @Size(min = 1, max = 200) String reason) {
        public ExceptionPut input() {
            return new ExceptionPut(expectedRowVersion, timeZone, mode, slots, reason);
        }
    }

    public record ExceptionSyncDelete(
            @NotNull LocalDate date,
            @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String expectedRowVersion) {}

    public static final class ProfilePatch {
        @JsonIgnore private final Set<String> presentFields = new HashSet<>();

        @NotBlank
        @Pattern(regexp = "[1-9][0-9]{0,18}")
        public String expectedRowVersion;

        @Size(min = 1, max = 40)
        public String nickname;

        @Pattern(regexp = "[1-9][0-9]{0,18}")
        public String avatarFileId;

        @Size(min = 1, max = 200)
        public String bio;

        @Size(min = 1, max = 64)
        public String timeZone;

        @Min(1)
        @Max(7)
        public Integer weekStartsOn;

        @Pattern(regexp = "GOALS|AVAILABILITY|PREFERENCES|COMPLETED")
        public String onboardingStep;

        @JsonIgnore
        public Set<String> presentFields() {
            return Set.copyOf(presentFields);
        }

        @JsonSetter("expectedRowVersion")
        public void setExpectedRowVersion(String value) {
            this.expectedRowVersion = value;
            presentFields.add("expectedRowVersion");
        }

        @JsonSetter("nickname")
        public void setNickname(String value) {
            this.nickname = value;
            presentFields.add("nickname");
        }

        @JsonSetter("avatarFileId")
        public void setAvatarFileId(String value) {
            this.avatarFileId = value;
            presentFields.add("avatarFileId");
        }

        @JsonSetter("bio")
        public void setBio(String value) {
            this.bio = value;
            presentFields.add("bio");
        }

        @JsonSetter("timeZone")
        public void setTimeZone(String value) {
            this.timeZone = value;
            presentFields.add("timeZone");
        }

        @JsonSetter("weekStartsOn")
        public void setWeekStartsOn(Integer value) {
            this.weekStartsOn = value;
            presentFields.add("weekStartsOn");
        }

        @JsonSetter("onboardingStep")
        public void setOnboardingStep(String value) {
            this.onboardingStep = value;
            presentFields.add("onboardingStep");
        }
    }

    public static final class PreferencePatch {
        @JsonIgnore private final Set<String> presentFields = new HashSet<>();

        @NotBlank
        @Pattern(regexp = "[1-9][0-9]{0,18}")
        public String expectedRowVersion;

        @Pattern(regexp = "SYSTEM|LIGHT|DARK")
        public String theme;

        public Boolean reduceMotion;

        @DecimalMin("0.80")
        @DecimalMax("2.00")
        @Digits(integer = 1, fraction = 2)
        public BigDecimal fontScale;

        @Size(max = 10)
        public List<@Pattern(regexp = "WALK|RUN|CYCLING|STRETCH|SPORT|OTHER") String>
                activityPreferences;

        @Size(max = 10)
        public List<@Pattern(regexp = "WALK|RUN|CYCLING|STRETCH|SPORT|OTHER") String>
                excludedActivityTypes;

        @Min(1)
        @Max(12)
        public Integer dailyTaskLimit;

        @Min(1)
        @Max(240)
        public Integer defaultTaskMinutes;

        @Min(1)
        @Max(240)
        public Integer preferredSessionMinutes;

        @Pattern(regexp = "([01][0-9]|2[0-3]):[0-5][0-9]")
        public String preferredWakeTime;

        @Pattern(regexp = "([01][0-9]|2[0-3]):[0-5][0-9]")
        public String preferredBedTime;

        @Pattern(regexp = "NEUTRAL|ENCOURAGING|CONCISE")
        public String planTone;

        public Boolean quietHoursEnabled;

        @Pattern(regexp = "([01][0-9]|2[0-3]):[0-5][0-9]")
        public String quietStart;

        @Pattern(regexp = "([01][0-9]|2[0-3]):[0-5][0-9]")
        public String quietEnd;

        @JsonIgnore
        public Set<String> presentFields() {
            return Set.copyOf(presentFields);
        }

        @JsonSetter("expectedRowVersion")
        public void setExpectedRowVersion(String value) {
            this.expectedRowVersion = value;
            presentFields.add("expectedRowVersion");
        }

        @JsonSetter("theme")
        public void setTheme(String value) {
            this.theme = value;
            presentFields.add("theme");
        }

        @JsonSetter("reduceMotion")
        public void setReduceMotion(Boolean value) {
            this.reduceMotion = value;
            presentFields.add("reduceMotion");
        }

        @JsonSetter("fontScale")
        public void setFontScale(BigDecimal value) {
            this.fontScale = value;
            presentFields.add("fontScale");
        }

        @JsonSetter("activityPreferences")
        public void setActivityPreferences(
                List<@Pattern(regexp = "WALK|RUN|CYCLING|STRETCH|SPORT|OTHER") String> value) {
            this.activityPreferences = value;
            presentFields.add("activityPreferences");
        }

        @JsonSetter("excludedActivityTypes")
        public void setExcludedActivityTypes(
                List<@Pattern(regexp = "WALK|RUN|CYCLING|STRETCH|SPORT|OTHER") String> value) {
            this.excludedActivityTypes = value;
            presentFields.add("excludedActivityTypes");
        }

        @JsonSetter("dailyTaskLimit")
        public void setDailyTaskLimit(Integer value) {
            this.dailyTaskLimit = value;
            presentFields.add("dailyTaskLimit");
        }

        @JsonSetter("defaultTaskMinutes")
        public void setDefaultTaskMinutes(Integer value) {
            this.defaultTaskMinutes = value;
            presentFields.add("defaultTaskMinutes");
        }

        @JsonSetter("preferredSessionMinutes")
        public void setPreferredSessionMinutes(Integer value) {
            this.preferredSessionMinutes = value;
            presentFields.add("preferredSessionMinutes");
        }

        @JsonSetter("preferredWakeTime")
        public void setPreferredWakeTime(String value) {
            this.preferredWakeTime = value;
            presentFields.add("preferredWakeTime");
        }

        @JsonSetter("preferredBedTime")
        public void setPreferredBedTime(String value) {
            this.preferredBedTime = value;
            presentFields.add("preferredBedTime");
        }

        @JsonSetter("planTone")
        public void setPlanTone(String value) {
            this.planTone = value;
            presentFields.add("planTone");
        }

        @JsonSetter("quietHoursEnabled")
        public void setQuietHoursEnabled(Boolean value) {
            this.quietHoursEnabled = value;
            presentFields.add("quietHoursEnabled");
        }

        @JsonSetter("quietStart")
        public void setQuietStart(String value) {
            this.quietStart = value;
            presentFields.add("quietStart");
        }

        @JsonSetter("quietEnd")
        public void setQuietEnd(String value) {
            this.quietEnd = value;
            presentFields.add("quietEnd");
        }
    }

    public static final class GoalPatch {
        @JsonIgnore private final Set<String> presentFields = new HashSet<>();

        @NotBlank
        @Pattern(regexp = "[1-9][0-9]{0,18}")
        public String expectedRowVersion;

        @Size(min = 1, max = 80)
        public String title;

        @DecimalMin("0")
        @DecimalMax("1000000")
        @Digits(integer = 7, fraction = 2)
        public BigDecimal targetValue;

        @Pattern(regexp = "MINUTES|DAYS_PER_WEEK|TIMES_PER_WEEK|CUSTOM")
        public String unit;

        public LocalDate endDate;

        @Min(1)
        @Max(5)
        public Integer priority;

        @Size(min = 1, max = 500)
        public String note;

        @Pattern(regexp = "ACTIVE|PAUSED|COMPLETED")
        public String status;

        @JsonIgnore
        public Set<String> presentFields() {
            return Set.copyOf(presentFields);
        }

        @JsonSetter("expectedRowVersion")
        public void setExpectedRowVersion(String value) {
            this.expectedRowVersion = value;
            presentFields.add("expectedRowVersion");
        }

        @JsonSetter("title")
        public void setTitle(String value) {
            this.title = value;
            presentFields.add("title");
        }

        @JsonSetter("targetValue")
        public void setTargetValue(BigDecimal value) {
            this.targetValue = value;
            presentFields.add("targetValue");
        }

        @JsonSetter("unit")
        public void setUnit(String value) {
            this.unit = value;
            presentFields.add("unit");
        }

        @JsonSetter("endDate")
        public void setEndDate(LocalDate value) {
            this.endDate = value;
            presentFields.add("endDate");
        }

        @JsonSetter("priority")
        public void setPriority(Integer value) {
            this.priority = value;
            presentFields.add("priority");
        }

        @JsonSetter("note")
        public void setNote(String value) {
            this.note = value;
            presentFields.add("note");
        }

        @JsonSetter("status")
        public void setStatus(String value) {
            this.status = value;
            presentFields.add("status");
        }
    }
}
