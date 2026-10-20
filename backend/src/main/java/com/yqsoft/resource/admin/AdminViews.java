package com.yqsoft.resource.admin;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

public final class AdminViews {
    private AdminViews() {}

    public record UserView(
            long id,
            long rowVersion,
            String accountMasked,
            String nickname,
            String status,
            Instant createdAt,
            Instant lastLoginAt,
            int activeSessionCount,
            List<String> roleCodes) {}

    public record RoleView(
            long id,
            long rowVersion,
            String code,
            String name,
            String description,
            boolean enabled,
            boolean systemReserved,
            List<Long> permissionIds) {}

    public record PermissionView(
            long id, String code, String name, String module, String description) {}

    public record PermissionList(List<PermissionView> items) {}

    public record AssignmentView(
            long id,
            long rowVersion,
            long userId,
            long roleId,
            String roleCode,
            Instant validFrom,
            Instant validTo,
            long grantedBy,
            Instant revokedAt) {}

    public record ConfigurationView(
            long id,
            long rowVersion,
            String key,
            String namespace,
            String environment,
            String scope,
            String valueType,
            JsonNode value,
            boolean secretMasked,
            String description,
            boolean enabled,
            Instant updatedAt) {}

    public record AuditView(
            long id,
            String actorType,
            Long actorId,
            String actionCode,
            String resourceType,
            Long resourceId,
            String result,
            String requestId,
            String reason,
            Instant createdAt) {}

    public record JobView(
            long id,
            long rowVersion,
            String kind,
            String status,
            int attemptCount,
            Integer maxAttempts,
            Instant nextAttemptAt,
            Instant lockedAt,
            Instant createdAt,
            Instant updatedAt,
            String lastErrorCode) {}
}
