package com.yqsoft.resource.profile;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

public final class ProfileViews {
    private ProfileViews() {}

    public record ProfileView(
            long id,
            long rowVersion,
            String nickname,
            Long avatarFileId,
            String bio,
            String timeZone,
            int weekStartsOn,
            String onboardingStep,
            Instant updatedAt) {}

    public record PreferenceView(
            long id,
            long rowVersion,
            String theme,
            boolean reduceMotion,
            BigDecimal fontScale,
            List<String> activityPreferences,
            List<String> excludedActivityTypes,
            int dailyTaskLimit,
            int defaultTaskMinutes,
            Integer preferredSessionMinutes,
            String preferredWakeTime,
            String preferredBedTime,
            String planTone,
            boolean quietHoursEnabled,
            String quietStart,
            String quietEnd) {}

    public record ConsentView(
            long id,
            long rowVersion,
            String purpose,
            String status,
            String policyVersion,
            Instant decidedAt) {}

    public record PolicyVersion(String purpose, String version, String title, String documentUrl) {}

    public record ConsentListView(
            List<ConsentView> items, List<PolicyVersion> currentPolicyVersions) {}

    public record MeView(
            long userId,
            String accountMasked,
            String status,
            ProfileView profile,
            PreferenceView preferences,
            List<ConsentView> consents,
            long datasetEpoch,
            long committedSeq,
            boolean dataWriteFence,
            Instant createdAt) {}

    public record GoalDefinitionView(
            long id,
            String code,
            String title,
            String category,
            String description,
            List<String> allowedUnits,
            BigDecimal minValue,
            BigDecimal maxValue,
            BigDecimal defaultValue,
            boolean enabled) {}

    public record GoalDefinitionList(List<GoalDefinitionView> items) {}

    public record UserGoalView(
            long id,
            long rowVersion,
            long definitionId,
            boolean isCustom,
            String title,
            BigDecimal targetValue,
            String unit,
            LocalDate startDate,
            LocalDate endDate,
            String timeZone,
            int priority,
            String note,
            String status,
            Instant completedAt) {}

    public record AvailabilityRuleView(
            long id,
            long rowVersion,
            int weekday,
            String kind,
            String startTime,
            String endTime,
            int endDayOffset,
            String timeZone,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String label) {}

    public record ExceptionView(
            long id,
            long rowVersion,
            LocalDate date,
            String timeZone,
            String mode,
            List<ProfileDtos.ExceptionSlot> slots,
            String reason) {}

    public record ExceptionList(List<ExceptionView> items, LocalDate from, LocalDate to) {}

    public record InstallationView(
            long id,
            long rowVersion,
            String installationKey,
            String installationKind,
            Long parentInstallationId,
            String platform,
            String deviceName,
            String appVersion,
            String osVersion,
            InstallationDtos.Capabilities capabilities,
            String notificationPermission,
            Instant lastSeenAt,
            long lastAckSeq,
            Instant revokedAt) {}
}
