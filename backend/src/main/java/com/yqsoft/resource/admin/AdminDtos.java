package com.yqsoft.resource.admin;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;

public final class AdminDtos {
    private AdminDtos() {}

    public record VersionBody(@NotBlank String expectedRowVersion) {}

    public record UserSecurityCommand(
            @NotBlank String expectedRowVersion,
            @NotBlank @Size(max = 500) String reason,
            @NotNull @AssertTrue Boolean userConfirmed) {}

    public record RoleCreate(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{2,63}") String code,
            @NotBlank @Size(max = 80) String name,
            @Size(max = 500) String description) {}

    public record RoleReplace(
            @NotBlank String expectedRowVersion,
            @NotBlank @Size(max = 80) String name,
            @Size(max = 500) String description,
            @NotNull Boolean enabled) {}

    public record RolePermissionsPut(
            @NotBlank String expectedRowVersion,
            @NotNull @Size(max = 100) List<@NotBlank String> permissionIds) {}

    public record RoleAssignmentCreate(
            @NotBlank String userId,
            @NotBlank String roleId,
            @NotNull Instant validFrom,
            Instant validTo,
            @NotBlank @Size(max = 500) String reason) {}

    public record ConfigurationPut(
            @NotBlank String expectedRowVersion,
            @NotBlank @Pattern(regexp = "DEV|TEST|PROD") String environment,
            @NotBlank @Pattern(regexp = "PUBLIC|SERVER") String scope,
            @NotBlank @Pattern(regexp = "BOOLEAN|NUMBER|STRING|OBJECT") String valueType,
            @NotNull JsonNode value,
            @NotBlank @Size(max = 500) String description,
            @NotNull Boolean enabled,
            Instant effectiveAt,
            @NotBlank @Size(max = 500) String reason) {}

    public record OutboxRetry(
            @NotBlank String expectedRowVersion,
            @NotBlank @Size(max = 500) String reason,
            @NotNull @AssertTrue Boolean userConfirmed) {}
}
