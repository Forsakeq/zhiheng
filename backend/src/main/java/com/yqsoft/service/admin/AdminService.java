package com.yqsoft.service.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yqsoft.dao.SqlStore;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.persistence.MutationExecutor;
import com.yqsoft.resource.admin.AdminDtos.*;
import com.yqsoft.resource.admin.AdminViews.*;
import com.yqsoft.resource.common.*;
import com.yqsoft.security.AuthContext;
import com.yqsoft.security.AuthenticatedUser;
import com.yqsoft.service.common.CryptoService;
import com.yqsoft.service.common.CursorService;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

@Service
public class AdminService {
    private final SqlStore sql;
    private final MutationExecutor mutations;
    private final CursorService cursors;
    private final CryptoService crypto;
    private final ObjectMapper mapper;
    private final Environment environment;
    private final TransactionTemplate transactions;

    public AdminService(
            SqlStore sql,
            MutationExecutor mutations,
            CursorService cursors,
            CryptoService crypto,
            ObjectMapper mapper,
            Environment environment,
            PlatformTransactionManager transactionManager) {
        this.sql = sql;
        this.mutations = mutations;
        this.cursors = cursors;
        this.crypto = crypto;
        this.mapper = mapper;
        this.environment = environment;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public PageResult<UserView> users(
            String q, String status, LocalDate from, LocalDate to, String cursor, int limit) {
        List<Object> args = new ArrayList<>();
        String where = "1=1";
        if (q != null && !q.isBlank()) {
            where +=
                    " AND (account_no=? OR id IN (SELECT user_id FROM user_profile WHERE"
                            + " display_name LIKE ? ESCAPE '!'))";
            args.add(q);
            args.add("%" + escapeLike(q) + "%");
        }
        if (status != null) {
            allow(status, Set.of("ACTIVE", "LOCKED", "DELETING", "DELETED"));
            where += " AND status=?";
            args.add(status);
        }
        if ((from == null) != (to == null)) throw bad("注册起止日期需同时提供");
        if (from != null) {
            if (to.isBefore(from)) throw bad("日期范围无效");
            where += " AND created_at>=? AND created_at<?";
            args.add(from.atStartOfDay());
            args.add(to.plusDays(1).atStartOfDay());
        }
        return pageColumns(
                "app_user",
                "id,row_version,email_cipher,status,created_at,last_login_at",
                where,
                args,
                "admin-users:" + q + ":" + status + ":" + from + ":" + to,
                cursor,
                limit,
                this::userView);
    }

    public MutationResult security(long userId, UserSecurityCommand request, boolean lock) {
        return adminMutate(
                lock ? "ADMIN_USER_LOCK" : "ADMIN_USER_UNLOCK",
                Rows.map("userId", userId, "request", request),
                List.of(userId),
                () -> {
                    Map<String, Object> user =
                            sql.one(
                                    "SELECT * FROM app_user WHERE id=? AND deleted_at IS NULL FOR"
                                            + " UPDATE",
                                    userId);
                    String status = Rows.text(user, "status");
                    if (lock && !"ACTIVE".equals(status) || !lock && !"LOCKED".equals(status)) {
                        throw BusinessException.conflict("ILLEGAL_STATE_TRANSITION", "账号状态不允许此操作");
                    }
                    if (lock) protectLastAdministrator(userId);
                    long version =
                            sql.update(
                                    "app_user",
                                    userId,
                                    Ids.parse(request.expectedRowVersion()),
                                    Rows.map(
                                            "status",
                                            lock ? "LOCKED" : "ACTIVE",
                                            "security_version",
                                            Rows.number(user, "security_version") + 1));
                    sql.execute(
                            "UPDATE auth_session SET"
                                + " revoked_at=COALESCE(revoked_at,UTC_TIMESTAMP(3)),revoke_reason='ACCOUNT_SECURITY'"
                                + " WHERE user_id=?",
                            userId);
                    if (lock)
                        sql.execute(
                                "UPDATE client_installation SET"
                                    + " device_credential_hash=NULL,credential_expires_at=NULL,"
                                    + "pairing_token_hash=NULL,pairing_expires_at=NULL,credential_revision=credential_revision+1,row_version=row_version+1,updated_at=UTC_TIMESTAMP(3)"
                                    + " WHERE user_id=? AND installation_kind='WATCH'",
                                userId);
                    audit(
                            lock ? "USER_LOCK" : "USER_UNLOCK",
                            "USER",
                            userId,
                            userId,
                            request.reason(),
                            List.of("status", "securityVersion"));
                    return MutationResult.of("USER", userId, version);
                });
    }

    public PageResult<RoleView> roles(String cursor, int limit) {
        return page(
                "sys_role",
                "deleted_at IS NULL",
                List.of(),
                "admin-roles",
                cursor,
                limit,
                this::roleView);
    }

    public MutationResult createRole(RoleCreate request) {
        return adminMutate(
                "ADMIN_ROLE_CREATE",
                request,
                List.of(),
                () -> {
                    if (sql.count("SELECT COUNT(*) FROM sys_role WHERE role_code=?", request.code())
                            > 0) {
                        throw BusinessException.conflict("ROLE_CODE_EXISTS", "角色代码已存在");
                    }
                    long id =
                            sql.insert(
                                    "sys_role",
                                    Rows.map(
                                            "role_code",
                                            request.code(),
                                            "name",
                                            request.name().trim(),
                                            "description",
                                            request.description() == null
                                                    ? ""
                                                    : request.description(),
                                            "status",
                                            "ACTIVE",
                                            "is_builtin",
                                            0));
                    audit("ROLE_CREATE", "ROLE", id, null, null, List.of("roleCode", "name"));
                    return MutationResult.of("ROLE", id, 1);
                });
    }

    public MutationResult replaceRole(long id, RoleReplace request) {
        return adminMutate(
                "ADMIN_ROLE_REPLACE",
                Rows.map("id", id, "request", request),
                List.of(),
                () -> {
                    Map<String, Object> role = role(id);
                    if ("SYSTEM_ADMIN".equals(Rows.text(role, "role_code")) && !request.enabled()) {
                        throw BusinessException.conflict("LAST_ADMIN_PROTECTED", "系统管理员守卫角色不能禁用");
                    }
                    assertPermissionCeiling(rolePermissions(id));
                    long version =
                            sql.update(
                                    "sys_role",
                                    id,
                                    Ids.parse(request.expectedRowVersion()),
                                    Rows.map(
                                            "name",
                                            request.name().trim(),
                                            "description",
                                            request.description() == null
                                                    ? ""
                                                    : request.description(),
                                            "status",
                                            request.enabled() ? "ACTIVE" : "DISABLED"));
                    audit(
                            "ROLE_REPLACE",
                            "ROLE",
                            id,
                            null,
                            null,
                            List.of("name", "description", "status"));
                    return MutationResult.of("ROLE", id, version);
                });
    }

    public MutationResult deleteRole(long id, VersionBody request) {
        return adminMutate(
                "ADMIN_ROLE_DELETE",
                Rows.map("id", id, "request", request),
                List.of(),
                () -> {
                    Map<String, Object> role = role(id);
                    if (Rows.bool(role, "is_builtin"))
                        throw BusinessException.conflict("BUILTIN_ROLE_PROTECTED", "内建角色不能删除");
                    assertPermissionCeiling(rolePermissions(id));
                    if (sql.count(
                                    "SELECT COUNT(*) FROM user_role_assignment WHERE role_id=? AND"
                                        + " status='ACTIVE' AND deleted_at IS NULL AND (valid_until"
                                        + " IS NULL OR valid_until>UTC_TIMESTAMP(3))",
                                    id)
                            > 0) {
                        throw BusinessException.conflict("ROLE_IN_USE", "请先撤销有效或待生效的角色分配");
                    }
                    long version =
                            sql.update(
                                    "sys_role",
                                    id,
                                    Ids.parse(request.expectedRowVersion()),
                                    Rows.map("deleted_at", Instant.now(), "status", "DISABLED"));
                    audit("ROLE_DELETE", "ROLE", id, null, null, List.of("deletedAt", "status"));
                    return MutationResult.of("ROLE", id, version);
                });
    }

    public PermissionList permissions(String module) {
        Map<String, String> groups =
                Map.of(
                        "CONTENT",
                        "KNOWLEDGE",
                        "USER",
                        "USER",
                        "RBAC",
                        "ROLE",
                        "CONFIG",
                        "CONFIG",
                        "AUDIT",
                        "AUDIT",
                        "JOB",
                        "JOB",
                        "FEEDBACK",
                        "FEEDBACK");
        if (module != null && !groups.containsKey(module)) throw bad("未知权限模块");
        List<Map<String, Object>> rows =
                module == null
                        ? sql.query(
                                "SELECT * FROM sys_permission WHERE deleted_at IS NULL AND"
                                        + " status='ACTIVE' ORDER BY id")
                        : sql.query(
                                "SELECT * FROM sys_permission WHERE deleted_at IS NULL AND"
                                        + " status='ACTIVE' AND resource_group=? ORDER BY id",
                                groups.get(module));
        return new PermissionList(
                rows.stream()
                        .map(
                                row ->
                                        new PermissionView(
                                                Rows.number(row, "id"),
                                                Rows.text(row, "permission_code"),
                                                Rows.text(row, "name"),
                                                moduleName(Rows.text(row, "resource_group")),
                                                Rows.text(row, "description")))
                        .toList());
    }

    public MutationResult putPermissions(long roleId, RolePermissionsPut request) {
        List<Long> ids = request.permissionIds().stream().map(Ids::parse).toList();
        if (ids.size() != new HashSet<>(ids).size()) throw bad("权限编号不能重复");
        return adminMutate(
                "ADMIN_ROLE_PERMISSIONS",
                Rows.map("roleId", roleId, "request", request),
                List.of(),
                () -> {
                    Map<String, Object> role = role(roleId);
                    Set<String> codes = new HashSet<>();
                    for (long id : ids) {
                        Map<String, Object> permission =
                                sql.one(
                                        "SELECT permission_code FROM sys_permission WHERE id=? AND"
                                                + " status='ACTIVE' AND deleted_at IS NULL",
                                        id);
                        codes.add(Rows.text(permission, "permission_code"));
                    }
                    assertPermissionCeiling(codes);
                    if ("SYSTEM_ADMIN".equals(Rows.text(role, "role_code"))
                            && !codes.contains("rbac.manage")) {
                        throw BusinessException.conflict(
                                "LAST_ADMIN_PROTECTED", "不能移除系统管理员的授权管理能力");
                    }
                    long version =
                            sql.update(
                                    "sys_role",
                                    roleId,
                                    Ids.parse(request.expectedRowVersion()),
                                    Rows.map(
                                            "permission_revision",
                                            Rows.number(role, "permission_revision") + 1));
                    sql.execute("DELETE FROM role_permission WHERE role_id=?", roleId);
                    for (long id : ids)
                        sql.insert(
                                "role_permission",
                                Rows.map(
                                        "role_id",
                                        roleId,
                                        "permission_id",
                                        id,
                                        "granted_by_user_id",
                                        AuthContext.userId(),
                                        "operation_id",
                                        RequestContext.idempotencyKey()));
                    audit(
                            "ROLE_PERMISSIONS_REPLACE",
                            "ROLE",
                            roleId,
                            null,
                            null,
                            List.of("permissionIds"));
                    return MutationResult.of("ROLE", roleId, version);
                });
    }

    public PageResult<AssignmentView> assignments(
            String userId, String roleId, boolean activeOnly, String cursor, int limit) {
        String where = "deleted_at IS NULL";
        List<Object> args = new ArrayList<>();
        if (userId != null) {
            where += " AND user_id=?";
            args.add(Ids.parse(userId));
        }
        if (roleId != null) {
            where += " AND role_id=?";
            args.add(Ids.parse(roleId));
        }
        if (activeOnly)
            where +=
                    " AND status='ACTIVE' AND valid_from<=UTC_TIMESTAMP(3) "
                            + "AND (valid_until IS NULL OR valid_until>UTC_TIMESTAMP(3))";
        return page(
                "user_role_assignment",
                where,
                args,
                "admin-assignments:" + userId + ":" + roleId + ":" + activeOnly,
                cursor,
                limit,
                this::assignmentView);
    }

    public MutationResult grant(RoleAssignmentCreate request) {
        long userId = Ids.parse(request.userId()), roleId = Ids.parse(request.roleId());
        if (request.validTo() != null && !request.validTo().isAfter(request.validFrom()))
            throw bad("角色有效期无效");
        return adminMutate(
                "ADMIN_ROLE_GRANT",
                request,
                List.of(userId),
                () -> {
                    Map<String, Object> user =
                            sql.one(
                                    "SELECT * FROM app_user WHERE id=? AND deleted_at IS NULL",
                                    userId);
                    if (!"ACTIVE".equals(Rows.text(user, "status"))) throw bad("只能向有效账号分配角色");
                    Map<String, Object> role = role(roleId);
                    if ("SYSTEM_ADMIN".equals(Rows.text(role, "role_code"))
                            && request.validTo() != null) {
                        throw bad("系统管理员角色不能设置自动到期，请在交接后显式撤销");
                    }
                    if (!"ACTIVE".equals(Rows.text(role, "status"))) throw bad("角色未启用");
                    assertPermissionCeiling(rolePermissions(roleId));
                    Map<String, Object> old =
                            sql.find(
                                            "SELECT * FROM user_role_assignment WHERE user_id=? AND"
                                                    + " role_id=? FOR UPDATE",
                                            userId,
                                            roleId)
                                    .orElse(null);
                    if (old != null && "ACTIVE".equals(Rows.text(old, "status"))) {
                        throw BusinessException.conflict("ROLE_ALREADY_ASSIGNED", "该账号已有此角色");
                    }
                    Map<String, Object> values =
                            Rows.map(
                                    "status",
                                    "ACTIVE",
                                    "valid_from",
                                    request.validFrom(),
                                    "valid_until",
                                    request.validTo(),
                                    "granted_by_user_id",
                                    AuthContext.userId(),
                                    "grant_reason",
                                    request.reason(),
                                    "revoked_at",
                                    null,
                                    "revoked_by_user_id",
                                    null);
                    long id, version;
                    if (old == null) {
                        values.put("user_id", userId);
                        values.put("role_id", roleId);
                        id = sql.insert("user_role_assignment", values);
                        version = 1;
                    } else {
                        id = Rows.number(old, "id");
                        values.put("deleted_at", null);
                        version =
                                sql.update(
                                        "user_role_assignment",
                                        id,
                                        Rows.number(old, "row_version"),
                                        values);
                    }
                    audit(
                            "ROLE_GRANT",
                            "ROLE_ASSIGNMENT",
                            id,
                            userId,
                            request.reason(),
                            List.of("roleId", "validFrom", "validUntil"));
                    return MutationResult.of("ROLE_ASSIGNMENT", id, version);
                });
    }

    public MutationResult revokeAssignment(long id, VersionBody request) {
        Map<String, Object> before =
                sql.one(
                        "SELECT user_id FROM user_role_assignment WHERE id=? AND deleted_at IS"
                                + " NULL",
                        id);
        long userId = Rows.number(before, "user_id");
        return adminMutate(
                "ADMIN_ROLE_REVOKE",
                Rows.map("id", id, "request", request),
                List.of(userId),
                () -> {
                    Map<String, Object> row =
                            sql.one(
                                    "SELECT * FROM user_role_assignment WHERE id=? AND deleted_at"
                                            + " IS NULL FOR UPDATE",
                                    id);
                    Map<String, Object> role = role(Rows.number(row, "role_id"));
                    assertPermissionCeiling(rolePermissions(Rows.number(role, "id")));
                    if ("SYSTEM_ADMIN".equals(Rows.text(role, "role_code"))
                            && "ACTIVE".equals(Rows.text(row, "status"))) {
                        protectLastAdministrator(userId);
                    }
                    long version =
                            sql.update(
                                    "user_role_assignment",
                                    id,
                                    Ids.parse(request.expectedRowVersion()),
                                    Rows.map(
                                            "status",
                                            "REVOKED",
                                            "revoked_at",
                                            Instant.now(),
                                            "revoked_by_user_id",
                                            AuthContext.userId()));
                    audit(
                            "ROLE_REVOKE",
                            "ROLE_ASSIGNMENT",
                            id,
                            userId,
                            null,
                            List.of("status", "revokedAt"));
                    return MutationResult.of("ROLE_ASSIGNMENT", id, version);
                });
    }

    public PageResult<ConfigurationView> configurations(
            String namespace, String deployment, boolean publicOnly, String cursor, int limit) {
        String env = currentEnvironment(deployment);
        List<Object> args = new ArrayList<>(List.of(env));
        String where = "deleted_at IS NULL AND environment=?";
        if (namespace != null) {
            where += " AND config_key LIKE ? ESCAPE '!'";
            args.add(escapeLike(namespace) + ".%");
        }
        if (publicOnly) where += " AND scope='PUBLIC'";
        return page(
                "app_configuration",
                where,
                args,
                "admin-config:" + env + ":" + namespace + ":" + publicOnly,
                cursor,
                limit,
                this::configurationView);
    }

    public MutationResult putConfiguration(String key, ConfigurationPut request) {
        if (!key.matches("[a-z][a-z0-9._-]{0,99}")) throw bad("配置键格式不正确");
        currentEnvironment(request.environment());
        return adminMutate(
                "ADMIN_CONFIGURATION_PUT",
                Rows.map("key", key, "request", request),
                List.of(),
                () -> {
                    Map<String, Object> row =
                            sql.find(
                                            "SELECT * FROM app_configuration WHERE environment=?"
                                                    + " AND config_key=? AND deleted_at IS NULL FOR"
                                                    + " UPDATE",
                                            request.environment(),
                                            key)
                                    .orElseThrow(
                                            () ->
                                                    BusinessException.badRequest(
                                                            "CONFIG_KEY_NOT_ALLOWED",
                                                            "新配置键需通过受控迁移定义schema"));
                    if (secretConfiguration(key, Rows.text(row, "value_type"))) {
                        throw BusinessException.forbidden(
                                "SECRET_CONFIGURATION_FORBIDDEN", "敏感凭据只能从环境或密钥服务维护");
                    }
                    if (!request.valueType().equals(Rows.text(row, "value_type")))
                        throw bad("不能变更配置的类型");
                    if (!request.scope().equals(Rows.text(row, "scope")))
                        throw bad("配置公开范围需经受控迁移变更");
                    if (sql.count(
                                    "SELECT JSON_SCHEMA_VALID(?,?)",
                                    Rows.json(row, "schema_json").toString(),
                                    request.value().toString())
                            != 1) {
                        throw BusinessException.badRequest(
                                "CONFIG_SCHEMA_INVALID", "配置值不符合该键的schema");
                    }
                    long id = Rows.number(row, "id");
                    long version =
                            sql.update(
                                    "app_configuration",
                                    id,
                                    Ids.parse(request.expectedRowVersion()),
                                    Rows.map(
                                            "value_json",
                                            request.value(),
                                            "description",
                                            request.description(),
                                            "is_enabled",
                                            request.enabled() ? 1 : 0,
                                            "effective_at",
                                            request.effectiveAt() == null
                                                    ? Instant.now()
                                                    : request.effectiveAt(),
                                            "changed_by_user_id",
                                            AuthContext.userId(),
                                            "change_reason",
                                            request.reason()));
                    audit(
                            "CONFIGURATION_PUT",
                            "CONFIGURATION",
                            id,
                            null,
                            request.reason(),
                            List.of("value", "enabled", "effectiveAt"));
                    return MutationResult.of("CONFIGURATION", id, version);
                });
    }

    public PageResult<AuditView> audits(
            Instant from,
            Instant to,
            String actorId,
            String actionCode,
            String resourceType,
            String result,
            String cursor,
            int limit) {
        if (!to.isAfter(from) || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0)
            throw bad("审计查询范围最多31天");
        String where = "created_at>=? AND created_at<?";
        List<Object> args = new ArrayList<>(List.of(from, to));
        if (actorId != null) {
            where += " AND actor_user_id=?";
            args.add(Ids.parse(actorId));
        }
        if (actionCode != null) {
            where += " AND action_code=?";
            args.add(actionCode);
        }
        if (resourceType != null) {
            where += " AND resource_type=?";
            args.add(resourceType);
        }
        if (result != null) {
            allow(result, Set.of("SUCCESS", "DENIED", "FAILED"));
            where += " AND outcome=?";
            args.add(result);
        }
        String scope =
                "admin-audit:"
                        + from
                        + ":"
                        + to
                        + ":"
                        + actorId
                        + ":"
                        + actionCode
                        + ":"
                        + resourceType
                        + ":"
                        + result;
        return pageColumns(
                "audit_log",
                "id,actor_type,actor_user_id,action_code,resource_type,resource_id,outcome,request_id,reason_code,created_at",
                where,
                args,
                scope,
                cursor,
                limit,
                row ->
                        new AuditView(
                                Rows.number(row, "id"),
                                Rows.text(row, "actor_type"),
                                nullableLong(row, "actor_user_id"),
                                Rows.text(row, "action_code"),
                                Rows.text(row, "resource_type"),
                                nullableLong(row, "resource_id"),
                                Rows.text(row, "outcome"),
                                Rows.text(row, "request_id"),
                                Rows.text(row, "reason_code"),
                                Rows.instant(row, "created_at")));
    }

    public PageResult<JobView> jobs(
            String kind, String status, Instant from, Instant to, String cursor, int limit) {
        Map<String, String> tables =
                Map.of(
                        "OUTBOX",
                        "outbox_event",
                        "AI",
                        "ai_run",
                        "IMPORT",
                        "import_batch",
                        "PRIVACY",
                        "privacy_request");
        if (!tables.containsKey(kind)) throw bad("未知作业类型");
        Map<String, Set<String>> statuses =
                Map.of(
                        "OUTBOX",
                                Set.of("PENDING", "CLAIMED", "DONE", "RETRY", "DEAD", "CANCELLED"),
                        "AI",
                                Set.of(
                                        "QUEUED",
                                        "RUNNING",
                                        "SUCCEEDED",
                                        "FAILED",
                                        "CANCEL_REQUESTED",
                                        "CANCELLED"),
                        "IMPORT",
                                Set.of(
                                        "QUEUED",
                                        "RUNNING",
                                        "AWAITING_CONFIRM",
                                        "SUCCEEDED",
                                        "PARTIAL_FAILED",
                                        "FAILED",
                                        "CANCELLED"),
                        "PRIVACY",
                                Set.of(
                                        "PREVIEW_READY",
                                        "QUEUED",
                                        "PROCESSING",
                                        "COMPLETED",
                                        "PARTIAL_FAILED",
                                        "FAILED",
                                        "CANCELLED",
                                        "EXPIRED"));
        if (status != null) allow(status, statuses.get(kind));
        if ((from == null) != (to == null)) throw bad("作业起止时间需同时提供");
        String jobScope = "admin-jobs:" + kind + ":" + status + ":" + from + ":" + to;
        Map<String, Object> bounds = cursors.decode(cursor, jobScope);
        Instant end =
                to != null
                        ? to
                        : bounds.containsKey("windowEnd")
                                ? Instant.parse(String.valueOf(bounds.get("windowEnd")))
                                : Instant.now();
        Instant begin = from == null ? end.minus(Duration.ofDays(7)) : from;
        if (!end.isAfter(begin) || Duration.between(begin, end).compareTo(Duration.ofDays(31)) > 0)
            throw bad("作业查询最多31天");
        String where = "created_at>=? AND created_at<? AND deleted_at IS NULL";
        List<Object> args = new ArrayList<>(List.of(begin, end));
        if (status != null) {
            where += " AND status=?";
            args.add(status);
        }
        String table = tables.get(kind);
        List<String> columns =
                new ArrayList<>(List.of("id", "row_version", "status", "created_at", "updated_at"));
        for (String column :
                List.of(
                        "attempt_count",
                        "available_at",
                        "started_at",
                        "last_error_code",
                        "error_code")) {
            if (sql.metadata().hasColumn(table, column)) columns.add(column);
        }
        PageResult<JobView> result =
                pageColumns(
                        table,
                        String.join(",", columns),
                        where,
                        args,
                        jobScope,
                        cursor,
                        limit,
                        row ->
                                new JobView(
                                        Rows.number(row, "id"),
                                        Rows.number(row, "row_version"),
                                        kind,
                                        Rows.text(row, "status"),
                                        Rows.integer(row, "attempt_count"),
                                        "OUTBOX".equals(kind) ? 5 : null,
                                        Rows.instant(row, "available_at"),
                                        "CLAIMED".equals(Rows.text(row, "status"))
                                                ? Rows.instant(row, "updated_at")
                                                : Rows.instant(row, "started_at"),
                                        Rows.instant(row, "created_at"),
                                        Rows.instant(row, "updated_at"),
                                        Rows.text(row, "last_error_code") == null
                                                ? Rows.text(row, "error_code")
                                                : Rows.text(row, "last_error_code")));
        if (result.nextCursor() != null) {
            Map<String, Object> next =
                    new LinkedHashMap<>(cursors.decode(result.nextCursor(), jobScope));
            next.put("windowEnd", end.toString());
            return PageResult.of(result.items(), cursors.encode(jobScope, next), result.hasMore());
        }
        return result;
    }

    public MutationResult retryOutbox(long id, OutboxRetry request) {
        Map<String, Object> preliminary =
                sql.one("SELECT user_id FROM outbox_event WHERE id=? AND deleted_at IS NULL", id);
        Long owner = nullableLong(preliminary, "user_id");
        return adminMutate(
                "ADMIN_OUTBOX_RETRY",
                Rows.map("id", id, "request", request),
                owner == null ? List.of() : List.of(owner),
                () -> {
                    Map<String, Object> row =
                            sql.one(
                                    "SELECT * FROM outbox_event WHERE id=? AND deleted_at IS NULL"
                                            + " FOR UPDATE",
                                    id);
                    if (!Set.of("RETRY", "DEAD").contains(Rows.text(row, "status"))) {
                        throw BusinessException.conflict("ILLEGAL_STATE_TRANSITION", "此作业当前不可人工重试");
                    }
                    if (!Set.of(
                                    "STAT_INVALIDATED",
                                    "STATISTICS_REBUILD",
                                    "REMINDER_REBUILD_REQUESTED")
                            .contains(Rows.text(row, "event_type"))) {
                        throw BusinessException.badRequest(
                                "JOB_RETRY_NOT_ALLOWED", "此事件必须从所属业务流程恢复，不能通用重放");
                    }
                    if (owner != null) assertOwnerGuard(owner, row);
                    long version =
                            sql.update(
                                    "outbox_event",
                                    id,
                                    Ids.parse(request.expectedRowVersion()),
                                    Rows.map(
                                            "status",
                                            "PENDING",
                                            "available_at",
                                            Instant.now(),
                                            "lease_owner",
                                            null,
                                            "lease_until",
                                            null,
                                            "last_error_code",
                                            null,
                                            "attempt_count",
                                            0));
                    audit(
                            "OUTBOX_RETRY",
                            "OUTBOX",
                            id,
                            owner,
                            request.reason(),
                            List.of("status", "availableAt"));
                    return MutationResult.of("OUTBOX", id, version);
                });
    }

    private MutationResult adminMutate(
            String code, Object payload, List<Long> targets, Supplier<MutationResult> action) {
        return transactions.execute(
                status -> {
                    sql.one(
                            "SELECT id FROM sys_role WHERE role_code='SYSTEM_ADMIN' AND deleted_at"
                                    + " IS NULL FOR UPDATE");
                    TreeSet<Long> users = new TreeSet<>(targets);
                    users.add(AuthContext.userId());
                    for (long user : users)
                        sql.one("SELECT * FROM user_sync_clock WHERE user_id=? FOR UPDATE", user);
                    for (long user : users)
                        sql.one("SELECT * FROM app_user WHERE id=? FOR UPDATE", user);
                    refreshActorAuthority(code);
                    return mutations.mutate(code, payload, false, action);
                });
    }

    private void refreshActorAuthority(String operation) {
        var actor = AuthContext.current();
        if (sql.count(
                        "SELECT COUNT(*) FROM auth_session s JOIN app_user u ON u.id=s.user_id"
                                + " WHERE s.id=? AND s.user_id=? AND s.revoked_at IS NULL AND"
                                + " s.expires_at>UTC_TIMESTAMP(3) AND"
                                + " s.security_version=u.security_version AND"
                                + " s.client_audience='ADMIN'",
                        actor.sessionId(),
                        actor.userId())
                != 1) {
            throw new BusinessException(401, "SESSION_REVOKED", "后台会话已撤销");
        }
        Set<String> current = new HashSet<>();
        for (Map<String, Object> row :
                sql.query(
                        "SELECT DISTINCT p.permission_code FROM user_role_assignment a JOIN"
                            + " sys_role r ON r.id=a.role_id AND r.status='ACTIVE' AND r.deleted_at"
                            + " IS NULL JOIN role_permission rp ON rp.role_id=r.id JOIN"
                            + " sys_permission p ON p.id=rp.permission_id AND p.status='ACTIVE' AND"
                            + " p.deleted_at IS NULL WHERE a.user_id=? AND a.status='ACTIVE' AND"
                            + " a.deleted_at IS NULL AND a.valid_from<=UTC_TIMESTAMP(3) AND"
                            + " (a.valid_until IS NULL OR a.valid_until>UTC_TIMESTAMP(3))",
                        actor.userId())) {
            current.add(Rows.text(row, "permission_code"));
        }
        String needed =
                operation.startsWith("ADMIN_USER_")
                        ? "user.security"
                        : operation.startsWith("ADMIN_CONFIGURATION")
                                ? "config.manage"
                                : operation.startsWith("ADMIN_OUTBOX")
                                        ? "job.retry"
                                        : "rbac.manage";
        if (!current.contains(needed)) {
            throw BusinessException.forbidden("PERMISSION_DENIED", "后台权限已变化，请重新登录");
        }
        AuthContext.set(
                new AuthenticatedUser(
                        actor.userId(),
                        actor.sessionId(),
                        actor.installationId(),
                        actor.audience(),
                        current));
    }

    private void assertOwnerGuard(long owner, Map<String, Object> row) {
        Map<String, Object> clock =
                sql.one("SELECT dataset_epoch FROM user_sync_clock WHERE user_id=?", owner);
        Map<String, Object> user =
                sql.one("SELECT status,data_write_fence FROM app_user WHERE id=?", owner);
        if (!"ACTIVE".equals(Rows.text(user, "status"))
                || !"OPEN".equals(Rows.text(user, "data_write_fence"))
                || row.get("captured_dataset_epoch") != null
                        && Rows.number(row, "captured_dataset_epoch")
                                != Rows.number(clock, "dataset_epoch")) {
            throw BusinessException.conflict("DATA_EPOCH_CHANGED", "目标数据代际或写入状态已变化");
        }
        String purpose = Rows.text(row, "consent_purpose");
        if (purpose != null) {
            Map<String, Object> consent =
                    sql.find(
                                    "SELECT * FROM user_consent WHERE user_id=? AND purpose_code=? "
                                            + "AND deleted_at IS NULL",
                                    owner,
                                    purpose)
                            .orElseThrow(
                                    () ->
                                            BusinessException.forbidden(
                                                    "CONSENT_REQUIRED", "用途授权已失效"));
            if (!"GRANTED".equals(Rows.text(consent, "status"))
                    || Rows.number(consent, "consent_version")
                            != Rows.number(row, "captured_consent_version")
                    || Rows.instant(consent, "expires_at") != null
                            && !Rows.instant(consent, "expires_at").isAfter(Instant.now())) {
                throw BusinessException.forbidden("CONSENT_REQUIRED", "用途授权已失效，不能重放旧作业");
            }
        }
    }

    private void protectLastAdministrator(long userId) {
        String active =
                " FROM user_role_assignment a JOIN sys_role r ON r.id=a.role_id JOIN app_user u ON"
                    + " u.id=a.user_id WHERE r.role_code='SYSTEM_ADMIN' AND r.status='ACTIVE' AND"
                    + " r.deleted_at IS NULL AND u.status='ACTIVE' AND u.deleted_at IS NULL AND"
                    + " a.status='ACTIVE' AND a.deleted_at IS NULL AND"
                    + " a.valid_from<=UTC_TIMESTAMP(3) AND (a.valid_until IS NULL OR"
                    + " a.valid_until>UTC_TIMESTAMP(3))";
        long target =
                sql.count("SELECT COUNT(DISTINCT a.user_id)" + active + " AND a.user_id=?", userId);
        if (target > 0 && sql.count("SELECT COUNT(DISTINCT a.user_id)" + active) <= 1) {
            throw BusinessException.conflict("LAST_ADMIN_PROTECTED", "不能移除最后一名有效系统管理员");
        }
    }

    private void assertPermissionCeiling(Set<String> codes) {
        if (!AuthContext.current().permissions().containsAll(codes)) {
            throw BusinessException.forbidden("GRANT_EXCEEDS_AUTHORITY", "不能授予或修改超出本人权限上界的角色");
        }
    }

    private Set<String> rolePermissions(long roleId) {
        Set<String> codes = new HashSet<>();
        for (Map<String, Object> row :
                sql.query(
                        "SELECT p.permission_code FROM role_permission rp JOIN sys_permission p ON"
                                + " p.id=rp.permission_id AND p.status='ACTIVE' AND p.deleted_at IS"
                                + " NULL WHERE rp.role_id=?",
                        roleId)) {
            codes.add(Rows.text(row, "permission_code"));
        }
        return codes;
    }

    private Map<String, Object> role(long id) {
        return sql.one("SELECT * FROM sys_role WHERE id=? AND deleted_at IS NULL FOR UPDATE", id);
    }

    private void audit(
            String code,
            String resource,
            long id,
            Long subject,
            String reason,
            List<String> fields) {
        sql.insert(
                "audit_log",
                Rows.map(
                        "actor_user_id",
                        AuthContext.userId(),
                        "subject_user_id",
                        subject,
                        "actor_type",
                        "ADMIN",
                        "action_code",
                        code,
                        "resource_type",
                        resource,
                        "resource_id",
                        id,
                        "request_id",
                        RequestContext.requestId(),
                        "operation_id",
                        RequestContext.idempotencyKey(),
                        "outcome",
                        "SUCCESS",
                        "reason_code",
                        "ADMIN_EXPLICIT_ACTION",
                        "metadata_json",
                        Rows.map(
                                "fields",
                                fields,
                                "reasonDigest",
                                reason == null
                                        ? null
                                        : Base64.getEncoder()
                                                .encodeToString(
                                                        crypto.hmac("admin-reason", reason))),
                        "retention_until",
                        Instant.now().plus(Duration.ofDays(180))));
    }

    private UserView userView(Map<String, Object> row) {
        long id = Rows.number(row, "id");
        String
                email =
                        "DELETED".equals(Rows.text(row, "status"))
                                ? null
                                : crypto.decrypt(row.get("email_cipher")),
                masked = "***";
        if (email != null && email.contains("@"))
            masked = email.substring(0, 1) + "***" + email.substring(email.indexOf('@'));
        String nickname =
                sql.find(
                                "SELECT display_name FROM user_profile WHERE user_id=? AND"
                                        + " deleted_at IS NULL",
                                id)
                        .map(profile -> Rows.text(profile, "display_name"))
                        .orElse("");
        List<String> roles =
                sql
                        .query(
                                "SELECT r.role_code FROM user_role_assignment a JOIN sys_role r ON"
                                    + " r.id=a.role_id WHERE a.user_id=? AND a.status='ACTIVE' AND"
                                    + " a.deleted_at IS NULL AND r.status='ACTIVE' AND r.deleted_at"
                                    + " IS NULL AND a.valid_from<=UTC_TIMESTAMP(3) AND"
                                    + " (a.valid_until IS NULL OR a.valid_until>UTC_TIMESTAMP(3))"
                                    + " ORDER BY r.id",
                                id)
                        .stream()
                        .map(role -> Rows.text(role, "role_code"))
                        .toList();
        int sessions =
                Math.toIntExact(
                        sql.count(
                                "SELECT COUNT(*) FROM auth_session WHERE user_id=? AND revoked_at"
                                        + " IS NULL AND consumed_at IS NULL AND"
                                        + " expires_at>UTC_TIMESTAMP(3)",
                                id));
        return new UserView(
                id,
                Rows.number(row, "row_version"),
                masked,
                nickname,
                Rows.text(row, "status"),
                Rows.instant(row, "created_at"),
                Rows.instant(row, "last_login_at"),
                sessions,
                roles);
    }

    private RoleView roleView(Map<String, Object> row) {
        List<Long> permissions =
                sql
                        .query(
                                "SELECT permission_id FROM role_permission WHERE role_id=? ORDER BY"
                                        + " permission_id",
                                Rows.number(row, "id"))
                        .stream()
                        .map(item -> Rows.number(item, "permission_id"))
                        .toList();
        return new RoleView(
                Rows.number(row, "id"),
                Rows.number(row, "row_version"),
                Rows.text(row, "role_code"),
                Rows.text(row, "name"),
                Rows.text(row, "description"),
                "ACTIVE".equals(Rows.text(row, "status")),
                Rows.bool(row, "is_builtin"),
                permissions);
    }

    private AssignmentView assignmentView(Map<String, Object> row) {
        String code =
                Rows.text(
                        sql.one(
                                "SELECT role_code FROM sys_role WHERE id=?",
                                Rows.number(row, "role_id")),
                        "role_code");
        return new AssignmentView(
                Rows.number(row, "id"),
                Rows.number(row, "row_version"),
                Rows.number(row, "user_id"),
                Rows.number(row, "role_id"),
                code,
                Rows.instant(row, "valid_from"),
                Rows.instant(row, "valid_until"),
                Rows.number(row, "granted_by_user_id"),
                Rows.instant(row, "revoked_at"));
    }

    private ConfigurationView configurationView(Map<String, Object> row) {
        String key = Rows.text(row, "config_key");
        String namespace = key.contains(".") ? key.substring(0, key.indexOf('.')) : key;
        return new ConfigurationView(
                Rows.number(row, "id"),
                Rows.number(row, "row_version"),
                key,
                namespace,
                Rows.text(row, "environment"),
                Rows.text(row, "scope"),
                Rows.text(row, "value_type"),
                secretConfiguration(key, Rows.text(row, "value_type"))
                        ? null
                        : Rows.json(row, "value_json"),
                secretConfiguration(key, Rows.text(row, "value_type")),
                Rows.text(row, "description"),
                Rows.bool(row, "is_enabled"),
                Rows.instant(row, "updated_at"));
    }

    private <T> PageResult<T> page(
            String table,
            String where,
            List<Object> filters,
            String scope,
            String cursor,
            int limit,
            Function<Map<String, Object>, T> view) {
        return pageColumns(table, "*", where, filters, scope, cursor, limit, view);
    }

    private <T> PageResult<T> pageColumns(
            String table,
            String columns,
            String where,
            List<Object> filters,
            String scope,
            String cursor,
            int limit,
            Function<Map<String, Object>, T> view) {
        int size = Math.max(1, Math.min(limit, 100));
        Map<String, Object> state = cursors.decode(cursor, scope);
        long after = state.isEmpty() ? 0 : Long.parseLong(String.valueOf(state.get("after")));
        List<Object> args = new ArrayList<>(filters);
        args.add(after);
        args.add(size + 1);
        List<Map<String, Object>> rows =
                sql.query(
                        "SELECT "
                                + columns
                                + " FROM "
                                + table
                                + " WHERE "
                                + where
                                + " AND id>? ORDER BY id LIMIT ?",
                        args.toArray());
        boolean more = rows.size() > size;
        if (more) rows = rows.subList(0, size);
        String next =
                more
                        ? cursors.encode(
                                scope, Rows.map("after", Rows.number(rows.getLast(), "id")))
                        : null;
        return PageResult.of(rows.stream().map(view).toList(), next, more);
    }

    private boolean secretConfiguration(String key, String type) {
        if ("BOOLEAN".equals(type) || "NUMBER".equals(type)) return false;
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("secret")
                || normalized.contains("password")
                || normalized.contains("credential")
                || normalized.contains("api_key")
                || normalized.contains("api-key")
                || normalized.contains("private_key")
                || normalized.contains("hmac_key")
                || normalized.contains("encryption_key")
                || normalized.endsWith("access_token")
                || normalized.endsWith("refresh_token");
    }

    private String currentEnvironment(String requested) {
        String current = environment.getProperty("zhiheng.environment", "DEV");
        if (requested != null && !current.equals(requested)) throw bad("不能从当前部署修改其他环境配置");
        return current;
    }

    private String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private String moduleName(String group) {
        return switch (group) {
            case "KNOWLEDGE" -> "CONTENT";
            case "ROLE" -> "RBAC";
            default -> group;
        };
    }

    private Long nullableLong(Map<String, Object> row, String column) {
        return row.get(column) == null ? null : Rows.number(row, column);
    }

    private void allow(String value, Set<String> values) {
        if (!values.contains(value)) throw bad("不支持的状态或类型");
    }

    private BusinessException bad(String message) {
        return BusinessException.badRequest("VALIDATION_FAILED", message);
    }
}
