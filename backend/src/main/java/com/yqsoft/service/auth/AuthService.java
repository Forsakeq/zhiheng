package com.yqsoft.service.auth;

import com.yqsoft.dao.SqlStore;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.resource.auth.AuthDtos.*;
import com.yqsoft.resource.auth.AuthViews.*;
import com.yqsoft.resource.common.*;
import com.yqsoft.security.AuthContext;
import com.yqsoft.service.common.CryptoService;
import com.yqsoft.service.common.CursorService;

import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {
    private final SqlStore sql;
    private final CryptoService crypto;
    private final AccessTokenService accessTokens;
    private final CursorService cursors;
    private final VerificationSender sender;
    private final Environment environment;
    private final AuthDeploymentSettings deploymentSettings;
    private final TransactionTemplate tx;
    private final Pbkdf2PasswordEncoder passwords =
            Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    private final SecureRandom random = new SecureRandom();
    private final String dummyPasswordHash;
    private final Map<String, FailureWindow> loginFailures = new ConcurrentHashMap<>();

    public AuthService(
            SqlStore sql,
            CryptoService crypto,
            AccessTokenService accessTokens,
            CursorService cursors,
            VerificationSender sender,
            Environment environment,
            AuthDeploymentSettings deploymentSettings,
            PlatformTransactionManager transactionManager) {
        this.sql = sql;
        this.crypto = crypto;
        this.accessTokens = accessTokens;
        this.cursors = cursors;
        this.sender = sender;
        this.environment = environment;
        this.deploymentSettings = deploymentSettings;
        this.tx = new TransactionTemplate(transactionManager);
        this.dummyPasswordHash = passwords.encode(crypto.randomToken());
    }

    public VerificationSendView sendVerification(VerificationSend request) {
        String email = normalizeEmail(request.destination());
        if ("REGISTER".equals(request.purpose()) && !"MOBILE".equals(request.clientAudience())) {
            throw BusinessException.badRequest("UNSUPPORTED_AUDIENCE", "注册仅面向手机账号");
        }
        if ("REGISTER".equals(request.purpose()) && !deploymentSettings.registrationEnabled()) {
            throw BusinessException.unavailable("REGISTRATION_DISABLED", "注册尚未启用");
        }
        byte[] lookup = emailHash(email);
        String challenge = UUID.randomUUID().toString();
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        int ttlMinutes = numberConfig("auth.verification_ttl_minutes", 10, 1, 10);
        int resendSeconds = numberConfig("auth.verification_resend_seconds", 60, 30, 300);
        int hourlyLimit = numberConfig("auth.verification_hourly_limit", 5, 1, 10);
        int maxAttempts = numberConfig("auth.verification_max_attempts", 5, 1, 10);
        Instant expires = Instant.now().plusSeconds(ttlMinutes * 60L);
        long id =
                Objects.requireNonNull(
                        tx.execute(
                                status -> {
                                    List<Map<String, Object>> recent =
                                            sql.query(
                                                    "SELECT id,created_at FROM auth_verification"
                                                        + " WHERE destination_hash=? AND"
                                                        + " created_at>UTC_TIMESTAMP(3)-INTERVAL 1"
                                                        + " HOUR ORDER BY id DESC FOR UPDATE",
                                                    lookup);
                                    if (recent.size() >= hourlyLimit
                                            || (!recent.isEmpty()
                                                    && Rows.instant(recent.getFirst(), "created_at")
                                                            .isAfter(
                                                                    Instant.now()
                                                                            .minusSeconds(
                                                                                    resendSeconds)))) {
                                        throw new BusinessException(
                                                429, "RATE_LIMITED", "请稍后再获取验证码");
                                    }
                                    sql.execute(
                                            "UPDATE auth_verification SET"
                                                + " invalidated_at=UTC_TIMESTAMP(3) WHERE"
                                                + " destination_hash=? AND purpose=? AND"
                                                + " client_audience=? AND consumed_at IS NULL AND"
                                                + " invalidated_at IS NULL",
                                            lookup,
                                            request.purpose(),
                                            request.clientAudience());
                                    Long userId =
                                            sql.find(
                                                            "SELECT id FROM app_user WHERE"
                                                                    + " email_lookup_hmac=? AND"
                                                                    + " deleted_at IS NULL",
                                                            lookup)
                                                    .map(row -> Rows.number(row, "id"))
                                                    .orElse(null);
                                    return sql.insert(
                                            "auth_verification",
                                            Rows.map(
                                                    "challenge_id",
                                                    challenge,
                                                    "user_id",
                                                    userId,
                                                    "purpose",
                                                    request.purpose(),
                                                    "channel",
                                                    "EMAIL",
                                                    "destination_hash",
                                                    lookup,
                                                    "destination_cipher",
                                                    crypto.encrypt(email),
                                                    "proof_hash",
                                                    crypto.hmac(
                                                            "verification-code",
                                                            challenge + ":" + code),
                                                    "encryption_key_ref",
                                                    crypto.keyRef(),
                                                    "expires_at",
                                                    expires,
                                                    "attempt_count",
                                                    0,
                                                    "max_attempts",
                                                    maxAttempts,
                                                    "client_audience",
                                                    request.clientAudience()));
                                }));
        try {
            sender.send(email, code, request.purpose());
        } catch (RuntimeException exception) {
            sql.execute(
                    "UPDATE auth_verification SET invalidated_at=UTC_TIMESTAMP(3) WHERE id=?", id);
            throw exception;
        }
        return new VerificationSendView(id, expires, resendSeconds, mask(email));
    }

    public Tokens register(Register request, String clientPlatform) {
        if (!deploymentSettings.registrationEnabled()) {
            throw BusinessException.unavailable("REGISTRATION_DISABLED", "注册尚未启用");
        }
        String policy = deploymentSettings.servicePolicy().orElseThrow().version();
        if (!policy.equals(request.policyVersion())) {
            throw BusinessException.conflict("POLICY_CHANGED", "服务政策版本已更新，请重新阅读");
        }
        try {
            ZoneId.of(request.timeZone());
        } catch (DateTimeException exception) {
            throw BusinessException.badRequest("TIME_ZONE_INVALID", "请使用有效IANA时区");
        }
        String platform = actualPlatform(request.platform(), clientPlatform, "MOBILE");
        String email = normalizeEmail(request.account());
        byte[] lookup = emailHash(email);
        Outcome<Tokens> outcome =
                tx.execute(
                        status -> {
                            if (!consumeVerification(
                                    request.verificationId(),
                                    request.verificationCode(),
                                    lookup,
                                    "REGISTER",
                                    "MOBILE")) {
                                return Outcome.failure("VERIFICATION_INVALID");
                            }
                            if (sql.count(
                                            "SELECT COUNT(*) FROM app_user WHERE"
                                                    + " email_lookup_hmac=?",
                                            lookup)
                                    > 0) {
                                throw BusinessException.conflict(
                                        "ACCOUNT_UNAVAILABLE", "该账号无法注册，请尝试登录或找回密码");
                            }
                            Instant now = Instant.now();
                            long userId =
                                    sql.insert(
                                            "app_user",
                                            Rows.map(
                                                    "account_no",
                                                    UUID.randomUUID()
                                                            .toString()
                                                            .replace("-", "")
                                                            .substring(0, 26),
                                                    "password_hash",
                                                    passwords.encode(request.password()),
                                                    "password_updated_at",
                                                    now,
                                                    "status",
                                                    "ACTIVE",
                                                    "security_version",
                                                    1,
                                                    "data_write_fence",
                                                    "OPEN",
                                                    "registered_at",
                                                    now,
                                                    "email_lookup_hmac",
                                                    lookup,
                                                    "email_cipher",
                                                    crypto.encrypt(email),
                                                    "email_key_ref",
                                                    crypto.keyRef(),
                                                    "email_verified_at",
                                                    now,
                                                    "registration_policy_version",
                                                    request.policyVersion(),
                                                    "registration_policy_accepted_at",
                                                    now));
                            sql.insert(
                                    "user_profile",
                                    Rows.map(
                                            "user_id",
                                            userId,
                                            "display_name",
                                            request.nickname() == null
                                                    ? "知衡用户"
                                                    : request.nickname().trim(),
                                            "time_zone",
                                            request.timeZone(),
                                            "locale",
                                            "zh-CN",
                                            "adult_confirmed_at",
                                            now,
                                            "onboarding_step",
                                            "GOALS",
                                            "week_start_day",
                                            1));
                            sql.insert(
                                    "user_preference",
                                    Rows.map(
                                            "user_id",
                                            userId,
                                            "preferred_activity_types_json",
                                            List.of(),
                                            "excluded_activity_types_json",
                                            List.of()));
                            sql.execute(
                                    "INSERT INTO"
                                        + " user_sync_clock(user_id,committed_seq,retained_from_seq,dataset_epoch,updated_at)"
                                        + " VALUES (?,0,1,1,?)",
                                    userId,
                                    Rows.utc(now));
                            long installationId =
                                    createInstallation(
                                            userId,
                                            request.installationKey(),
                                            platform,
                                            "MOBILE",
                                            "首次安装");
                            return Outcome.success(
                                    issueSession(
                                            userId,
                                            installationId,
                                            "MOBILE",
                                            1,
                                            null,
                                            null,
                                            1,
                                            null));
                        });
        return requireOutcome(outcome);
    }

    public Tokens login(Login request, String clientPlatform) {
        String email = normalizeEmail(request.account());
        byte[] lookup = emailHash(email);
        String limiter = Base64.getEncoder().encodeToString(lookup);
        FailureWindow window = loginFailures.get(limiter);
        if (window != null && window.until().isAfter(Instant.now()) && window.count() >= 5) {
            throw new BusinessException(429, "RATE_LIMITED", "登录尝试较多，请稍后再试");
        }
        Map<String, Object> found =
                sql.find(
                                "SELECT * FROM app_user WHERE email_lookup_hmac=? AND deleted_at IS"
                                        + " NULL",
                                lookup)
                        .orElse(null);
        String candidateHash =
                found == null || Rows.text(found, "password_hash") == null
                        ? dummyPasswordHash
                        : Rows.text(found, "password_hash");
        boolean checked = passwords.matches(request.password(), candidateHash);
        boolean matches = found != null && Rows.text(found, "password_hash") != null && checked;
        if (!matches) {
            registerLoginFailure(limiter);
            throw new BusinessException(401, "LOGIN_FAILED", "账号或密码不正确");
        }
        long userId = Rows.number(found, "id");
        Tokens result =
                tx.execute(
                        status -> {
                            Map<String, Object> user = lockUser(userId);
                            assertLoginAllowed(user);
                            // Recheck after the row lock so a concurrent password reset cannot
                            // authenticate the old password.
                            if (!passwords.matches(
                                    request.password(), Rows.text(user, "password_hash"))) {
                                throw new BusinessException(401, "LOGIN_FAILED", "账号或密码不正确");
                            }
                            if ("ADMIN".equals(request.clientAudience())
                                    && !hasAdminPermissions(userId)) {
                                throw BusinessException.forbidden("PERMISSION_DENIED", "没有后台权限");
                            }
                            Map<String, Object> installation =
                                    sql.find(
                                                    "SELECT * FROM client_installation WHERE"
                                                        + " user_id=? AND installation_key=? AND"
                                                        + " deleted_at IS NULL FOR UPDATE",
                                                    userId,
                                                    request.installationKey().toString())
                                            .orElse(null);
                            long installationId;
                            if (installation == null) {
                                String platform =
                                        actualPlatform(
                                                request.platform(),
                                                clientPlatform,
                                                request.clientAudience());
                                installationId =
                                        createInstallation(
                                                userId,
                                                request.installationKey(),
                                                platform,
                                                request.clientAudience(),
                                                request.clientName());
                            } else {
                                if (installation.get("revoked_at") != null) {
                                    throw new BusinessException(
                                            401, "INSTALLATION_REVOKED", "该安装已移除，请重装或使用新的安装编号");
                                }
                                String expectedKind =
                                        "ADMIN".equals(request.clientAudience()) ? "WEB" : "PHONE";
                                if (!expectedKind.equals(
                                        Rows.text(installation, "installation_kind"))) {
                                    throw BusinessException.forbidden(
                                            "AUDIENCE_MISMATCH", "安装类型与登录入口不符");
                                }
                                installationId = Rows.number(installation, "id");
                                sql.execute(
                                        "UPDATE client_installation SET"
                                                + " last_seen_at=UTC_TIMESTAMP(3) WHERE id=?",
                                        installationId);
                            }
                            sql.execute(
                                    "UPDATE app_user SET last_login_at=UTC_TIMESTAMP(3) WHERE id=?",
                                    userId);
                            return issueSession(
                                    userId,
                                    installationId,
                                    request.clientAudience(),
                                    Rows.number(user, "security_version"),
                                    null,
                                    null,
                                    1,
                                    null);
                        });
        loginFailures.remove(limiter);
        return result;
    }

    public Tokens refresh(Refresh request) {
        byte[] hash = crypto.hashToken(request.refreshToken());
        Map<String, Object> found =
                sql.find("SELECT user_id FROM auth_session WHERE token_hash=?", hash)
                        .orElseThrow(() -> new BusinessException(401, "INVALID_REFRESH", "登录已失效"));
        Outcome<Tokens> outcome =
                tx.execute(
                        status -> {
                            long userId = Rows.number(found, "user_id");
                            Map<String, Object> user = lockUser(userId);
                            Map<String, Object> session =
                                    sql.one(
                                            "SELECT * FROM auth_session WHERE token_hash=? FOR"
                                                    + " UPDATE",
                                            hash);
                            Map<String, Object> installation =
                                    sql.one(
                                            "SELECT * FROM client_installation WHERE id=? AND"
                                                    + " user_id=? FOR UPDATE",
                                            Rows.number(session, "installation_id"),
                                            userId);
                            if (!request.installationKey()
                                            .toString()
                                            .equals(Rows.text(installation, "installation_key"))
                                    || !request.clientAudience()
                                            .equals(Rows.text(session, "client_audience"))) {
                                return Outcome.failure("INVALID_REFRESH");
                            }
                            if (session.get("consumed_at") != null) {
                                sql.execute(
                                        "UPDATE auth_session SET"
                                            + " revoked_at=COALESCE(revoked_at,UTC_TIMESTAMP(3)),revoke_reason='REUSE'"
                                            + " WHERE user_id=? AND family_id=?",
                                        userId,
                                        Rows.text(session, "family_id"));
                                return Outcome.failure("REFRESH_REUSED");
                            }
                            if (session.get("revoked_at") != null
                                    || installation.get("revoked_at") != null
                                    || installation.get("deleted_at") != null
                                    || !Rows.instant(session, "expires_at").isAfter(Instant.now())
                                    || !Rows.instant(session, "family_expires_at")
                                            .isAfter(Instant.now())
                                    || Rows.number(user, "security_version")
                                            != Rows.number(session, "security_version")) {
                                return Outcome.failure("SESSION_REVOKED");
                            }
                            assertLoginAllowed(user);
                            if ("ADMIN".equals(request.clientAudience())
                                    && !hasAdminPermissions(userId)) {
                                return Outcome.failure("SESSION_REVOKED");
                            }
                            return Outcome.success(
                                    issueSession(
                                            userId,
                                            Rows.number(session, "installation_id"),
                                            request.clientAudience(),
                                            Rows.number(user, "security_version"),
                                            Rows.number(session, "id"),
                                            Rows.text(session, "family_id"),
                                            Rows.number(session, "generation") + 1,
                                            Rows.instant(session, "family_expires_at")));
                        });
        return requireOutcome(outcome);
    }

    public void logout(String scope) {
        var current = AuthContext.current();
        tx.executeWithoutResult(
                status -> {
                    lockUser(current.userId());
                    if ("ALL".equals(scope)) {
                        sql.execute(
                                "UPDATE app_user SET"
                                    + " security_version=security_version+1,row_version=row_version+1,updated_at=UTC_TIMESTAMP(3)"
                                    + " WHERE id=?",
                                current.userId());
                        sql.execute(
                                "UPDATE auth_session SET"
                                    + " revoked_at=COALESCE(revoked_at,UTC_TIMESTAMP(3)),revoke_reason='LOGOUT'"
                                    + " WHERE user_id=?",
                                current.userId());
                        sql.execute(
                                "UPDATE client_installation SET"
                                    + " device_credential_hash=NULL,credential_expires_at=NULL,"
                                    + "pairing_token_hash=NULL,pairing_expires_at=NULL,credential_revision=credential_revision+1,row_version=row_version+1,updated_at=UTC_TIMESTAMP(3)"
                                    + " WHERE user_id=? AND installation_kind='WATCH'",
                                current.userId());
                    } else {
                        Map<String, Object> session =
                                sql.one(
                                        "SELECT family_id FROM auth_session WHERE id=?",
                                        current.sessionId());
                        sql.execute(
                                "UPDATE auth_session SET"
                                    + " revoked_at=COALESCE(revoked_at,UTC_TIMESTAMP(3)),revoke_reason='LOGOUT'"
                                    + " WHERE user_id=? AND family_id=?",
                                current.userId(),
                                Rows.text(session, "family_id"));
                    }
                });
    }

    public PageResult<SessionView> sessions(String cursor, int limit) {
        long userId = AuthContext.userId();
        int pageSize = Math.max(1, Math.min(limit, 100));
        Map<String, Object> state = cursors.decode(cursor, "auth-sessions");
        long before =
                state.isEmpty()
                        ? Long.MAX_VALUE
                        : Long.parseLong(String.valueOf(state.get("before")));
        List<Map<String, Object>> rows =
                sql.query(
                        "SELECT s.*,i.device_label,i.last_seen_at FROM auth_session s "
                                + "JOIN client_installation i ON i.id=s.installation_id "
                                + "WHERE s.user_id=? AND s.id<? AND NOT EXISTS "
                                + "(SELECT 1 FROM auth_session n WHERE n.parent_session_id=s.id) "
                                + "ORDER BY s.id DESC LIMIT ?",
                        userId,
                        before,
                        pageSize + 1);
        boolean more = rows.size() > pageSize;
        if (more) {
            rows = rows.subList(0, pageSize);
        }
        List<SessionView> items =
                rows.stream()
                        .map(
                                row ->
                                        new SessionView(
                                                Rows.number(row, "id"),
                                                Rows.text(row, "family_id"),
                                                Rows.number(row, "generation"),
                                                Rows.text(row, "client_audience"),
                                                Rows.number(row, "installation_id"),
                                                Rows.text(row, "client_label"),
                                                Rows.instant(row, "created_at"),
                                                Rows.instant(row, "last_seen_at"),
                                                Rows.instant(row, "expires_at"),
                                                Rows.number(row, "installation_id")
                                                        == AuthContext.current().installationId(),
                                                row.get("revoked_at") != null
                                                        ? "REVOKED"
                                                        : Rows.instant(row, "expires_at")
                                                                        .isBefore(Instant.now())
                                                                ? "EXPIRED"
                                                                : "ACTIVE"))
                        .toList();
        String next =
                more
                        ? cursors.encode(
                                "auth-sessions",
                                Rows.map("before", Rows.number(rows.getLast(), "id")))
                        : null;
        return PageResult.of(items, next, more);
    }

    public void revokeSession(long sessionId) {
        long userId = AuthContext.userId();
        tx.executeWithoutResult(
                status -> {
                    lockUser(userId);
                    Map<String, Object> row =
                            sql.one(
                                    "SELECT * FROM auth_session WHERE id=? AND user_id=? FOR"
                                            + " UPDATE",
                                    sessionId,
                                    userId);
                    sql.execute(
                            "UPDATE auth_session SET"
                                + " revoked_at=COALESCE(revoked_at,UTC_TIMESTAMP(3)),revoke_reason='LOGOUT'"
                                + " WHERE user_id=? AND family_id=?",
                            userId,
                            Rows.text(row, "family_id"));
                });
    }

    public PasswordResetView resetPassword(ResetPassword request) {
        byte[] lookup = emailHash(normalizeEmail(request.account()));
        Map<String, Object> found =
                sql.find(
                                "SELECT id FROM app_user WHERE email_lookup_hmac=? "
                                        + "AND deleted_at IS NULL",
                                lookup)
                        .orElse(null);
        if (found == null) {
            throw new BusinessException(401, "VERIFICATION_INVALID", "验证码或账号不可用");
        }
        Outcome<Boolean> outcome =
                tx.execute(
                        status -> {
                            Map<String, Object> user = lockUser(Rows.number(found, "id"));
                            assertLoginAllowed(user);
                            if (!consumeVerification(
                                    request.verificationId(),
                                    request.verificationCode(),
                                    lookup,
                                    "RESET_PASSWORD",
                                    request.clientAudience())) {
                                return Outcome.failure("VERIFICATION_INVALID");
                            }
                            long userId = Rows.number(user, "id");
                            sql.execute(
                                    "UPDATE app_user SET"
                                        + " password_hash=?,password_updated_at=UTC_TIMESTAMP(3),"
                                        + "security_version=security_version+1,row_version=row_version+1,updated_at=UTC_TIMESTAMP(3)"
                                        + " WHERE id=?",
                                    passwords.encode(request.newPassword()),
                                    userId);
                            sql.execute(
                                    "UPDATE auth_session SET"
                                        + " revoked_at=COALESCE(revoked_at,UTC_TIMESTAMP(3)),revoke_reason='PASSWORD_CHANGED'"
                                        + " WHERE user_id=?",
                                    userId);
                            sql.execute(
                                    "UPDATE client_installation SET"
                                        + " device_credential_hash=NULL,credential_expires_at=NULL,"
                                        + "pairing_token_hash=NULL,pairing_expires_at=NULL,credential_revision=credential_revision+1,row_version=row_version+1,updated_at=UTC_TIMESTAMP(3)"
                                        + " WHERE user_id=? AND installation_kind='WATCH'",
                                    userId);
                            return Outcome.success(true);
                        });
        requireOutcome(outcome);
        return new PasswordResetView(true, true, true);
    }

    public void reauthenticate(long userId, String audience, ReauthProof proof) {
        if (proof == null) {
            throw BusinessException.badRequest("REAUTH_REQUIRED", "请重新验证身份");
        }
        if ("PASSWORD".equals(proof.method())) {
            if (proof.password() == null
                    || proof.verificationId() != null
                    || proof.verificationCode() != null) {
                throw BusinessException.badRequest("VALIDATION_FAILED", "重新认证字段不匹配");
            }
            Map<String, Object> user =
                    sql.one(
                            "SELECT password_hash FROM app_user WHERE id=? AND deleted_at IS NULL",
                            userId);
            if (Rows.text(user, "password_hash") == null
                    || !passwords.matches(proof.password(), Rows.text(user, "password_hash"))) {
                throw new BusinessException(401, "REAUTH_FAILED", "身份验证失败");
            }
        } else if ("VERIFICATION".equals(proof.method())) {
            if (proof.password() != null
                    || proof.verificationId() == null
                    || proof.verificationCode() == null) {
                throw BusinessException.badRequest("VALIDATION_FAILED", "重新认证字段不匹配");
            }
            boolean accepted =
                    Boolean.TRUE.equals(
                            tx.execute(
                                    status -> {
                                        Map<String, Object> user = lockUser(userId);
                                        return consumeVerification(
                                                proof.verificationId(),
                                                proof.verificationCode(),
                                                (byte[]) user.get("email_lookup_hmac"),
                                                "REAUTH",
                                                audience);
                                    }));
            if (!accepted) {
                throw new BusinessException(401, "REAUTH_FAILED", "身份验证失败");
            }
        } else {
            throw BusinessException.badRequest("VALIDATION_FAILED", "不支持的重新认证方式");
        }
    }

    private Tokens issueSession(
            long userId,
            long installationId,
            String audience,
            long securityVersion,
            Long parentId,
            String familyId,
            long generation,
            Instant familyExpiresAt) {
        Instant now = Instant.now();
        Instant familyExpiry =
                familyExpiresAt == null
                        ? now.plus(
                                Duration.ofDays(
                                        numberConfig("auth.session_family_max_days", 30, 1, 30)))
                        : familyExpiresAt;
        Instant refreshExpiry =
                now.plus(Duration.ofDays(numberConfig("auth.refresh_token_days", 7, 1, 30)));
        if (refreshExpiry.isAfter(familyExpiry)) {
            refreshExpiry = familyExpiry;
        }
        String family = familyId == null ? UUID.randomUUID().toString() : familyId;
        String refresh = crypto.randomToken();
        long id =
                sql.insert(
                        "auth_session",
                        Rows.map(
                                "user_id",
                                userId,
                                "installation_id",
                                installationId,
                                "family_id",
                                family,
                                "parent_session_id",
                                parentId,
                                "token_hash",
                                crypto.hashToken(refresh),
                                "security_version",
                                securityVersion,
                                "issued_at",
                                now,
                                "expires_at",
                                refreshExpiry,
                                "family_expires_at",
                                familyExpiry,
                                "client_audience",
                                audience,
                                "generation",
                                generation,
                                "client_label",
                                Rows.text(
                                        sql.one(
                                                "SELECT device_label FROM client_installation WHERE"
                                                        + " id=?",
                                                installationId),
                                        "device_label")));
        if (parentId != null) {
            sql.execute(
                    "UPDATE auth_session SET consumed_at=?,replaced_by_session_id=? WHERE id=?",
                    Rows.utc(now),
                    id,
                    parentId);
        }
        Instant accessExpiry =
                now.plusSeconds(numberConfig("auth.access_token_minutes", 15, 1, 30) * 60L);
        if (accessExpiry.isAfter(refreshExpiry)) accessExpiry = refreshExpiry;
        long epoch =
                Rows.number(
                        sql.one(
                                "SELECT dataset_epoch FROM user_sync_clock WHERE user_id=?",
                                userId),
                        "dataset_epoch");
        return new Tokens(
                accessTokens.issue(
                        userId, id, installationId, audience, securityVersion, accessExpiry),
                accessExpiry,
                refresh,
                refreshExpiry,
                id,
                family,
                generation,
                audience,
                installationId,
                userId,
                epoch,
                securityVersion,
                "Bearer");
    }

    private long createInstallation(
            long userId, UUID key, String platform, String audience, String label) {
        return sql.insert(
                "client_installation",
                Rows.map(
                        "user_id",
                        userId,
                        "installation_key",
                        key.toString(),
                        "platform",
                        platform,
                        "app_version",
                        "unreported",
                        "device_label",
                        label,
                        "installation_kind",
                        "ADMIN".equals(audience) ? "WEB" : "PHONE",
                        "capabilities_json",
                        Rows.map(
                                "localReminder",
                                "UNVERIFIED",
                                "deliveryObservation",
                                "NONE",
                                "watchTaskChannel",
                                "NONE",
                                "secureCredentialStorage",
                                "UNVERIFIED",
                                "verifiedAt",
                                null),
                        "notification_permission",
                        "UNKNOWN",
                        "last_seen_at",
                        Instant.now()));
    }

    private boolean consumeVerification(
            String idText, String code, byte[] destination, String purpose, String audience) {
        long id = Ids.parse(idText);
        Map<String, Object> row =
                sql.find("SELECT * FROM auth_verification WHERE id=? FOR UPDATE", id).orElse(null);
        if (row == null
                || !purpose.equals(Rows.text(row, "purpose"))
                || !audience.equals(Rows.text(row, "client_audience"))
                || !MessageDigest.isEqual(destination, (byte[]) row.get("destination_hash"))
                || row.get("consumed_at") != null
                || row.get("invalidated_at") != null
                || row.get("locked_at") != null
                || !Rows.instant(row, "expires_at").isAfter(Instant.now())) {
            return false;
        }
        boolean correct =
                MessageDigest.isEqual(
                        (byte[]) row.get("proof_hash"),
                        crypto.hmac(
                                "verification-code", Rows.text(row, "challenge_id") + ":" + code));
        if (!correct) {
            sql.execute(
                    "UPDATE auth_verification SET locked_at=CASE WHEN attempt_count+1>=max_attempts"
                        + " THEN UTC_TIMESTAMP(3) ELSE NULL END,attempt_count=attempt_count+1 WHERE"
                        + " id=?",
                    id);
            return false;
        }
        sql.execute("UPDATE auth_verification SET consumed_at=UTC_TIMESTAMP(3) WHERE id=?", id);
        return true;
    }

    private Map<String, Object> lockUser(long userId) {
        sql.one("SELECT * FROM user_sync_clock WHERE user_id=? FOR UPDATE", userId);
        return sql.one("SELECT * FROM app_user WHERE id=? FOR UPDATE", userId);
    }

    private void assertLoginAllowed(Map<String, Object> user) {
        if ("LOCKED".equals(Rows.text(user, "status"))) {
            throw BusinessException.forbidden("ACCOUNT_LOCKED", "账号已锁定");
        }
        if (!"ACTIVE".equals(Rows.text(user, "status")) || user.get("deleted_at") != null) {
            throw new BusinessException(401, "ACCOUNT_UNAVAILABLE", "账号不可登录");
        }
    }

    private boolean hasAdminPermissions(long userId) {
        return sql.count(
                        "SELECT COUNT(*) FROM user_role_assignment a JOIN sys_role r ON"
                            + " r.id=a.role_id AND r.status='ACTIVE' AND r.deleted_at IS NULL JOIN"
                            + " role_permission rp ON rp.role_id=r.id JOIN sys_permission p ON"
                            + " p.id=rp.permission_id AND p.status='ACTIVE' AND p.deleted_at IS"
                            + " NULL WHERE a.user_id=? AND a.status='ACTIVE' AND a.deleted_at IS"
                            + " NULL AND a.valid_from<=UTC_TIMESTAMP(3) AND (a.valid_until IS NULL"
                            + " OR a.valid_until>UTC_TIMESTAMP(3))",
                        userId)
                > 0;
    }

    private String actualPlatform(String body, String header, String audience) {
        String platform = body == null ? header : body;
        if ("ADMIN".equals(audience)) {
            if (platform != null && !"WEB".equals(platform)) {
                throw BusinessException.badRequest("PLATFORM_REQUIRED", "后台登录必须使用WEB安装");
            }
            return "WEB";
        }
        if (platform == null || !Set.of("ANDROID", "HARMONYOS", "IOS").contains(platform)) {
            throw BusinessException.badRequest("PLATFORM_REQUIRED", "首次登录请提交真实客户端platform");
        }
        return platform;
    }

    private int numberConfig(String key, int fallback, int minimum, int maximum) {
        String deployment = environment.getProperty("zhiheng.environment", "DEV");
        int value =
                sql.find(
                                "SELECT value_json FROM app_configuration WHERE environment=? AND"
                                    + " config_key=? AND is_enabled=1 AND deleted_at IS NULL AND"
                                    + " effective_at<=UTC_TIMESTAMP(3)",
                                deployment,
                                key)
                        .map(
                                row ->
                                        Rows.json(row, "value_json")
                                                .path("numberValue")
                                                .asInt(fallback))
                        .orElse(fallback);
        if (value < minimum || value > maximum)
            throw BusinessException.unavailable("AUTH_CONFIGURATION_INVALID", "认证配置无效");
        return value;
    }

    public String normalizeEmail(String input) {
        if (input == null
                || input.length() > 254
                || !input.trim().matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+")) {
            throw BusinessException.badRequest("EMAIL_INVALID", "请输入有效邮箱地址");
        }
        return input.trim().toLowerCase(Locale.ROOT);
    }

    private byte[] emailHash(String email) {
        return crypto.hmac("email-lookup", email);
    }

    private String mask(String email) {
        int separator = email.indexOf('@');
        return email.substring(0, Math.min(1, separator)) + "***" + email.substring(separator);
    }

    private void registerLoginFailure(String key) {
        Instant now = Instant.now();
        loginFailures.compute(
                key,
                (ignored, current) ->
                        current == null || current.until().isBefore(now)
                                ? new FailureWindow(1, now.plusSeconds(300))
                                : new FailureWindow(current.count() + 1, current.until()));
        if (loginFailures.size() > 10000) {
            loginFailures.entrySet().removeIf(entry -> entry.getValue().until().isBefore(now));
        }
    }

    private <T> T requireOutcome(Outcome<T> result) {
        if (result == null || result.error() != null) {
            throw new BusinessException(
                    401, result == null ? "AUTH_FAILED" : result.error(), "身份凭据无效或已过期");
        }
        return result.value();
    }

    private record Outcome<T>(T value, String error) {
        static <T> Outcome<T> success(T value) {
            return new Outcome<>(value, null);
        }

        static <T> Outcome<T> failure(String error) {
            return new Outcome<>(null, error);
        }
    }

    private record FailureWindow(int count, Instant until) {}
}
