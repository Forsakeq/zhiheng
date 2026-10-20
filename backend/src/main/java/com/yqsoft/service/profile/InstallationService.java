package com.yqsoft.service.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yqsoft.dao.SqlStore;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.persistence.MutationExecutor;
import com.yqsoft.resource.common.*;
import com.yqsoft.resource.profile.InstallationDtos.*;
import com.yqsoft.resource.profile.ProfileDtos.VersionBody;
import com.yqsoft.resource.profile.ProfileViews.InstallationView;
import com.yqsoft.security.AuthContext;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
public class InstallationService {
    private final SqlStore sql;
    private final MutationExecutor mutations;
    private final ObjectMapper mapper;

    public InstallationService(SqlStore sql, MutationExecutor mutations, ObjectMapper mapper) {
        this.sql = sql;
        this.mutations = mutations;
        this.mapper = mapper;
    }

    public InstallationView register(Register request) {
        boolean admin = "ADMIN".equals(AuthContext.current().audience());
        if (admin
                        && (!"WEB".equals(request.installationKind())
                                || !"WEB".equals(request.platform()))
                || !admin
                        && (!"PHONE".equals(request.installationKind())
                                || "WEB".equals(request.platform()))) {
            throw BusinessException.badRequest("PLATFORM_INVALID", "安装类型与当前登录入口不符");
        }
        MutationResult receipt =
                mutations.mutate(
                        "INSTALLATION_REGISTER",
                        request,
                        false,
                        () -> {
                            long userId = AuthContext.userId();
                            Map<String, Object> row =
                                    sql.find(
                                                    "SELECT * FROM client_installation WHERE"
                                                        + " user_id=? AND installation_key=? AND"
                                                        + " deleted_at IS NULL FOR UPDATE",
                                                    userId,
                                                    request.installationKey().toString())
                                            .orElse(null);
                            Map<String, Object> values =
                                    Rows.map(
                                            "platform",
                                            request.platform(),
                                            "installation_kind",
                                            request.installationKind(),
                                            "device_label",
                                            request.deviceName(),
                                            "app_version",
                                            request.appVersion(),
                                            "os_version",
                                            request.osVersion(),
                                            "capabilities_json",
                                            mapper.valueToTree(request.capabilities()),
                                            "notification_permission",
                                            request.notificationPermission(),
                                            "last_seen_at",
                                            Instant.now());
                            long id, version;
                            if (row == null) {
                                values.put("user_id", userId);
                                values.put(
                                        "installation_key", request.installationKey().toString());
                                id = sql.insert("client_installation", values);
                                version = 1;
                            } else {
                                if (row.get("revoked_at") != null) {
                                    throw BusinessException.conflict(
                                            "INSTALLATION_REVOKED", "该安装已移除，不能重新启用旧编号");
                                }
                                if (!request.platform().equals(Rows.text(row, "platform"))
                                        || !request.installationKind()
                                                .equals(Rows.text(row, "installation_kind"))) {
                                    throw BusinessException.conflict(
                                            "INSTALLATION_TYPE_CHANGED", "已注册安装不能改变平台和类型");
                                }
                                id = Rows.number(row, "id");
                                version =
                                        sql.update(
                                                "client_installation",
                                                id,
                                                Rows.number(row, "row_version"),
                                                values);
                            }
                            return MutationResult.of("INSTALLATION", id, version);
                        });
        return view(
                sql.owned("client_installation", Ids.parse(receipt.id()), AuthContext.userId()));
    }

