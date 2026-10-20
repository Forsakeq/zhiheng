package com.yqsoft.resource.reminder;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.*;
import java.util.*;

public final class ReminderDtos {
    private ReminderDtos() {}

    public record Create(
            String taskId,
            @NotBlank @Pattern(regexp = "TASK|DAILY_RECORD") String scope,
            @NotNull Boolean enabled,
            @NotBlank @Pattern(regexp = "LOCAL_SYSTEM|IN_APP") String channel,
            @Min(-1440) @Max(0) Integer offsetMinutes,
            LocalTime localTime,
            @NotBlank String timeZone,
            @Pattern(regexp = "SUPPRESS|NEXT_ALLOWED") String quietPolicy,
            String installationId) {}

    public static class Patch {
        @NotBlank private String expectedRowVersion;
        private Boolean enabled;

        @Pattern(regexp = "LOCAL_SYSTEM|IN_APP")
        private String channel;

        @Min(-1440)
        @Max(0)
        private Integer offsetMinutes;

        private LocalTime localTime;
        private String timeZone;

        @Pattern(regexp = "SUPPRESS|NEXT_ALLOWED")
        private String quietPolicy;

        private String installationId;
        @JsonIgnore private final Set<String> present = new HashSet<>();

        @JsonSetter
        public void setExpectedRowVersion(String value) {
            expectedRowVersion = value;
        }

        @JsonSetter
        public void setEnabled(Boolean value) {
            enabled = value;
            present.add("enabled");
        }

        @JsonSetter
        public void setChannel(String value) {
            channel = value;
            present.add("channel");
        }

        @JsonSetter
        public void setOffsetMinutes(Integer value) {
            offsetMinutes = value;
            present.add("offsetMinutes");
        }

        @JsonSetter
        public void setLocalTime(LocalTime value) {
            localTime = value;
            present.add("localTime");
        }

        @JsonSetter
        public void setTimeZone(String value) {
            timeZone = value;
            present.add("timeZone");
        }

        @JsonSetter
        public void setQuietPolicy(String value) {
            quietPolicy = value;
            present.add("quietPolicy");
        }

        @JsonSetter
        public void setInstallationId(String value) {
            installationId = value;
            present.add("installationId");
        }

        public String expectedRowVersion() {
            return expectedRowVersion;
        }

        public Boolean enabled() {
            return enabled;
        }

        public String channel() {
            return channel;
        }

        public Integer offsetMinutes() {
            return offsetMinutes;
        }

        public LocalTime localTime() {
            return localTime;
        }

        public String timeZone() {
            return timeZone;
        }

        public String quietPolicy() {
            return quietPolicy;
        }

        public String installationId() {
            return installationId;
        }

        @JsonIgnore
        public boolean has(String key) {
            return present.contains(key);
        }

        @JsonIgnore
        public boolean empty() {
            return present.isEmpty();
        }
    }

    public record VersionBody(@NotBlank String expectedRowVersion) {}

    public record Receipt(
            @NotBlank String expectedRowVersion,
            @NotBlank String installationId,
            @NotBlank
                    @Pattern(
                            regexp =
                                    "REGISTERED|REGISTRATION_FAILED|DELIVERED|CLICKED|DISMISSED|CANCELLED|UNKNOWN")
                    String event,
            @Size(max = 200) String osRequestId,
            @NotNull Instant observedAt,
            @Size(max = 80) String errorCode) {}

    public record NotificationVersion(@NotBlank String id, @NotBlank String expectedRowVersion) {}

    public record ReadState(
            @NotEmpty @Size(max = 100) List<@Valid NotificationVersion> items,
            @NotNull Boolean read) {}
}
