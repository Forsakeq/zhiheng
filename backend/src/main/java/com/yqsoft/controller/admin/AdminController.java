package com.yqsoft.controller.admin;

import com.yqsoft.resource.admin.AdminDtos.*;
import com.yqsoft.resource.admin.AdminViews.*;
import com.yqsoft.resource.common.*;
import com.yqsoft.security.RequirePermission;
import com.yqsoft.service.admin.AdminService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.*;

@RestController
@RequestMapping("/api/admin/v1")
@Validated
public class AdminController {
    private final AdminService service;

    public AdminController(AdminService service) {
        this.service = service;
    }

    @GetMapping("/users")
    @RequirePermission("user.read")
    public ApiResponse<PageResult<UserView>> users(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) LocalDate createdFrom,
            @RequestParam(required = false) LocalDate createdTo,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.ok(service.users(q, status, createdFrom, createdTo, cursor, limit));
    }

    @PostMapping("/users/{userId}/lock")
    @RequirePermission("user.security")
    public ApiResponse<MutationResult> lock(
            @PathVariable String userId, @Valid @RequestBody UserSecurityCommand request) {
        return ApiResponse.ok(service.security(Ids.parse(userId), request, true));
    }

    @PostMapping("/users/{userId}/unlock")
    @RequirePermission("user.security")
    public ApiResponse<MutationResult> unlock(
            @PathVariable String userId, @Valid @RequestBody UserSecurityCommand request) {
        return ApiResponse.ok(service.security(Ids.parse(userId), request, false));
    }

    @GetMapping("/roles")
    @RequirePermission("rbac.read")
    public ApiResponse<PageResult<RoleView>> roles(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.ok(service.roles(cursor, limit));
    }

    @PostMapping("/roles")
    @RequirePermission("rbac.manage")
    public ResponseEntity<ApiResponse<MutationResult>> createRole(
            @Valid @RequestBody RoleCreate request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.createRole(request)));
    }

    @PutMapping("/roles/{roleId}")
    @RequirePermission("rbac.manage")
    public ApiResponse<MutationResult> replaceRole(
            @PathVariable String roleId, @Valid @RequestBody RoleReplace request) {
        return ApiResponse.ok(service.replaceRole(Ids.parse(roleId), request));
    }

    @DeleteMapping("/roles/{roleId}")
    @RequirePermission("rbac.manage")
    public ApiResponse<MutationResult> deleteRole(
            @PathVariable String roleId, @Valid @RequestBody VersionBody request) {
        return ApiResponse.ok(service.deleteRole(Ids.parse(roleId), request));
    }

    @GetMapping("/permissions")
    @RequirePermission("rbac.read")
    public ApiResponse<PermissionList> permissions(@RequestParam(required = false) String module) {
        return ApiResponse.ok(service.permissions(module));
    }

    @PutMapping("/roles/{roleId}/permissions")
    @RequirePermission("rbac.manage")
    public ApiResponse<MutationResult> putPermissions(
            @PathVariable String roleId, @Valid @RequestBody RolePermissionsPut request) {
        return ApiResponse.ok(service.putPermissions(Ids.parse(roleId), request));
    }

    @GetMapping("/role-assignments")
    @RequirePermission("rbac.read")
    public ApiResponse<PageResult<AssignmentView>> assignments(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String roleId,
            @RequestParam(defaultValue = "true") boolean activeOnly,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.ok(service.assignments(userId, roleId, activeOnly, cursor, limit));
    }

    @PostMapping("/role-assignments")
    @RequirePermission("rbac.manage")
    public ResponseEntity<ApiResponse<MutationResult>> grant(
            @Valid @RequestBody RoleAssignmentCreate request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.grant(request)));
    }

    @DeleteMapping("/role-assignments/{assignmentId}")
    @RequirePermission("rbac.manage")
    public ApiResponse<MutationResult> revoke(
            @PathVariable String assignmentId, @Valid @RequestBody VersionBody request) {
        return ApiResponse.ok(service.revokeAssignment(Ids.parse(assignmentId), request));
    }

    @GetMapping("/configurations")
    @RequirePermission("config.read")
    public ApiResponse<PageResult<ConfigurationView>> configurations(
            @RequestParam(required = false) @Size(max = 40) String namespace,
            @RequestParam(required = false) String environment,
            @RequestParam(defaultValue = "false") boolean publicOnly,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.ok(
                service.configurations(namespace, environment, publicOnly, cursor, limit));
    }

    @PutMapping("/configurations/{key}")
    @RequirePermission("config.manage")
    public ApiResponse<MutationResult> configure(
            @PathVariable String key, @Valid @RequestBody ConfigurationPut request) {
        return ApiResponse.ok(service.putConfiguration(key, request));
    }

    @GetMapping("/audit-logs")
    @RequirePermission("audit.read")
    public ApiResponse<PageResult<AuditView>> audits(
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) @Size(max = 80) String actionCode,
            @RequestParam(required = false) @Size(max = 64) String resourceType,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.ok(
                service.audits(from, to, actorId, actionCode, resourceType, result, cursor, limit));
    }

    @GetMapping("/jobs")
    @RequirePermission("job.read")
    public ApiResponse<PageResult<JobView>> jobs(
            @RequestParam String kind,
            @RequestParam(required = false) @Size(max = 40) String status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.ok(service.jobs(kind, status, from, to, cursor, limit));
    }

    @PostMapping("/outbox/{eventId}/retry")
    @RequirePermission("job.retry")
    public ApiResponse<MutationResult> retry(
            @PathVariable String eventId, @Valid @RequestBody OutboxRetry request) {
        return ApiResponse.ok(service.retryOutbox(Ids.parse(eventId), request));
    }
}
