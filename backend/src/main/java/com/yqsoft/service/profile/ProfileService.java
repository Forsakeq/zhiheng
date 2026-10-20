package com.yqsoft.service.profile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yqsoft.dao.SqlStore;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.persistence.MutationExecutor;
import com.yqsoft.resource.common.*;
import com.yqsoft.resource.profile.ProfileDtos.*;
import com.yqsoft.resource.profile.ProfileViews.*;
import com.yqsoft.security.AuthContext;
import com.yqsoft.service.common.CryptoService;
import com.yqsoft.service.common.CursorService;
import com.yqsoft.service.privacy.PrivacyService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;

@Service
public class ProfileService {
    private final SqlStore sql;
    private final MutationExecutor mutations;
    private final CryptoService crypto;
    private final CursorService cursors;
    private final ObjectMapper mapper;
    private final Environment environment;
    private final ObjectProvider<PrivacyService> privacy;

    public ProfileService(
            SqlStore sql,
            MutationExecutor mutations,
            CryptoService crypto,
            CursorService cursors,
            ObjectMapper mapper,
            Environment environment,
            ObjectProvider<PrivacyService> privacy) {
        this.sql = sql;
        this.mutations = mutations;
        this.crypto = crypto;
        this.cursors = cursors;
        this.mapper = mapper;
        this.environment = environment;
        this.privacy = privacy;
    }

    public MeView me() {
        long userId = AuthContext.userId();
        Map<String, Object> user = sql.one("SELECT * FROM app_user WHERE id=?", userId);
        Map<String, Object> clock =
                sql.one("SELECT * FROM user_sync_clock WHERE user_id=?", userId);
        String email = crypto.decrypt(user.get("email_cipher"));
        int separator = email == null ? -1 : email.indexOf('@');
        String masked =
                separator < 0
                        ? "***"
                        : email.substring(0, Math.min(separator, 1))
                                + "***"
                                + email.substring(separator);
        return new MeView(
                userId,
                masked,
                Rows.text(user, "status"),
                profileView(profileRow()),
                preferenceView(preferenceRow()),
                consents().items(),
                Rows.number(clock, "dataset_epoch"),
                Rows.number(clock, "committed_seq"),
                !"OPEN".equals(Rows.text(user, "data_write_fence")),
                Rows.instant(user, "created_at"));
    }

    public MutationResult updateProfile(ProfilePatch request) {
        requirePatch(request.presentFields(), Set.of("avatarFileId", "bio"), request);
        return mutations.mutate(
                "PROFILE_PATCH",
                patchCanonical(request, request.presentFields()),
                false,
                () -> {
                    Map<String, Object> row = profileRow();
                    Map<String, Object> values = new LinkedHashMap<>();
                    Set<String> fields = request.presentFields();
                    if (fields.contains("nickname"))
                        values.put("display_name", request.nickname.trim());
                    if (fields.contains("avatarFileId")) {
                        Long fileId = Ids.optional(request.avatarFileId);
                        if (fileId != null) {
                            Map<String, Object> file =
                                    sql.owned("file_object", fileId, AuthContext.userId());
                            if (!"AVATAR".equals(Rows.text(file, "purpose"))
                                    || !"CLEAN".equals(Rows.text(file, "status"))) {
                                throw BusinessException.badRequest("FILE_NOT_READY", "头像文件不可用");
                            }
                        }
                        values.put("avatar_file_id", fileId);
                    }
                    if (fields.contains("bio")) {
                        values.put("bio_cipher", crypto.encrypt(request.bio));
                        values.put(
                                "encryption_key_ref", request.bio == null ? null : crypto.keyRef());
                    }
                    if (fields.contains("timeZone")) {
                        zone(request.timeZone);
                        values.put("time_zone", request.timeZone);
                    }
                    if (fields.contains("weekStartsOn"))
                        values.put("week_start_day", request.weekStartsOn);
                    if (fields.contains("onboardingStep")) {
                        values.put("onboarding_step", request.onboardingStep);
                        values.put(
                                "onboarding_completed_at",
                                "COMPLETED".equals(request.onboardingStep) ? Instant.now() : null);
                    }
                    long id = Rows.number(row, "id");
                    long version =
                            sql.update(
                                    "user_profile",
                                    id,
                                    Ids.parse(request.expectedRowVersion),
                                    values);
                    mutations.upsert("PROFILE", id, version, projection(profileView(profileRow())));
                    return MutationResult.of("PROFILE", id, version);
                });
    }

