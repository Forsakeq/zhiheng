package com.yqsoft.interceptor;

import com.yqsoft.dao.SqlStore;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.resource.common.Rows;
import com.yqsoft.security.*;
import com.yqsoft.service.auth.AccessTokenService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    private final AccessTokenService tokens;
    private final SqlStore sql;

    public AuthInterceptor(AccessTokenService tokens, SqlStore sql) {
        this.tokens = tokens;
        this.sql = sql;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        AuthContext.clear();
        if ("OPTIONS".equals(request.getMethod()) || !(handler instanceof HandlerMethod method)) {
            return true;
        }
        boolean publicEndpoint =
                AnnotatedElementUtils.hasAnnotation(method.getMethod(), PublicEndpoint.class)
                        || AnnotatedElementUtils.hasAnnotation(
                                method.getBeanType(), PublicEndpoint.class);
        if (publicEndpoint) {
            return true;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(401, "AUTH_REQUIRED", "请先登录");
        }
        AccessTokenService.Claims claims = tokens.verify(authorization.substring(7));
        Map<String, Object> session =
                sql.find(
                                "SELECT s.*,u.status AS account_status,u.security_version AS"
                                    + " current_security_version,u.deleted_at AS"
                                    + " account_deleted_at,u.data_write_fence AS"
                                    + " account_write_fence,i.revoked_at AS"
                                    + " installation_revoked_at,i.deleted_at AS"
                                    + " installation_deleted_at FROM auth_session s JOIN app_user u"
                                    + " ON u.id=s.user_id JOIN client_installation i ON"
                                    + " i.id=s.installation_id AND i.user_id=s.user_id WHERE s.id=?"
                                    + " AND s.user_id=?",
                                claims.sessionId(),
                                claims.userId())
                        .orElseThrow(
                                () -> new BusinessException(401, "SESSION_REVOKED", "登录会话已失效"));
        if (Rows.number(session, "installation_id") != claims.installationId()
                || Rows.number(session, "security_version") != claims.securityVersion()
                || Rows.number(session, "current_security_version") != claims.securityVersion()
                || !claims.audience().equals(Rows.text(session, "client_audience"))
                || session.get("revoked_at") != null
                || session.get("account_deleted_at") != null
                || session.get("installation_revoked_at") != null
                || session.get("installation_deleted_at") != null
                || Rows.instant(session, "expires_at") == null
                || !Rows.instant(session, "expires_at").isAfter(Instant.now())) {
            throw new BusinessException(401, "SESSION_REVOKED", "登录会话已失效");
        }
        String status = Rows.text(session, "account_status");
        if ("LOCKED".equals(status)) {
            throw BusinessException.forbidden("ACCOUNT_LOCKED", "账号已锁定");
        }
        if (!"ACTIVE".equals(status)) {
            throw new BusinessException(401, "SESSION_REVOKED", "账号不可登录");
        }
        String uri = request.getRequestURI();
        boolean adminPath = uri.startsWith("/api/admin/v1/");
        boolean healthPath =
                Set.of(
                                "records",
                                "record-tags",
                                "plans",
                                "tasks",
                                "ai",
                                "statistics",
                                "weekly-reviews",
                                "home",
                                "sync",
                                "device")
                        .stream()
                        .anyMatch(
                                segment ->
                                        uri.equals("/api/v1/" + segment)
                                                || uri.startsWith("/api/v1/" + segment + "/"));
        if (healthPath && !"OPEN".equals(Rows.text(session, "account_write_fence"))) {
            throw BusinessException.conflict("DATA_WRITE_FENCED", "数据清理正在执行，请先查看隐私工单状态");
        }
        boolean authSelfService = request.getRequestURI().startsWith("/api/v1/auth/");
        String requiredAudience =
                adminPath ? "ADMIN" : authSelfService ? claims.audience() : "MOBILE";
        if (!requiredAudience.equals(claims.audience())) {
            throw BusinessException.forbidden("AUDIENCE_MISMATCH", "当前登录凭据不适用于此入口");
        }
        Set<String> permissions = loadPermissions(claims.userId());
        if (adminPath && permissions.isEmpty()) {
            throw BusinessException.forbidden("PERMISSION_DENIED", "没有后台权限");
        }
        RequirePermission required =
                AnnotatedElementUtils.findMergedAnnotation(
                        method.getMethod(), RequirePermission.class);
        if (required == null) {
            required =
                    AnnotatedElementUtils.findMergedAnnotation(
                            method.getBeanType(), RequirePermission.class);
        }
        if (required != null && !permissions.contains(required.value())) {
            throw BusinessException.forbidden("PERMISSION_DENIED", "没有执行此操作的权限");
        }
        AuthContext.set(
                new AuthenticatedUser(
                        claims.userId(),
                        claims.sessionId(),
                        claims.installationId(),
                        claims.audience(),
                        permissions));
        return true;
    }

    public Set<String> loadPermissions(long userId) {
        Set<String> permissions = new HashSet<>();
        for (Map<String, Object> row :
                sql.query(
                        "SELECT DISTINCT p.permission_code FROM user_role_assignment a JOIN"
                            + " sys_role r ON r.id=a.role_id AND r.status='ACTIVE' AND r.deleted_at"
                            + " IS NULL JOIN role_permission rp ON rp.role_id=r.id JOIN"
                            + " sys_permission p ON p.id=rp.permission_id AND p.status='ACTIVE' AND"
                            + " p.deleted_at IS NULL WHERE a.user_id=? AND a.status='ACTIVE' AND"
                            + " a.deleted_at IS NULL AND a.valid_from<=UTC_TIMESTAMP(3) AND"
                            + " (a.valid_until IS NULL OR a.valid_until>UTC_TIMESTAMP(3))",
                        userId)) {
            permissions.add(Rows.text(row, "permission_code"));
        }
        return permissions;
    }
}
