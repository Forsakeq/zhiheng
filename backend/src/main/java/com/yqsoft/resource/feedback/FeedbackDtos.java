package com.yqsoft.resource.feedback;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.*;

public final class FeedbackDtos {
    private FeedbackDtos() {}

    public record Diagnostic(
            @NotBlank @Size(max = 32) String appVersion,
            @NotBlank @Pattern(regexp = "ANDROID|HARMONYOS|IOS|WEB") String platform,
            @NotBlank @Size(max = 64) String osVersion,
            @Size(max = 80) String screen,
            @Size(max = 80) String errorCode,
            @Size(max = 10) List<@Size(max = 80) String> requestIds) {}

    public record Create(
            @NotBlank @Pattern(regexp = "BUG|SUGGESTION|SYNC|PRIVACY|AI_QUALITY") String category,
            @NotBlank @Size(max = 100) String title,
            @NotBlank @Size(max = 3000) String description,
            @Size(max = 3) List<String> fileIds,
            @Size(max = 254) String contact,
            String installationId,
            Boolean includeDiagnostics,
            @Valid Diagnostic diagnostics) {}

    /** Presence flags preserve PATCH omitted/explicit-null semantics. */
    public static class ManagePatch {
        @NotBlank private String expectedRowVersion;

        @Pattern(regexp = "OPEN|IN_PROGRESS|WAITING_USER|RESOLVED|CLOSED")
        private String status;

        private String assignedTo;

        @Size(max = 2000)
        private String reply;

        @Size(max = 2000)
        private String internalNote;

        @JsonIgnore private final Set<String> present = new HashSet<>();

        @JsonSetter
        public void setExpectedRowVersion(String value) {
            expectedRowVersion = value;
        }

        @JsonSetter
        public void setStatus(String value) {
            status = value;
            present.add("status");
        }

        @JsonSetter
        public void setAssignedTo(String value) {
            assignedTo = value;
            present.add("assignedTo");
        }

        @JsonSetter
        public void setReply(String value) {
            reply = value;
            present.add("reply");
        }

        @JsonSetter
        public void setInternalNote(String value) {
            internalNote = value;
            present.add("internalNote");
        }

        public String expectedRowVersion() {
            return expectedRowVersion;
        }

        public String status() {
            return status;
        }

        public String assignedTo() {
            return assignedTo;
        }

        public String reply() {
            return reply;
        }

        public String internalNote() {
            return internalNote;
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
}
