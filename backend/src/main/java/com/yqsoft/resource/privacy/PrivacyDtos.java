package com.yqsoft.resource.privacy;

import com.yqsoft.resource.auth.AuthDtos.ReauthProof;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public final class PrivacyDtos {

    private PrivacyDtos() {}

    public record Scope(
            @Size(max = 1000) List<String> recordIds,
            List<String> recordTypes,
            LocalDate from,
            LocalDate to,
            Boolean includeFiles,
            Boolean includeAiContent,
            Boolean includePlans,
            String sourceConnectionId) {}

    public record Preview(
            @NotBlank @Pattern(regexp = "EXPORT|DELETE_RECORDS|ERASE_HEALTH_DATA|DELETE_ACCOUNT")
                    String type,
            @NotNull @Valid Scope scope,
            @Pattern(regexp = "JSON|CSV_ZIP") String format,
            @Size(max = 300) String reason) {}

    public record Confirm(
            @NotBlank String expectedRowVersion,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String manifestDigest,
            @NotBlank String expectedDataSeq,
            @AssertTrue boolean userConfirmed,
            @NotNull @Valid ReauthProof reauth) {}

    public record Receipt(@NotBlank @Size(min = 32, max = 512) String receiptToken) {}

    public record Download(
            @NotNull @Valid ReauthProof reauth,
            @Pattern(regexp = "ATTACHMENT") String disposition) {}

    public record VersionBody(@NotBlank String expectedRowVersion) {}
}