    public MutationResult updatePreferences(PreferencePatch request) {
        requirePatch(
                request.presentFields(),
                Set.of(
                        "preferredSessionMinutes",
                        "preferredWakeTime",
                        "preferredBedTime",
                        "quietStart",
                        "quietEnd"),
                request);
        return mutations.mutate(
                "PREFERENCE_PATCH",
                patchCanonical(request, request.presentFields()),
                false,
                () -> {
                    Map<String, Object> row = preferenceRow();
                    Map<String, Object> values = new LinkedHashMap<>();
                    Set<String> f = request.presentFields();
                    if (f.contains("theme")) values.put("theme_mode", request.theme);
                    if (f.contains("reduceMotion"))
                        values.put(
                                "reduced_motion",
                                Boolean.TRUE.equals(request.reduceMotion) ? 1 : 0);
                    if (f.contains("fontScale")) values.put("font_scale", request.fontScale);
                    if (f.contains("activityPreferences")) {
                        unique(request.activityPreferences);
                        values.put("preferred_activity_types_json", request.activityPreferences);
                    }
                    if (f.contains("excludedActivityTypes")) {
                        unique(request.excludedActivityTypes);
                        values.put("excluded_activity_types_json", request.excludedActivityTypes);
                    }
                    if (f.contains("dailyTaskLimit"))
                        values.put("daily_task_limit", request.dailyTaskLimit);
                    if (f.contains("defaultTaskMinutes"))
                        values.put("default_task_minutes", request.defaultTaskMinutes);
                    if (f.contains("preferredSessionMinutes"))
                        values.put("preferred_session_minutes", request.preferredSessionMinutes);
                    if (f.contains("preferredWakeTime"))
                        values.put("preferred_wake_time", time(request.preferredWakeTime));
                    if (f.contains("preferredBedTime"))
                        values.put("preferred_bed_time", time(request.preferredBedTime));
                    if (f.contains("planTone")) values.put("plan_tone", request.planTone);
                    if (f.contains("quietHoursEnabled"))
                        values.put(
                                "quiet_hours_enabled",
                                Boolean.TRUE.equals(request.quietHoursEnabled) ? 1 : 0);
                    if (f.contains("quietStart") != f.contains("quietEnd")) {
                        throw BusinessException.badRequest("VALIDATION_FAILED", "免打扰起止时间必须一起设置");
                    }
                    if (f.contains("quietStart")
                            && (request.quietStart == null) != (request.quietEnd == null)) {
                        throw BusinessException.badRequest("VALIDATION_FAILED", "免打扰起止时间必须一起填写或清空");
                    }
                    if (f.contains("quietStart")) {
                        values.put("quiet_start", time(request.quietStart));
                        values.put("quiet_end", time(request.quietEnd));
                    }
                    boolean enabled =
                            values.containsKey("quiet_hours_enabled")
                                    ? ((Integer) values.get("quiet_hours_enabled")) == 1
                                    : Rows.bool(row, "quiet_hours_enabled");
                    Object start =
                            values.containsKey("quiet_start")
                                    ? values.get("quiet_start")
                                    : row.get("quiet_start");
                    Object end =
                            values.containsKey("quiet_end")
                                    ? values.get("quiet_end")
                                    : row.get("quiet_end");
                    if (enabled
                            && (start == null
                                    || end == null
                                    || start.toString().equals(end.toString()))) {
                        throw BusinessException.badRequest("VALIDATION_FAILED", "开启免打扰需提供不同的起止时间");
                    }
                    long id = Rows.number(row, "id");
                    long version =
                            sql.update(
                                    "user_preference",
                                    id,
                                    Ids.parse(request.expectedRowVersion),
                                    values);
                    mutations.upsert(
                            "PREFERENCE", id, version, projection(preferenceView(preferenceRow())));
                    mutations.outbox(
                            "REMINDER_REBUILD_REQUESTED",
                            "PREFERENCE",
                            id,
                            version,
                            Rows.map("reason", "PREFERENCE_CHANGED"));
                    return MutationResult.of("PREFERENCE", id, version);
                });
    }

    public ConsentListView consents() {
        List<ConsentView> items =
                sql
                        .query(
                                "SELECT * FROM user_consent WHERE user_id=? AND deleted_at IS NULL"
                                        + " ORDER BY id",
                                AuthContext.userId())
                        .stream()
                        .map(this::consentView)
                        .toList();
        String policy = environment.getProperty("zhiheng.auth.consent-policy-version", "");
        List<PolicyVersion> policies =
                policy.isBlank()
                        ? List.of()
                        : List.of(
                                new PolicyVersion("AI_PROCESSING", policy, "AI处理说明", policyUrl("ai-policy-url")),
                                new PolicyVersion("NOTIFICATION", policy, "提醒通知说明", policyUrl("notification-policy-url")),
                                new PolicyVersion("EXTERNAL_HEALTH", policy, "外部健康数据说明", policyUrl("external-health-policy-url")));
        return new ConsentListView(items, policies);
    }