    public MutationResult update(long id, Patch request) {
        return mutations.mutate(
                "INSTALLATION_PATCH",
                Rows.map("id", id, "request", request),
                false,
                () -> {
                    Map<String, Object> row =
                            sql.owned("client_installation", id, AuthContext.userId());
                    if (row.get("revoked_at") != null) {
                        throw BusinessException.conflict("INSTALLATION_REVOKED", "该安装已移除");
                    }
                    Map<String, Object> values = new LinkedHashMap<>();
                    if (request.deviceName() != null)
                        values.put("device_label", request.deviceName());
                    if (request.appVersion() != null)
                        values.put("app_version", request.appVersion());
                    if (request.osVersion() != null) values.put("os_version", request.osVersion());
                    if (request.capabilities() != null)
                        values.put("capabilities_json", mapper.valueToTree(request.capabilities()));
                    if (request.notificationPermission() != null)
                        values.put("notification_permission", request.notificationPermission());
                    if (values.isEmpty())
                        throw BusinessException.badRequest("VALIDATION_FAILED", "至少修改一个安装字段");
                    values.put("last_seen_at", Instant.now());
                    long version =
                            sql.update(
                                    "client_installation",
                                    id,
                                    Ids.parse(request.expectedRowVersion()),
                                    values);
                    return MutationResult.of("INSTALLATION", id, version);
                });
    }

    public MutationResult revoke(long id, VersionBody request) {
        return mutations.mutate(
                "INSTALLATION_REVOKE",
                Rows.map("id", id, "request", request),
                false,
                () -> {
                    sql.owned("client_installation", id, AuthContext.userId());
                    long version =
                            sql.update(
                                    "client_installation",
                                    id,
                                    Ids.parse(request.expectedRowVersion()),
                                    Rows.map(
                                            "revoked_at",
                                            Instant.now(),
                                            "device_credential_hash",
                                            null,
                                            "credential_expires_at",
                                            null,
                                            "credential_revision",
                                            Rows.number(
                                                            sql.one(
                                                                    "SELECT credential_revision"
                                                                            + " FROM"
                                                                            + " client_installation"
                                                                            + " WHERE id=?",
                                                                    id),
                                                            "credential_revision")
                                                    + 1,
                                            "bootstrap_id",
                                            null,
                                            "bootstrap_from_seq",
                                            null,
                                            "bootstrap_epoch",
                                            null,
                                            "bootstrap_expires_at",
                                            null,
                                            "bootstrap_progress_json",
                                            null,
                                            "sync_epoch",
                                            null,
                                            "sync_state",
                                            "UNINITIALIZED"));
                    sql.execute(
                            "UPDATE auth_session SET"
                                + " revoked_at=COALESCE(revoked_at,UTC_TIMESTAMP(3)),revoke_reason='INSTALLATION_REVOKED'"
                                + " WHERE user_id=? AND installation_id=?",
                            AuthContext.userId(),
                            id);
                    sql.execute(
                            "UPDATE reminder_delivery SET"
                                + " status='CANCEL_REQUESTED',row_version=row_version+1,updated_at=UTC_TIMESTAMP(3)"
                                + " WHERE user_id=? AND installation_id=? AND status IN"
                                + " ('PENDING','REGISTERED','UNKNOWN')",
                            AuthContext.userId(),
                            id);
                    mutations.outbox(
                            "INSTALLATION_REVOKED",
                            "INSTALLATION",
                            id,
                            version,
                            Rows.map("installationId", id));
                    return MutationResult.of("INSTALLATION", id, version);
                });
    }

    public InstallationView view(Map<String, Object> row) {
        return new InstallationView(
                Rows.number(row, "id"),
                Rows.number(row, "row_version"),
                Rows.text(row, "installation_key"),
                Rows.text(row, "installation_kind"),
                row.get("parent_installation_id") == null
                        ? null
                        : Rows.number(row, "parent_installation_id"),
                Rows.text(row, "platform"),
                Rows.text(row, "device_label"),
                Rows.text(row, "app_version"),
                Rows.text(row, "os_version"),
                mapper.convertValue(Rows.json(row, "capabilities_json"), Capabilities.class),
                Rows.text(row, "notification_permission"),
                Rows.instant(row, "last_seen_at"),
                Rows.number(row, "last_ack_seq"),
                Rows.instant(row, "revoked_at"));
    }
}
