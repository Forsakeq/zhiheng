package com.yqsoft.listener;

import com.yqsoft.dao.SqlStore;
import com.yqsoft.resource.common.Ids;
import com.yqsoft.resource.common.Rows;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Explicit, one-time deployment bootstrap. No public endpoint or default credentials. */
@Component
public class AdminBootstrapInitializer implements ApplicationRunner {

    private final SqlStore sql;
    private final Clock clock;
    private final Environment environment;
    private final TransactionTemplate transactions;

    public AdminBootstrapInitializer(
            SqlStore sql,
            Clock clock,
            Environment environment,
            PlatformTransactionManager manager) {
        this.sql = sql;
        this.clock = clock;
        this.environment = environment;
        this.transactions = new TransactionTemplate(manager);
    }

    @Override
    public void run(ApplicationArguments arguments) {
        String value =
                environment.getProperty(
                        "zhiheng.bootstrap.admin-user-id",
                        environment.getProperty("ZH_BOOTSTRAP_ADMIN_USER_ID", ""));
        if (value.isBlank()) {
            return;
        }
        long user = Ids.parse(value);
        Set<String> roleCodes = new LinkedHashSet<>();
        roleCodes.add("SYSTEM_ADMIN");
        String additional =
                environment.getProperty(
                        "zhiheng.bootstrap.additional-role-codes",
                        environment.getProperty("ZH_BOOTSTRAP_ADDITIONAL_ROLE_CODES", ""));
        Arrays.stream(additional.split(","))
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .forEach(roleCodes::add);
        if (!Set.of(
                        "SYSTEM_ADMIN",
                        "CONTENT_EDITOR",
                        "CONTENT_REVIEWER",
                        "CONTENT_PUBLISHER",
                        "SUPPORT",
                        "OPERATIONS")
                .containsAll(roleCodes)) {
            throw new IllegalStateException("Bootstrap only accepts the known built-in role codes");
        }
        transactions.executeWithoutResult(
                status -> {
                    var role =
                            sql.one(
                                    "SELECT * FROM sys_role WHERE role_code='SYSTEM_ADMIN' AND"
                                            + " deleted_at IS NULL FOR UPDATE");
                    if (!"ACTIVE".equals(Rows.text(role, "status"))) {
                        throw new IllegalStateException(
                                "The system administrator role must be active");
                    }
                    sql.one("SELECT * FROM user_sync_clock WHERE user_id=? FOR UPDATE", user);
                    var account = sql.one("SELECT * FROM app_user WHERE id=? FOR UPDATE", user);
                    if (!"ACTIVE".equals(Rows.text(account, "status"))
                            || account.get("deleted_at") != null
                            || !"OPEN".equals(Rows.text(account, "data_write_fence"))) {
                        throw new IllegalStateException("Bootstrap account must be active");
                    }
                    long roleId = Rows.number(role, "id");
                    long existing =
                            sql.count(
                                    "SELECT COUNT(*) FROM user_role_assignment a JOIN app_user u ON"
                                        + " u.id=a.user_id WHERE a.role_id=? AND a.status='ACTIVE'"
                                        + " AND a.deleted_at IS NULL AND a.valid_from<=? AND"
                                        + " (a.valid_until IS NULL OR a.valid_until>?) AND"
                                        + " u.status='ACTIVE' AND u.deleted_at IS NULL",
                                    roleId,
                                    clock.instant(),
                                    clock.instant());
                    boolean already =
                            sql.count(
                                            "SELECT COUNT(*) FROM user_role_assignment WHERE"
                                                + " user_id=? AND role_id=? AND status='ACTIVE' AND"
                                                + " deleted_at IS NULL AND valid_from<=? AND"
                                                + " valid_until IS NULL",
                                            user,
                                            roleId,
                                            clock.instant())
                                    > 0;
                    if (existing > 0 && !already) {
                        throw new IllegalStateException(
                                "An active system administrator already exists; use audited role"
                                        + " assignment");
                    }
                    if (already) {
                        return;
                    }
                    if (sql.count(
                                    "SELECT COUNT(*) FROM user_role_assignment WHERE user_id=? AND"
                                            + " role_id=?",
                                    user,
                                    roleId)
                            > 0) {
                        throw new IllegalStateException(
                                "A historical administrator assignment exists; use controlled"
                                        + " recovery");
                    }
                    long assignment =
                            sql.insert(
                                    "user_role_assignment",
                                    Rows.map(
                                            "user_id",
                                            user,
                                            "role_id",
                                            roleId,
                                            "status",
                                            "ACTIVE",
                                            "valid_from",
                                            clock.instant(),
                                            "granted_by_user_id",
                                            user,
                                            "grant_reason",
                                            "受控部署初始化首位管理员"));
                    for (String code : roleCodes) {
                        if (code.equals("SYSTEM_ADMIN")) {
                            continue;
                        }
                        var additionalRole =
                                sql.one(
                                        "SELECT id FROM sys_role WHERE role_code=? "
                                                + "AND status='ACTIVE' AND deleted_at IS NULL",
                                        code);
                        sql.insert(
                                "user_role_assignment",
                                Rows.map(
                                        "user_id",
                                        user,
                                        "role_id",
                                        Rows.number(additionalRole, "id"),
                                        "status",
                                        "ACTIVE",
                                        "valid_from",
                                        clock.instant(),
                                        "granted_by_user_id",
                                        user,
                                        "grant_reason",
                                        "受控部署初始化附加角色"));
                    }
                    sql.execute(
                            "UPDATE app_user SET"
                                + " security_version=security_version+1,row_version=row_version+1,updated_at=?"
                                + " WHERE id=?",
                            clock.instant(),
                            user);
                    sql.insert(
                            "audit_log",
                            Rows.map(
                                    "actor_user_id",
                                    null,
                                    "subject_user_id",
                                    user,
                                    "actor_type",
                                    "WORKER",
                                    "action_code",
                                    "BOOTSTRAP_ADMIN",
                                    "resource_type",
                                    "ROLE_ASSIGNMENT",
                                    "resource_id",
                                    assignment,
                                    "request_id",
                                    UUID.randomUUID().toString(),
                                    "operation_id",
                                    UUID.randomUUID().toString(),
                                    "outcome",
                                    "SUCCESS",
                                    "reason_code",
                                    "CONTROLLED_BOOTSTRAP",
                                    "metadata_json",
                                    Rows.map("roleCodes", roleCodes),
                                    "retention_until",
                                    clock.instant().plus(Duration.ofDays(180))));
                });
    }
}