    private String policyUrl(String property) {
        String configured = environment.getProperty("zhiheng.auth." + property, "").trim();
        if (configured.isEmpty()) return null;
        try {
            URI uri = URI.create(configured);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getHost().isBlank() || uri.getRawUserInfo() != null) return null;
            return configured;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public MutationResult changeConsent(String purpose, ConsentChange request) {
        if (!Set.of("AI_PROCESSING", "NOTIFICATION", "EXTERNAL_HEALTH").contains(purpose)) {
            throw BusinessException.badRequest("UNSUPPORTED_TYPE", "不支持的授权用途");
        }
        String policy = environment.getProperty("zhiheng.auth.consent-policy-version", "");
        if (policy.isBlank())
            throw BusinessException.unavailable("POLICY_NOT_PUBLISHED", "授权说明尚未配置");
        if (!policy.equals(request.policyVersion())) {
            throw BusinessException.conflict("POLICY_CHANGED", "授权说明已更新，请重新阅读");
        }
        MutationResult result =
                mutations.mutate(
                        "CONSENT_CHANGE",
                        Rows.map("purpose", purpose, "request", request),
                        false,
                        () -> {
                            long userId = AuthContext.userId();
                            Map<String, Object> row =
                                    sql.find(
                                                    "SELECT * FROM user_consent WHERE user_id=? AND"
                                                        + " purpose_code=? AND deleted_at IS NULL"
                                                        + " FOR UPDATE",
                                                    userId,
                                                    purpose)
                                            .orElse(null);
                            long expected = zeroVersion(request.expectedRowVersion());
                            if ((row == null && expected != 0)
                                    || (row != null
                                            && Rows.number(row, "row_version") != expected)) {
                                throw BusinessException.conflict("ROW_VERSION_CONFLICT", "授权状态已更新");
                            }
                            boolean grant = "GRANT".equals(request.decision());
                            if (grant && Boolean.TRUE.equals(request.deleteExisting())) {
                                throw BusinessException.badRequest(
                                        "VALIDATION_FAILED", "仅撤回授权时可申请清除已有数据");
                            }
                            long consentVersion =
                                    row == null ? 1 : Rows.number(row, "consent_version") + 1;
                            Map<String, Object> values =
                                    Rows.map(
                                            "status",
                                            grant ? "GRANTED" : "REVOKED",
                                            "policy_version",
                                            policy,
                                            "scope_json",
                                            List.of(purpose),
                                            "consent_version",
                                            consentVersion,
                                            "granted_at",
                                            grant
                                                    ? Instant.now()
                                                    : row == null ? null : row.get("granted_at"),
                                            "revoked_at",
                                            grant ? null : Instant.now(),
                                            "installation_id",
                                            AuthContext.current().installationId());
                            long id, version;
                            if (row == null) {
                                values.put("user_id", userId);
                                values.put("purpose_code", purpose);
                                id = sql.insert("user_consent", values);
                                version = 1;
                            } else {
                                id = Rows.number(row, "id");
                                version = sql.update("user_consent", id, expected, values);
                            }
                            long eventId =
                                    sql.insert(
                                            "user_consent_event",
                                            Rows.map(
                                                    "user_id",
                                                    userId,
                                                    "consent_id",
                                                    id,
                                                    "event_type",
                                                    grant ? "GRANT" : "REVOKE",
                                                    "consent_version",
                                                    consentVersion,
                                                    "policy_version",
                                                    policy,
                                                    "scope_json",
                                                    List.of(purpose),
                                                    "occurred_at",
                                                    Instant.now(),
                                                    "installation_id",
                                                    AuthContext.current().installationId(),
                                                    "operation_id",
                                                    RequestContext.idempotencyKey(),
                                                    "notice_digest",
                                                    crypto.hmac(
                                                            "consent-notice",
                                                            purpose + ":" + policy)));
                            sql.execute(
                                    "UPDATE user_consent SET last_event_id=? WHERE id=?",
                                    eventId,
                                    id);
                            List<String> cancelledRuns = new ArrayList<>();
                            if (!grant) {
                                if ("AI_PROCESSING".equals(purpose)) {
                                    for (Map<String, Object> run :
                                            sql.query(
                                                    "SELECT id FROM ai_run WHERE user_id=? "
                                                            + "AND status IN ('QUEUED','RUNNING')",
                                                    userId)) {
                                        cancelledRuns.add(Long.toString(Rows.number(run, "id")));
                                    }
                                    sql.execute(
                                            "UPDATE ai_run SET finished_at=CASE WHEN"
                                                + " status='QUEUED' THEN UTC_TIMESTAMP(3) ELSE"
                                                + " finished_at END,status=CASE WHEN"
                                                + " status='QUEUED' THEN 'CANCELLED' ELSE"
                                                + " 'CANCEL_REQUESTED' END,"
                                                + "error_code='CONSENT_WITHDRAWN',row_version=row_version+1,updated_at=UTC_TIMESTAMP(3)"
                                                + " WHERE user_id=? AND status IN"
                                                + " ('QUEUED','RUNNING')",
                                            userId);
                                } else if ("EXTERNAL_HEALTH".equals(purpose)) {
                                    sql.execute(
                                            "UPDATE external_data_connection SET"
                                                + " status='REVOKED',revoked_at=UTC_TIMESTAMP(3),"
                                                + "authorization_revision=authorization_revision+1,access_token_cipher=NULL,"
                                                + "refresh_token_cipher=NULL,provider_cursor_cipher=NULL,encryption_key_ref=NULL,state_hash=NULL,row_version=row_version+1,updated_at=UTC_TIMESTAMP(3)"
                                                + " WHERE user_id=? AND status<>'REVOKED'",
                                            userId);
                                } else {
                                    sql.execute(
                                            "UPDATE reminder_delivery SET"
                                                + " status='CANCEL_REQUESTED',row_version=row_version+1,updated_at=UTC_TIMESTAMP(3)"
                                                + " WHERE user_id=? AND status IN"
                                                + " ('PENDING','REGISTERED','UNKNOWN')",
                                            userId);
                                }
                                mutations.outbox(
                                        "CONSENT_WITHDRAWN",
                                        "CONSENT",
                                        id,
                                        version,
                                        Rows.map("purpose", purpose));
                            }
                            Long previewId =
                                    Boolean.TRUE.equals(request.deleteExisting())
                                            ? privacy.getObject()
                                                    .previewForConsentWithdrawal(purpose)
                                            : null;
                            return MutationResult.of("CONSENT", id, version)
                                    .withExtra(
                                            Rows.map(
                                                    "consent",
                                                    consentView(
                                                            sql.one(
                                                                    "SELECT * FROM user_consent"
                                                                            + " WHERE id=?",
                                                                    id)),
                                                    "privacyPreviewId",
                                                    previewId,
                                                    "cancelledRunIds",
                                                    cancelledRuns));
                        });
        Map<String, Object> response = new LinkedHashMap<>(result.extra());
        response.put(
                "consent",
                consentView(
                        sql.one(
                                "SELECT * FROM user_consent WHERE id=? AND user_id=?",
                                Ids.parse(result.id()),
                                AuthContext.userId())));
        return result.withExtra(response);
    }

    public GoalDefinitionList definitions(String category) {
        if (category != null
                && !Set.of("ROUTINE", "ACTIVITY", "RECORDING", "CUSTOM").contains(category)) {
            throw BusinessException.badRequest("UNSUPPORTED_TYPE", "不支持的目标分类");
        }
        List<Map<String, Object>> rows =
                category == null
                        ? sql.query(
                                "SELECT * FROM goal_definition WHERE status='ACTIVE' AND deleted_at"
                                        + " IS NULL ORDER BY sort_order,id")
                        : sql.query(
                                "SELECT * FROM goal_definition WHERE status='ACTIVE' AND deleted_at"
                                        + " IS NULL AND goal_category=? ORDER BY sort_order,id",
                                category);
        return new GoalDefinitionList(rows.stream().map(this::definitionView).toList());
    }

    public PageResult<UserGoalView> goals(String status, String cursor, int limit) {
        if (status != null
                && !Set.of("ACTIVE", "PAUSED", "COMPLETED", "ARCHIVED").contains(status)) {
            throw BusinessException.badRequest("UNSUPPORTED_TYPE", "不支持的目标状态");
        }
        return ownedPage(
                "user_goal",
                status == null ? "" : " AND status=?",
                status == null ? List.of() : List.of(status),
                "goals:" + status,
                cursor,
                limit,
                this::goalView);
    }

    public MutationResult createGoal(GoalCreate request) {
        return mutations.mutate(
                "GOAL_CREATE",
                request,
                true,
                () -> {
                    Map<String, Object> definition =
                            sql.one(
                                    "SELECT * FROM goal_definition WHERE id=? "
                                            + "AND status='ACTIVE' AND deleted_at IS NULL",
                                    Ids.parse(request.definitionId()));
                    checkDates(request.startDate(), request.endDate());
                    String zone =
                            request.timeZone() == null
                                    ? Rows.text(profileRow(), "time_zone")
                                    : request.timeZone();
                    zone(zone);
                    Map<String, Object> parameters =
                            parameters(definition, request.targetValue(), request.unit());
                    long id =
                            sql.insert(
                                    "user_goal",
                                    Rows.map(
                                            "user_id",
                                            AuthContext.userId(),
                                            "goal_definition_id",
                                            Rows.number(definition, "id"),
                                            "display_name",
                                            request.title().trim(),
                                            "parameters_json",
                                            parameters,
                                            "parameter_schema_version",
                                            Rows.integer(definition, "schema_version"),
                                            "priority",
                                            request.priority() == null ? 3 : request.priority(),
                                            "status",
                                            "ACTIVE",
                                            "start_local_date",
                                            request.startDate(),
                                            "end_local_date",
                                            request.endDate(),
                                            "goal_time_zone",
                                            zone,
                                            "motivation_cipher",
                                            crypto.encrypt(request.note()),
                                            "encryption_key_ref",
                                            request.note() == null ? null : crypto.keyRef()));
                    mutations.upsert(
                            "GOAL",
                            id,
                            1,
                            projection(goalView(sql.owned("user_goal", id, AuthContext.userId()))));
                    return MutationResult.of("GOAL", id, 1);
                });
    }

    public MutationResult updateGoal(long id, GoalPatch request) {
        requirePatch(
                request.presentFields(), Set.of("targetValue", "unit", "endDate", "note"), request);
        return mutations.mutate(
                "GOAL_PATCH",
                Rows.map("id", id, "patch", patchCanonical(request, request.presentFields())),
                true,
                () -> {
                    Map<String, Object> row = sql.owned("user_goal", id, AuthContext.userId());
                    if ("ARCHIVED".equals(Rows.text(row, "status"))) {
                        throw BusinessException.conflict("ILLEGAL_STATE_TRANSITION", "归档目标不可直接修改");
                    }
                    Set<String> f = request.presentFields();
                    Map<String, Object> values = new LinkedHashMap<>();
                    if (f.contains("title")) values.put("display_name", request.title.trim());
                    if (f.contains("targetValue") || f.contains("unit")) {
                        JsonNode old = Rows.json(row, "parameters_json");
                        BigDecimal target =
                                f.contains("targetValue")
                                        ? request.targetValue
                                        : decimal(old.get("targetValue"));
                        String unit =
                                f.contains("unit") ? request.unit : nullableText(old.get("unit"));
                        Map<String, Object> definition =
                                sql.one(
                                        "SELECT * FROM goal_definition WHERE id=?",
                                        Rows.number(row, "goal_definition_id"));
                        values.put("parameters_json", parameters(definition, target, unit));
                    }
                    if (f.contains("endDate")) {
                        checkDates(Rows.date(row, "start_local_date"), request.endDate);
                        values.put("end_local_date", request.endDate);
                    }
                    if (f.contains("priority")) values.put("priority", request.priority);
                    if (f.contains("note")) {
                        values.put("motivation_cipher", crypto.encrypt(request.note));
                        values.put(
                                "encryption_key_ref",
                                request.note == null ? null : crypto.keyRef());
                    }
                    if (f.contains("status")) {
                        values.put("status", request.status);
                        values.put(
                                "completed_at",
                                "COMPLETED".equals(request.status) ? Instant.now() : null);
                    }
                    long version =
                            sql.update(
                                    "user_goal", id, Ids.parse(request.expectedRowVersion), values);
                    mutations.upsert(
                            "GOAL",
                            id,
                            version,
                            projection(goalView(sql.owned("user_goal", id, AuthContext.userId()))));
                    return MutationResult.of("GOAL", id, version);
                });
    }

    public MutationResult closeGoal(long id, GoalClose request) {
        return mutations.mutate(
                "GOAL_CLOSE",
                Rows.map("id", id, "request", request),
                true,
                () -> {
                    Map<String, Object> row = sql.owned("user_goal", id, AuthContext.userId());
                    LocalDate today = LocalDate.now(zone(Rows.text(profileRow(), "time_zone")));
                    if (request.closedOn().isBefore(Rows.date(row, "start_local_date"))
                            || request.closedOn().isAfter(today)) {
                        throw BusinessException.badRequest("VALIDATION_FAILED", "关闭日期不在有效范围内");
                    }
                    Map<String, Object> values =
                            Rows.map("status", "ARCHIVED", "end_local_date", request.closedOn());
                    if (request.reason() != null) {
                        values.put("motivation_cipher", crypto.encrypt(request.reason()));
                        values.put("encryption_key_ref", crypto.keyRef());
                    }
                    long version =
                            sql.update(
                                    "user_goal",
                                    id,
                                    Ids.parse(request.expectedRowVersion()),
                                    values);
                    mutations.upsert(
                            "GOAL",
                            id,
                            version,
                            projection(goalView(sql.owned("user_goal", id, AuthContext.userId()))));
                    return MutationResult.of("GOAL", id, version);
                });
    }

    public PageResult<AvailabilityRuleView> availability(
            LocalDate effectiveOn, String kind, String cursor, int limit) {
        LocalDate day =
                effectiveOn == null
                        ? LocalDate.now(zone(Rows.text(profileRow(), "time_zone")))
                        : effectiveOn;
        List<Object> args = new ArrayList<>(List.of(day, day));
        String filter =
                " AND valid_from<=? AND (valid_until IS NULL OR valid_until>=?) AND is_enabled=1";
        if (kind != null) {
            filter += " AND rule_kind=?";
            args.add(dbKind(kind));
        }
        return ownedPage(
                "availability_rule",
                filter,
                args,
                "availability:" + day + ":" + kind,
                cursor,
                limit,
                this::ruleView);
    }

    public MutationResult createAvailability(AvailabilityRuleInput request) {
        validateRule(request);
        return mutations.mutate(
                "AVAILABILITY_CREATE",
                request,
                true,
                () -> {
                    Map<String, Object> values = ruleValues(request);
                    values.put("user_id", AuthContext.userId());
                    long id = sql.insert("availability_rule", values);
                    mutations.upsert(
                            "AVAILABILITY_RULE",
                            id,
                            1,
                            projection(
                                    ruleView(
                                            sql.owned(
                                                    "availability_rule",
                                                    id,
                                                    AuthContext.userId()))));
                    return MutationResult.of("AVAILABILITY_RULE", id, 1);
                });
    }

    public MutationResult replaceAvailability(long id, AvailabilityRuleReplace request) {
        validateRule(request.input());
        return mutations.mutate(
                "AVAILABILITY_REPLACE",
                Rows.map("id", id, "request", request),
                true,
                () -> {
                    sql.owned("availability_rule", id, AuthContext.userId());
                    long version =
                            sql.update(
                                    "availability_rule",
                                    id,
                                    Ids.parse(request.expectedRowVersion()),
                                    ruleValues(request.input()));
                    mutations.upsert(
                            "AVAILABILITY_RULE",
                            id,
                            version,
                            projection(
                                    ruleView(
                                            sql.owned(
                                                    "availability_rule",
                                                    id,
                                                    AuthContext.userId()))));
                    return MutationResult.of("AVAILABILITY_RULE", id, version);
                });
    }

    public MutationResult deleteAvailability(long id, VersionBody request) {
        return mutations.mutate(
                "AVAILABILITY_DELETE",
                Rows.map("id", id, "request", request),
                true,
                () -> {
                    sql.owned("availability_rule", id, AuthContext.userId());
                    long version =
                            sql.update(
                                    "availability_rule",
                                    id,
                                    Ids.parse(request.expectedRowVersion()),
                                    Rows.map("deleted_at", Instant.now(), "is_enabled", 0));
                    mutations.delete("AVAILABILITY_RULE", id, version);
                    return MutationResult.of("AVAILABILITY_RULE", id, version);
                });
    }

    public ExceptionList exceptions(LocalDate from, LocalDate to) {
        requireRange(from, to, 366);
        List<ExceptionView> items =
                sql
                        .query(
                                "SELECT * FROM availability_exception WHERE user_id=? AND"
                                    + " deleted_at IS NULL AND exception_date BETWEEN ? AND ? ORDER"
                                    + " BY exception_date,id",
                                AuthContext.userId(),
                                from,
                                to)
                        .stream()
                        .map(this::exceptionView)
                        .toList();
        return new ExceptionList(items, from, to);
    }

    public MutationResult putException(LocalDate date, ExceptionPut request) {
        validateException(date, request);
        return mutations.mutate(
                "EXCEPTION_PUT",
                Rows.map("date", date, "request", request),
                true,
                () -> {
                    Map<String, Object> row =
                            sql.find(
                                            "SELECT * FROM availability_exception WHERE user_id=? "
                                                    + "AND exception_date=? FOR UPDATE",
                                            AuthContext.userId(),
                                            date)
                                    .orElse(null);
                    long expected = zeroVersion(request.expectedRowVersion());
                    boolean previouslyDeleted = row != null && row.get("deleted_at") != null;
                    if ((row == null && expected != 0)
                            || (row != null
                                    && !previouslyDeleted
                                    && Rows.number(row, "row_version") != expected)
                            || (previouslyDeleted
                                    && expected != 0
                                    && expected != Rows.number(row, "row_version"))) {
                        throw BusinessException.conflict("ROW_VERSION_CONFLICT", "该日期的时间例外已更新");
                    }
                    List<Map<String, Object>> slots = new ArrayList<>();
                    for (ExceptionSlot slot : request.slots()) {
                        slots.add(
                                Rows.map(
                                        "kind",
                                        slot.kind(),
                                        "startTime",
                                        slot.startTime(),
                                        "endTime",
                                        slot.endTime(),
                                        "endDayOffset",
                                        slot.endDayOffset() == null ? 0 : slot.endDayOffset(),
                                        "label",
                                        slot.label(),
                                        "startOffsetMinutes",
                                        slot.startOffsetMinutes(),
                                        "endOffsetMinutes",
                                        slot.endOffsetMinutes()));
                    }
                    Map<String, Object> values =
                            Rows.map(
                                    "time_zone",
                                    request.timeZone(),
                                    "mode",
                                    request.mode(),
                                    "slots_json",
                                    slots,
                                    "slots_schema_version",
                                    1,
                                    "label",
                                    "日期例外",
                                    "reason_cipher",
                                    crypto.encrypt(request.reason()),
                                    "encryption_key_ref",
                                    request.reason() == null ? null : crypto.keyRef(),
                                    "is_enabled",
                                    1,
                                    "source_type",
                                    "MANUAL",
                                    "deleted_at",
                                    null);
                    long id, version;
                    if (row == null) {
                        values.put("user_id", AuthContext.userId());
                        values.put("exception_date", date);
                        id = sql.insert("availability_exception", values);
                        version = 1;
                    } else {
                        id = Rows.number(row, "id");
                        version =
                                sql.update(
                                        "availability_exception",
                                        id,
                                        Rows.number(row, "row_version"),
                                        values);
                    }
                    mutations.upsert(
                            "AVAILABILITY_EXCEPTION",
                            id,
                            version,
                            projection(
                                    exceptionView(
                                            sql.owned(
                                                    "availability_exception",
                                                    id,
                                                    AuthContext.userId()))));
                    return MutationResult.of("AVAILABILITY_EXCEPTION", id, version);
                });
    }

    public MutationResult deleteException(LocalDate date, VersionBody request) {
        return mutations.mutate(
                "EXCEPTION_DELETE",
                Rows.map("date", date, "request", request),
                true,
                () -> {
                    Map<String, Object> row =
                            sql.one(
                                    "SELECT * FROM availability_exception WHERE user_id=? AND"
                                            + " exception_date=? AND deleted_at IS NULL FOR UPDATE",
                                    AuthContext.userId(),
                                    date);
                    long id = Rows.number(row, "id");
                    long version =
                            sql.update(
                                    "availability_exception",
                                    id,
                                    Ids.parse(request.expectedRowVersion()),
                                    Rows.map("deleted_at", Instant.now(), "is_enabled", 0));
                    mutations.delete("AVAILABILITY_EXCEPTION", id, version);
                    return MutationResult.of("AVAILABILITY_EXCEPTION", id, version);
                });
    }

    public String timeZone() {
        return Rows.text(profileRow(), "time_zone");
    }

    public ProfileView profileView(Map<String, Object> row) {
        return new ProfileView(
                Rows.number(row, "id"),
                Rows.number(row, "row_version"),
                Rows.text(row, "display_name"),
                nullableLong(row, "avatar_file_id"),
                crypto.decrypt(row.get("bio_cipher")),
                Rows.text(row, "time_zone"),
                Rows.integer(row, "week_start_day"),
                Rows.text(row, "onboarding_step"),
                Rows.instant(row, "updated_at"));
    }

    public PreferenceView preferenceView(Map<String, Object> row) {
        return new PreferenceView(
                Rows.number(row, "id"),
                Rows.number(row, "row_version"),
                Rows.text(row, "theme_mode"),
                Rows.bool(row, "reduced_motion"),
                new BigDecimal(row.get("font_scale").toString()),
                textList(Rows.json(row, "preferred_activity_types_json")),
                textList(Rows.json(row, "excluded_activity_types_json")),
                Rows.integer(row, "daily_task_limit"),
                Rows.integer(row, "default_task_minutes"),
                row.get("preferred_session_minutes") == null
                        ? null
                        : Rows.integer(row, "preferred_session_minutes"),
                timeText(row.get("preferred_wake_time")),
                timeText(row.get("preferred_bed_time")),
                Rows.text(row, "plan_tone"),
                Rows.bool(row, "quiet_hours_enabled"),
                timeText(row.get("quiet_start")),
                timeText(row.get("quiet_end")));
    }

    public UserGoalView goalView(Map<String, Object> row) {
        JsonNode parameters = Rows.json(row, "parameters_json");
        String code =
                Rows.text(
                        sql.one(
                                "SELECT code FROM goal_definition WHERE id=?",
                                Rows.number(row, "goal_definition_id")),
                        "code");
        return new UserGoalView(
                Rows.number(row, "id"),
                Rows.number(row, "row_version"),
                Rows.number(row, "goal_definition_id"),
                "CUSTOM".equals(code),
                Rows.text(row, "display_name"),
                decimal(parameters.get("targetValue")),
                nullableText(parameters.get("unit")),
                Rows.date(row, "start_local_date"),
                Rows.date(row, "end_local_date"),
                Rows.text(row, "goal_time_zone"),
                Rows.integer(row, "priority"),
                crypto.decrypt(row.get("motivation_cipher")),
                Rows.text(row, "status"),
                Rows.instant(row, "completed_at"));
    }

    public AvailabilityRuleView ruleView(Map<String, Object> row) {
        return new AvailabilityRuleView(
                Rows.number(row, "id"),
                Rows.number(row, "row_version"),
                Rows.integer(row, "weekday"),
                apiKind(Rows.text(row, "rule_kind")),
                minuteText(Rows.integer(row, "start_minute")),
                minuteText(Rows.integer(row, "end_minute")),
                Rows.integer(row, "end_day_offset"),
                Rows.text(row, "time_zone"),
                Rows.date(row, "valid_from"),
                Rows.date(row, "valid_until"),
                Rows.text(row, "label"));
    }

    public ExceptionView exceptionView(Map<String, Object> row) {
        List<ExceptionSlot> slots =
                mapper.convertValue(
                        Rows.json(row, "slots_json"), new TypeReference<List<ExceptionSlot>>() {});
        return new ExceptionView(
                Rows.number(row, "id"),
                Rows.number(row, "row_version"),
                Rows.date(row, "exception_date"),
                Rows.text(row, "time_zone"),
                Rows.text(row, "mode"),
                slots,
                crypto.decrypt(row.get("reason_cipher")));
    }

    private ConsentView consentView(Map<String, Object> row) {
        String status = Rows.text(row, "status");
        return new ConsentView(
                Rows.number(row, "id"),
                Rows.number(row, "row_version"),
                Rows.text(row, "purpose_code"),
                "REVOKED".equals(status) ? "WITHDRAWN" : status,
                Rows.text(row, "policy_version"),
                Rows.instant(row, "REVOKED".equals(status) ? "revoked_at" : "granted_at"));
    }

    private GoalDefinitionView definitionView(Map<String, Object> row) {
        JsonNode schema = Rows.json(row, "parameter_schema_json");
        List<String> units = textList(schema.path("properties").path("unit").path("enum"));
        units.removeIf(Objects::isNull);
        JsonNode value = schema.path("properties").path("targetValue");
        return new GoalDefinitionView(
                Rows.number(row, "id"),
                Rows.text(row, "code"),
                Rows.text(row, "name"),
                Rows.text(row, "goal_category"),
                Rows.text(row, "description"),
                units,
                decimal(value.get("minimum")),
                decimal(value.get("maximum")),
                null,
                true);
    }

    private Map<String, Object> parameters(
            Map<String, Object> definition, BigDecimal target, String unit) {
        if ((target == null) != (unit == null))
            throw BusinessException.badRequest("VALIDATION_FAILED", "目标数值和单位需同时填写或同时留空");
        Map<String, Object> value = Rows.map("targetValue", target, "unit", unit);
        JsonNode schema = Rows.json(definition, "parameter_schema_json");
        try {
            if (sql.count(
                            "SELECT JSON_SCHEMA_VALID(?,?)",
                            schema.toString(),
                            mapper.writeValueAsString(value))
                    != 1) {
                throw BusinessException.badRequest("GOAL_PARAMETERS_INVALID", "目标单位或数值不在该目标允许范围");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化目标参数");
        }
        return value;
    }

    private Map<String, Object> ruleValues(AvailabilityRuleInput request) {
        return Rows.map(
                "label",
                request.label() == null
                        ? switch (request.kind()) {
                            case "REST" -> "休息";
                            case "BUSY" -> "忙碌";
                            default -> "空闲";
                        }
                        : request.label(),
                "rule_kind",
                dbKind(request.kind()),
                "weekday",
                request.weekday(),
                "start_minute",
                LocalTime.parse(request.startTime()).toSecondOfDay() / 60,
                "end_minute",
                LocalTime.parse(request.endTime()).toSecondOfDay() / 60,
                "end_day_offset",
                request.endDayOffset() == null ? 0 : request.endDayOffset(),
                "time_zone",
                request.timeZone(),
                "valid_from",
                request.effectiveFrom(),
                "valid_until",
                request.effectiveTo(),
                "priority",
                0,
                "is_enabled",
                1,
                "source_type",
                "MANUAL");
    }

    private void validateRule(AvailabilityRuleInput request) {
        checkDates(request.effectiveFrom(), request.effectiveTo());
        int offset = request.endDayOffset() == null ? 0 : request.endDayOffset();
        duration(request.startTime(), request.endTime(), offset);
        ZoneId zone = zone(request.timeZone());
        LocalDate finish =
                request.effectiveTo() == null
                        ? request.effectiveFrom().plusDays(366)
                        : request.effectiveTo();
        if (finish.isAfter(request.effectiveFrom().plusDays(366)))
            finish = request.effectiveFrom().plusDays(366);
        for (LocalDate day = request.effectiveFrom(); !day.isAfter(finish); day = day.plusDays(1)) {
            if (day.getDayOfWeek().getValue() == request.weekday()) {
                resolve(day, request.startTime(), zone, null);
                resolve(day.plusDays(offset), request.endTime(), zone, null);
            }
        }
    }

    private void validateException(LocalDate date, ExceptionPut request) {
        ZoneId zone = zone(request.timeZone());
        for (ExceptionSlot slot : request.slots()) {
            int offset = slot.endDayOffset() == null ? 0 : slot.endDayOffset();
            duration(slot.startTime(), slot.endTime(), offset);
            Instant start = resolve(date, slot.startTime(), zone, slot.startOffsetMinutes());
            Instant end =
                    resolve(date.plusDays(offset), slot.endTime(), zone, slot.endOffsetMinutes());
            if (!end.isAfter(start) || Duration.between(start, end).toMinutes() > 1500) {
                throw BusinessException.badRequest("VALIDATION_FAILED", "跨时区时段实际长度无效");
            }
        }
    }

    private Instant resolve(LocalDate date, String time, ZoneId zone, Integer offsetMinutes) {
        LocalDateTime local = LocalDateTime.of(date, LocalTime.parse(time));
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
        if (offsets.isEmpty())
            throw BusinessException.badRequest("LOCAL_TIME_INVALID", "该时区不存在这个本地时间");
        if (offsets.size() > 1 && offsetMinutes == null) {
            throw BusinessException.badRequest("LOCAL_TIME_AMBIGUOUS", "本地时间重复，请提供明确UTC偏移");
        }
        ZoneOffset selected =
                offsetMinutes == null
                        ? offsets.getFirst()
                        : ZoneOffset.ofTotalSeconds(offsetMinutes * 60);
        if (!offsets.contains(selected))
            throw BusinessException.badRequest("LOCAL_TIME_INVALID", "UTC偏移与时区不一致");
        return local.toInstant(selected);
    }

    private void duration(String start, String end, int offset) {
        int minutes =
                LocalTime.parse(end).toSecondOfDay() / 60
                        + offset * 1440
                        - LocalTime.parse(start).toSecondOfDay() / 60;
        if (minutes < 1 || minutes > 1440)
            throw BusinessException.badRequest("VALIDATION_FAILED", "时段长度必须为1至1440分钟");
    }

    private <T> PageResult<T> ownedPage(
            String table,
            String filter,
            List<Object> filters,
            String scope,
            String cursor,
            int limit,
            Function<Map<String, Object>, T> view) {
        int size = Math.max(1, Math.min(limit, 100));
        Map<String, Object> state = cursors.decode(cursor, scope);
        long after = state.isEmpty() ? 0 : Long.parseLong(String.valueOf(state.get("after")));
        List<Object> args = new ArrayList<>();
        args.add(AuthContext.userId());
        args.add(after);
        args.addAll(filters);
        args.add(size + 1);
        List<Map<String, Object>> rows =
                sql.query(
                        "SELECT * FROM "
                                + table
                                + " WHERE user_id=? AND id>? "
                                + "AND deleted_at IS NULL"
                                + filter
                                + " ORDER BY id LIMIT ?",
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

    private Map<String, Object> profileRow() {
        return sql.one(
                "SELECT * FROM user_profile WHERE user_id=? AND deleted_at IS NULL",
                AuthContext.userId());
    }

    private Map<String, Object> preferenceRow() {
        return sql.one(
                "SELECT * FROM user_preference WHERE user_id=? AND deleted_at IS NULL",
                AuthContext.userId());
    }

    private Map<String, Object> projection(Object value) {
        return mapper.convertValue(value, new TypeReference<Map<String, Object>>() {});
    }

    private Object patchCanonical(Object request, Set<String> fields) {
        return Rows.map("fields", new TreeSet<>(fields), "values", request);
    }

    private void requirePatch(Set<String> fields, Set<String> nullable, Object request) {
        if (fields.size() < 2 || !fields.contains("expectedRowVersion")) {
            throw BusinessException.badRequest("VALIDATION_FAILED", "请提供版本和至少一个修改字段");
        }
        for (String field : fields) {
            try {
                if (request.getClass().getField(field).get(request) == null
                        && !nullable.contains(field)) {
                    throw BusinessException.badRequest("VALIDATION_FAILED", field + "不允许为空");
                }
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("DTO字段不匹配");
            }
        }
    }

    private void unique(List<String> values) {
        if (values.contains(null) || values.size() != new HashSet<>(values).size()) {
            throw BusinessException.badRequest("VALIDATION_FAILED", "偏好列表不能包含空项或重复值");
        }
    }

    private long zeroVersion(String value) {
        return "0".equals(value) ? 0 : Ids.parse(value);
    }

    private String dbKind(String kind) {
        if (!Set.of("BUSY", "AVAILABLE", "REST").contains(kind))
            throw BusinessException.badRequest("UNSUPPORTED_TYPE", "不支持的时段类型");
        return "REST".equals(kind) ? "RESERVED_REST" : kind;
    }

    private String apiKind(String kind) {
        return "RESERVED_REST".equals(kind) ? "REST" : kind;
    }

    private ZoneId zone(String value) {
        try {
            return ZoneId.of(value);
        } catch (DateTimeException exception) {
            throw BusinessException.badRequest("TIME_ZONE_INVALID", "请使用有效IANA时区");
        }
    }

    private void checkDates(LocalDate from, LocalDate to) {
        if (to != null && to.isBefore(from))
            throw BusinessException.badRequest("VALIDATION_FAILED", "结束日期不能早于开始日期");
    }

    private void requireRange(LocalDate from, LocalDate to, int maxDays) {
        if (from == null
                || to == null
                || to.isBefore(from)
                || ChronoUnit.DAYS.between(from, to) >= maxDays) {
            throw BusinessException.badRequest("VALIDATION_FAILED", "日期范围无效或过大");
        }
    }

    private LocalTime time(String value) {
        return value == null ? null : LocalTime.parse(value);
    }

    private String timeText(Object value) {
        if (value == null) return null;
        return value.toString().substring(0, 5);
    }

    private String minuteText(int value) {
        return String.format(Locale.ROOT, "%02d:%02d", value / 60, value % 60);
    }

    private Long nullableLong(Map<String, Object> row, String field) {
        return row.get(field) == null ? null : Rows.number(row, field);
    }

    private BigDecimal decimal(JsonNode value) {
        return value == null || value.isNull() ? null : value.decimalValue();
    }

    private String nullableText(JsonNode value) {
        return value == null || value.isNull() ? null : value.asText();
    }

    private List<String> textList(JsonNode value) {
        List<String> list = new ArrayList<>();
        if (value != null && value.isArray())
            value.forEach(item -> list.add(item.isNull() ? null : item.asText()));
        return list;
    }
}
