package com.yqsoft.service.reminder;

import com.fasterxml.jackson.databind.JsonNode;
import com.yqsoft.config.AppProperties;
import com.yqsoft.dao.SqlStore;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.persistence.*;
import com.yqsoft.resource.common.*;
import com.yqsoft.resource.reminder.ReminderDtos;
import com.yqsoft.security.AuthContext;
import com.yqsoft.service.common.CryptoService;
import com.yqsoft.service.common.CursorService;
import com.yqsoft.service.statistics.StatisticsService;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.sql.Time;
import java.time.*;
import java.util.*;

@Service
public class ReminderService implements SyncProjectionProvider {
    private final SqlStore sql;
    private final MutationExecutor mutations;
    private final WorkerGuard guard;
    private final CryptoService crypto;
    private final CursorService cursors;
    private final AppProperties properties;

    public ReminderService(
            SqlStore sql,
            MutationExecutor mutations,
            WorkerGuard guard,
            CryptoService crypto,
            CursorService cursors,
            AppProperties properties) {
        this.sql = sql;
        this.mutations = mutations;
        this.guard = guard;
        this.crypto = crypto;
        this.cursors = cursors;
        this.properties = properties;
    }

    @Override
    public Set<String> entityTypes() {
        return Set.of("REMINDER_RULE", "NOTIFICATION");
    }

    @Override
    public Map<String, Object> project(String type, long id) {
        return switch (type) {
            case "REMINDER_RULE" -> ruleView(sql.owned("reminder_rule", id, AuthContext.userId()));
            case "NOTIFICATION" ->
                    notificationView(sql.owned("notification_inbox", id, AuthContext.userId()));
            default -> throw invalid("不支持的同步类型");
        };
    }

    public PageResult<Map<String, Object>> rules(String cursor, int limit) {
        return page("reminder_rule", "reminder-rules", "", List.of(), cursor, limit, true);
    }

    public MutationResult create(ReminderDtos.Create request) {
        return mutations.mutate(
                "REMINDER_RULE_CREATE",
                request,
                true,
                () -> {
                    validateRule(request);
                    long id = sql.insert("reminder_rule", ruleValues(request));
                    syncRule(id, 1);
                    mutations.outbox(
                            "REMINDER_RULE_CHANGED",
                            "REMINDER_RULE",
                            id,
                            1L,
                            Rows.map("ruleId", id));
                    return MutationResult.of("REMINDER_RULE", id, 1);
                });
    }

    public MutationResult update(long id, ReminderDtos.Patch request) {
        Map<String, Object> canonical =
                Rows.map("id", id, "expectedRowVersion", request.expectedRowVersion());
        for (String key :
                List.of(
                        "enabled",
                        "channel",
                        "offsetMinutes",
                        "localTime",
                        "timeZone",
                        "quietPolicy",
                        "installationId")) {
            if (request.has(key))
                canonical.put(
                        key,
                        switch (key) {
                            case "enabled" -> request.enabled();
                            case "channel" -> request.channel();
                            case "offsetMinutes" -> request.offsetMinutes();
                            case "localTime" -> request.localTime();
                            case "timeZone" -> request.timeZone();
                            case "quietPolicy" -> request.quietPolicy();
                            default -> request.installationId();
                        });
        }
        return mutations.mutate(
                "REMINDER_RULE_UPDATE",
                canonical,
                true,
                () -> {
                    if (request.empty()) throw invalid("至少修改一个字段");
                    Map<String, Object> old =
                            sql.lockOwned("reminder_rule", id, AuthContext.userId());
                    expected(old, request.expectedRowVersion());
                    ReminderDtos.Create combined =
                            new ReminderDtos.Create(
                                    old.get("task_id") == null ? null : Rows.text(old, "task_id"),
                                    "TASK_START".equals(old.get("rule_type"))
                                            ? "TASK"
                                            : "DAILY_RECORD",
                                    request.has("enabled")
                                            ? request.enabled()
                                            : Rows.bool(old, "is_enabled"),
                                    request.has("channel")
                                            ? request.channel()
                                            : Rows.text(old, "channel"),
                                    request.has("offsetMinutes")
                                            ? request.offsetMinutes()
                                            : old.get("offset_minutes") == null
                                                    ? null
                                                    : Rows.integer(old, "offset_minutes"),
                                    request.has("localTime")
                                            ? request.localTime()
                                            : localTime(old.get("local_fire_time")),
                                    request.has("timeZone")
                                            ? request.timeZone()
                                            : Rows.text(old, "time_zone"),
                                    request.has("quietPolicy")
                                            ? request.quietPolicy()
                                            : Rows.text(old, "quiet_policy"),
                                    request.has("installationId")
                                            ? request.installationId()
                                            : old.get("target_installation_id") == null
                                                    ? null
                                                    : Rows.text(old, "target_installation_id"));
                    validateRule(combined);
                    Map<String, Object> values = ruleValues(combined);
                    values.remove("user_id");
                    values.remove("rule_key");
                    values.remove("valid_from");
                    long version =
                            sql.update(
                                    "reminder_rule", id, Rows.number(old, "row_version"), values);
                    cancelDeliveries(AuthContext.userId(), id, null);
                    syncRule(id, version);
                    mutations.outbox(
                            "REMINDER_RULE_CHANGED",
                            "REMINDER_RULE",
                            id,
                            version,
                            Rows.map("ruleId", id));
                    return MutationResult.of("REMINDER_RULE", id, version);
                });
    }

    public MutationResult deleteRule(long id, ReminderDtos.VersionBody request) {
        return mutations.mutate(
                "REMINDER_RULE_DELETE",
                Rows.map("id", id, "request", request),
                true,
                () -> {
                    Map<String, Object> old =
                            sql.lockOwned("reminder_rule", id, AuthContext.userId());
                    expected(old, request.expectedRowVersion());
                    cancelDeliveries(AuthContext.userId(), id, null);
                    long version =
                            sql.update(
                                    "reminder_rule",
                                    id,
                                    Rows.number(old, "row_version"),
                                    Rows.map("is_enabled", false, "deleted_at", Instant.now()));
                    mutations.delete("REMINDER_RULE", id, version);
                    mutations.outbox(
                            "REMINDER_CANCEL",
                            "REMINDER_RULE",
                            id,
                            version,
                            Rows.map("ruleId", id));
                    return MutationResult.of("REMINDER_RULE", id, version);
                });
    }

    public PageResult<Map<String, Object>> deliveries(
            long installationId, String state, Instant from, Instant to, String cursor, int limit) {
        requireSessionInstallation(installationId);
        limit(limit);
        if ((from == null) != (to == null)) throw invalid("提醒起止时间必须同时出现");
        String scope =
                "reminder-deliveries:" + installationId + ":" + state + ":" + from + ":" + to;
        Map<String, Object> position = cursors.decode(cursor, scope);
        Instant first =
                from == null
                        ? position.containsKey("from")
                                ? Instant.parse(position.get("from").toString())
                                : Instant.now().minus(Duration.ofDays(7))
                        : from;
        Instant last =
                to == null
                        ? position.containsKey("to")
                                ? Instant.parse(position.get("to").toString())
                                : Instant.now().plus(Duration.ofDays(7))
                        : to;
        if (!last.isAfter(first) || Duration.between(first, last).toDays() > 31)
            throw invalid("提醒日期范围无效");
        long after = Rows.number(position, "id");
        List<Object> args =
                new ArrayList<>(List.of(AuthContext.userId(), installationId, first, last, after));
        String where =
                "user_id=? AND installation_id=? AND fire_at>=? AND fire_at<? AND id>? AND"
                        + " deleted_at IS NULL";
        if (state != null) {
            if (!Set.of(
                            "PENDING",
                            "REGISTERED",
                            "DELIVERED",
                            "UNKNOWN",
                            "FAILED",
                            "CANCEL_REQUESTED",
                            "CANCELLED",
                            "EXPIRED")
                    .contains(state)) throw invalid("提醒状态无效");
            where += " AND status=?";
            args.add(state);
        }
        args.add(limit + 1);
        List<Map<String, Object>> rows =
                sql.query(
                        "SELECT * FROM reminder_delivery WHERE " + where + " ORDER BY id LIMIT ?",
                        args.toArray());
        boolean more = rows.size() > limit;
        if (more) rows = rows.subList(0, limit);
        return PageResult.of(
                rows.stream().map(this::deliveryView).toList(),
                more
                        ? cursors.encode(
                                scope,
                                Rows.map(
                                        "id",
                                        Rows.number(rows.getLast(), "id"),
                                        "from",
                                        first.toString(),
                                        "to",
                                        last.toString()))
                        : null,
                more);
    }

    public MutationResult receipt(long id, ReminderDtos.Receipt request) {
        return mutations.mutate(
                "REMINDER_RECEIPT",
                Rows.map("id", id, "request", request),
                true,
                () -> {
                    long installationId = Ids.parse(request.installationId());
                    requireSessionInstallation(installationId);
                    Map<String, Object> delivery =
                            sql.lockOwned("reminder_delivery", id, AuthContext.userId());
                    expected(delivery, request.expectedRowVersion());
                    if (Rows.number(delivery, "installation_id") != installationId)
                        throw BusinessException.notFound();
                    String event = request.event();
                    if (request.observedAt().isAfter(Instant.now().plusSeconds(300)))
                        throw invalid("回执时间明显在未来");
                    if (!Set.of("CANCELLED", "UNKNOWN").contains(event))
                        assertCurrentDelivery(delivery);
                    Map<String, Object> values = Rows.map();
                    switch (event) {
                        case "REGISTERED" -> {
                            if (!localEnabled() && "LOCAL_SYSTEM".equals(delivery.get("channel")))
                                throw BusinessException.unavailable(
                                        "FEATURE_NOT_ENABLED", "系统提醒尚未通过目标设备验证");
                            values.putAll(
                                    Rows.map(
                                            "status",
                                            "REGISTERED",
                                            "registered_at",
                                            request.observedAt(),
                                            "system_handle_cipher",
                                            crypto.encrypt(request.osRequestId()),
                                            "encryption_key_ref",
                                            request.osRequestId() == null
                                                    ? null
                                                    : crypto.keyRef()));
                        }
                        case "DELIVERED" ->
                                throw BusinessException.forbidden(
                                        "DELIVERY_OBSERVATION_UNVERIFIED",
                                        "尚未接入可验证的系统投递回调，请记录UNKNOWN，登记不代表已展示");
                        case "REGISTRATION_FAILED" ->
                                values.putAll(
                                        Rows.map(
                                                "status",
                                                "FAILED",
                                                "last_error_code",
                                                safeCode(request.errorCode())));
                        case "CANCELLED" ->
                                values.putAll(
                                        Rows.map(
                                                "status",
                                                "CANCELLED",
                                                "cancelled_at",
                                                request.observedAt()));
                        default -> values.put("status", "UNKNOWN");
                    }
                    long version =
                            sql.update(
                                    "reminder_delivery",
                                    id,
                                    Rows.number(delivery, "row_version"),
                                    values);
                    if ("CANCELLED".equals(event)) {
                        sql.find(
                                        "SELECT * FROM reminder_rule WHERE id=? AND user_id=? AND"
                                                + " is_enabled=1 AND deleted_at IS NULL",
                                        Rows.number(delivery, "rule_id"),
                                        AuthContext.userId())
                                .ifPresent(
                                        rule ->
                                                registerCandidate(
                                                        AuthContext.userId(),
                                                        mutations.currentEpoch(),
                                                        rule));
                        version =
                                Rows.number(
                                        sql.owned("reminder_delivery", id, AuthContext.userId()),
                                        "row_version");
                    }
                    return MutationResult.of("REMINDER_DELIVERY", id, version);
                });
    }

    public PageResult<Map<String, Object>> notifications(
            Boolean read, String category, String cursor, int size) {
        String where = " AND (expires_at IS NULL OR expires_at>UTC_TIMESTAMP(3))";
        List<Object> args = new ArrayList<>();
        if (read != null) where += read ? " AND read_at IS NOT NULL" : " AND read_at IS NULL";
        if (category != null) {
            if (!Set.of("PLAN", "SYNC", "PRIVACY", "REMINDER", "REVIEW", "SYSTEM")
                    .contains(category)) throw invalid("通知分类无效");
            where += " AND category=?";
            args.add(category);
        }
        return page(
                "notification_inbox",
                "notifications:" + read + ":" + category,
                where,
                args,
                cursor,
                size,
                false);
    }

    public MutationResult read(ReminderDtos.ReadState request) {
        return mutations.mutate(
                "NOTIFICATIONS_READ_STATE",
                request,
                true,
                () -> {
                    if (request.items().stream()
                                    .map(ReminderDtos.NotificationVersion::id)
                                    .distinct()
                                    .count()
                            != request.items().size()) throw invalid("通知编号不能重复");
                    List<MutationResult> results = new ArrayList<>();
                    for (ReminderDtos.NotificationVersion item :
                            request.items().stream()
                                    .sorted(Comparator.comparingLong(i -> Ids.parse(i.id())))
                                    .toList()) {
                        long id = Ids.parse(item.id());
                        Map<String, Object> row =
                                sql.lockOwned("notification_inbox", id, AuthContext.userId());
                        expected(row, item.expectedRowVersion());
                        long version =
                                sql.update(
                                        "notification_inbox",
                                        id,
                                        Rows.number(row, "row_version"),
                                        Rows.map("read_at", request.read() ? Instant.now() : null));
                        mutations.upsert(
                                "NOTIFICATION",
                                id,
                                version,
                                notificationView(
                                        sql.owned("notification_inbox", id, AuthContext.userId())));
                        results.add(
                                new MutationResult(
                                        "NOTIFICATION",
                                        Long.toString(id),
                                        Long.toString(version),
                                        Long.toString(mutations.currentSeq()),
                                        RequestContext.idempotencyKey(),
                                        false,
                                        Map.of()));
                    }
                    return results.getFirst().withExtra(Rows.map("results", results));
                });
    }

    public MutationResult deleteNotification(long id, ReminderDtos.VersionBody request) {
        return mutations.mutate(
                "NOTIFICATION_DELETE",
                Rows.map("id", id, "request", request),
                true,
                () -> {
                    Map<String, Object> row =
                            sql.lockOwned("notification_inbox", id, AuthContext.userId());
                    expected(row, request.expectedRowVersion());
                    long version =
                            sql.update(
                                    "notification_inbox",
                                    id,
                                    Rows.number(row, "row_version"),
                                    Rows.map("deleted_at", Instant.now()));
                    mutations.delete("NOTIFICATION", id, version);
                    return MutationResult.of("NOTIFICATION", id, version);
                });
    }

    public void handleEvent(Map<String, Object> event) {
        long user = Rows.number(event, "user_id");
        long epoch = Rows.number(event, "captured_dataset_epoch");
        guard.withUser(
                user,
                epoch,
                null,
                null,
                () -> {
                    Map<String, Object> durable =
                            sql.one(
                                    "SELECT * FROM outbox_event WHERE id=? FOR UPDATE",
                                    Rows.number(event, "id"));
                    if (Set.of("DONE", "CANCELLED").contains(Rows.text(durable, "status")))
                        return null;
                    String type = Rows.text(event, "event_type");
                    JsonNode payload = Rows.json(event, "payload_json");
                    Long ruleId =
                            payload != null && payload.hasNonNull("ruleId")
                                    ? Ids.parse(payload.path("ruleId").asText())
                                    : null;
                    Long taskId =
                            payload != null && payload.hasNonNull("taskId")
                                    ? Ids.parse(payload.path("taskId").asText())
                                    : null;
                    if ("REMINDER_CANCEL".equals(type)
                            && ruleId == null
                            && "REMINDER_RULE".equals(event.get("aggregate_type")))
                        ruleId = Rows.number(event, "aggregate_id");
                    if ("REMINDER_CANCEL".equals(type)
                            && taskId == null
                            && "TASK".equals(event.get("aggregate_type")))
                        taskId = Rows.number(event, "aggregate_id");
                    if ("CONSENT_WITHDRAWN".equals(type)
                            && payload != null
                            && !"NOTIFICATION"
                                    .equals(
                                            payload.path("purpose")
                                                    .asText(
                                                            payload.path("purposeCode")
                                                                    .asText()))) {
                        finishEvent(Rows.number(event, "id"));
                        return null;
                    }
                    if ("INSTALLATION_REVOKED".equals(type)) {
                        long installationId =
                                payload != null && payload.hasNonNull("installationId")
                                        ? Ids.parse(payload.path("installationId").asText())
                                        : Rows.number(event, "aggregate_id");
                        sql.execute(
                                "UPDATE reminder_delivery SET status=CASE WHEN registered_at IS"
                                    + " NULL THEN 'CANCELLED' ELSE 'CANCEL_REQUESTED'"
                                    + " END,updated_at=?,row_version=row_version+1 WHERE user_id=?"
                                    + " AND installation_id=? AND status IN"
                                    + " ('PENDING','REGISTERED','UNKNOWN')",
                                Rows.utc(Instant.now()),
                                user,
                                installationId);
                    } else cancelDeliveries(user, ruleId, taskId);
                    if (!"REMINDER_CANCEL".equals(type)
                            && !"CONSENT_WITHDRAWN".equals(type)
                            && !"INSTALLATION_REVOKED".equals(type)) {
                        List<Map<String, Object>> rules =
                                ruleId == null
                                        ? sql.query(
                                                "SELECT * FROM reminder_rule WHERE user_id=? AND"
                                                        + " is_enabled=1 AND deleted_at IS NULL",
                                                user)
                                        : sql.query(
                                                "SELECT * FROM reminder_rule WHERE id=? AND"
                                                    + " user_id=? AND is_enabled=1 AND deleted_at"
                                                    + " IS NULL",
                                                ruleId,
                                                user);
                        for (Map<String, Object> rule : rules) registerCandidate(user, epoch, rule);
                    }
                    finishEvent(Rows.number(event, "id"));
                    return null;
                });
    }

    public void refreshDaily() {
        long after = 0;
        while (true) {
            List<Map<String, Object>> rules =
                    sql.query(
                            "SELECT r.*,c.dataset_epoch FROM reminder_rule r JOIN user_sync_clock c"
                                    + " ON c.user_id=r.user_id WHERE r.id>? AND"
                                    + " r.rule_type='RECORD_CHECKIN' AND r.is_enabled=1 AND"
                                    + " r.deleted_at IS NULL ORDER BY r.id LIMIT 500",
                            after);
            if (rules.isEmpty()) return;
            for (Map<String, Object> rule : rules) {
                try {
                    guard.withUser(
                            Rows.number(rule, "user_id"),
                            Rows.number(rule, "dataset_epoch"),
                            null,
                            null,
                            () -> {
                                registerCandidate(
                                        Rows.number(rule, "user_id"),
                                        Rows.number(rule, "dataset_epoch"),
                                        rule);
                                return null;
                            });
                } catch (BusinessException ignored) {
                    /* A revoked or fenced account must not be re-enrolled. */
                }
            }
            after = Rows.number(rules.getLast(), "id");
            if (rules.size() < 500) return;
        }
    }

    private void registerCandidate(long user, long epoch, Map<String, Object> rule) {
        if (!Rows.bool(rule, "is_enabled") || rule.get("deleted_at") != null) return;
        Optional<Map<String, Object>> consent =
                sql.find(
                        "SELECT * FROM user_consent WHERE user_id=? AND purpose_code='NOTIFICATION'"
                                + " AND status='GRANTED' AND deleted_at IS NULL",
                        user);
        if (consent.isEmpty()
                || (Rows.instant(consent.get(), "expires_at") != null
                        && !Rows.instant(consent.get(), "expires_at").isAfter(Instant.now())))
            return;
        if ("LOCAL_SYSTEM".equals(rule.get("channel")) && !localEnabled()) return;
        Long installationId = nullableId(rule, "target_installation_id");
        Optional<Map<String, Object>> installation =
                installationId == null
                        ? sql.find(
                                "SELECT * FROM client_installation WHERE user_id=? AND"
                                    + " installation_kind='PHONE' AND revoked_at IS NULL AND"
                                    + " deleted_at IS NULL ORDER BY last_seen_at DESC,id DESC LIMIT"
                                    + " 1",
                                user)
                        : sql.find(
                                "SELECT * FROM client_installation WHERE id=? AND user_id=? AND"
                                        + " revoked_at IS NULL AND deleted_at IS NULL",
                                installationId,
                                user);
        if (installation.isEmpty()) return;
        installationId = Rows.number(installation.get(), "id");
        if ("LOCAL_SYSTEM".equals(rule.get("channel"))) {
            JsonNode caps = Rows.json(installation.get(), "capabilities_json");
            if (!"PHONE".equals(installation.get().get("installation_kind"))
                    || !"GRANTED".equals(installation.get().get("notification_permission"))
                    || caps == null
                    || !"SUPPORTED".equals(caps.path("localReminder").asText())) return;
        }
        Long taskId = nullableId(rule, "task_id");
        Long taskVersion = null;
        Instant fire;
        if (taskId != null) {
            Optional<Map<String, Object>> task =
                    sql.find(
                            "SELECT * FROM task WHERE id=? AND user_id=? AND status='PENDING' AND"
                                    + " deleted_at IS NULL",
                            taskId,
                            user);
            if (task.isEmpty()) return;
            taskVersion = Rows.number(task.get(), "row_version");
            fire =
                    Rows.instant(task.get(), "scheduled_start_at")
                            .plusSeconds(Rows.integer(rule, "offset_minutes") * 60L);
        } else {
            ZoneId zone = ZoneId.of(Rows.text(rule, "time_zone"));
            LocalDate day = LocalDate.now(zone);
            LocalTime time = localTime(rule.get("local_fire_time"));
            LocalDateTime proposed = LocalDateTime.of(day, time);
            if (zone.getRules().getValidOffsets(proposed).size() != 1) return;
            fire = proposed.atZone(zone).toInstant();
            if (!fire.isAfter(Instant.now())) {
                proposed = proposed.plusDays(1);
                if (zone.getRules().getValidOffsets(proposed).size() != 1) return;
                fire = proposed.atZone(zone).toInstant();
            }
        }
        if (!fire.isAfter(Instant.now())) return;
        ZoneId zone = ZoneId.of(Rows.text(rule, "time_zone"));
        LocalDate day = fire.atZone(zone).toLocalDate();
        if (day.isBefore(Rows.date(rule, "valid_from"))
                || (Rows.date(rule, "valid_until") != null
                        && day.isAfter(Rows.date(rule, "valid_until")))) return;
        Map<String, Object> preferences =
                sql.one("SELECT * FROM user_preference WHERE user_id=?", user);
        if (Rows.bool(preferences, "quiet_hours_enabled")
                && inQuiet(
                        fire.atZone(zone).toLocalTime(),
                        localTime(preferences.get("quiet_start")),
                        localTime(preferences.get("quiet_end")))) {
            if ("SUPPRESS".equals(rule.get("quiet_policy"))) return;
            LocalDateTime next = LocalDateTime.of(day, localTime(preferences.get("quiet_end")));
            if (!next.atZone(zone).toInstant().isAfter(fire)) next = next.plusDays(1);
            if (zone.getRules().getValidOffsets(next).size() != 1) return;
            fire = next.atZone(zone).toInstant();
            if (taskId != null)
                return; // A next-day task reminder would be stale; suppression is explicit.
        }
        byte[] key =
                crypto.digest(
                        Rows.map(
                                "rule",
                                Rows.number(rule, "id"),
                                "ruleVersion",
                                Rows.number(rule, "row_version"),
                                "task",
                                taskId,
                                "taskVersion",
                                taskVersion,
                                "channel",
                                rule.get("channel"),
                                "installation",
                                installationId,
                                "fireAt",
                                fire,
                                "consentVersion",
                                Rows.number(consent.get(), "consent_version"),
                                "epoch",
                                epoch,
                                "preferenceVersion",
                                Rows.number(preferences, "row_version")));
        Optional<Map<String, Object>> existing =
                sql.find(
                        "SELECT * FROM reminder_delivery WHERE user_id=? AND dedup_key=? FOR"
                                + " UPDATE",
                        user,
                        key);
        if (existing.isPresent()) {
            Map<String, Object> old = existing.get();
            if ("CANCELLED".equals(old.get("status"))
                    && (old.get("registered_at") == null || old.get("cancelled_at") != null)) {
                sql.update(
                        "reminder_delivery",
                        Rows.number(old, "id"),
                        Rows.number(old, "row_version"),
                        Rows.map(
                                "status",
                                "PENDING",
                                "registered_at",
                                null,
                                "cancelled_at",
                                null,
                                "system_handle_cipher",
                                null,
                                "encryption_key_ref",
                                null));
            }
            return;
        }
        sql.insert(
                "reminder_delivery",
                Rows.map(
                        "user_id",
                        user,
                        "rule_id",
                        Rows.number(rule, "id"),
                        "task_id",
                        taskId,
                        "task_row_version",
                        taskVersion,
                        "installation_id",
                        installationId,
                        "channel",
                        rule.get("channel"),
                        "fire_at",
                        fire,
                        "dedup_key",
                        key,
                        "status",
                        "PENDING",
                        "attempt_count",
                        0,
                        "expires_at",
                        fire.plus(Duration.ofMinutes(15)),
                        "captured_dataset_epoch",
                        epoch,
                        "captured_consent_version",
                        Rows.number(consent.get(), "consent_version"),
                        "rule_row_version",
                        Rows.number(rule, "row_version")));
    }

    private void assertCurrentDelivery(Map<String, Object> delivery) {
        if (Set.of("CANCEL_REQUESTED", "CANCELLED", "EXPIRED")
                        .contains(Rows.text(delivery, "status"))
                || Rows.instant(delivery, "expires_at").isBefore(Instant.now()))
            throw BusinessException.conflict("REMINDER_STALE", "提醒已失效，请撤销旧注册");
        Map<String, Object> consent = mutations.assertConsent("NOTIFICATION");
        if (Rows.number(delivery, "captured_dataset_epoch") != mutations.currentEpoch()
                || Rows.number(consent, "consent_version")
                        != Rows.number(delivery, "captured_consent_version"))
            throw BusinessException.conflict("REMINDER_STALE", "提醒所依据的授权已改变");
        Map<String, Object> rule =
                sql.owned("reminder_rule", Rows.number(delivery, "rule_id"), AuthContext.userId());
        if (!Rows.bool(rule, "is_enabled")
                || Rows.number(rule, "row_version") != Rows.number(delivery, "rule_row_version"))
            throw BusinessException.conflict("REMINDER_STALE", "提醒规则已更新");
        if (delivery.get("task_id") != null) {
            Map<String, Object> task =
                    sql.owned("task", Rows.number(delivery, "task_id"), AuthContext.userId());
            if (!"PENDING".equals(task.get("status"))
                    || Rows.number(task, "row_version")
                            != Rows.number(delivery, "task_row_version"))
                throw BusinessException.conflict("REMINDER_STALE", "任务已更新或完成");
        }
        if (!eligible(delivery))
            throw BusinessException.conflict("REMINDER_STALE", "目标安装或系统权限已变化，请撤销旧注册");
    }

    private Map<String, Object> ruleValues(ReminderDtos.Create request) {
        boolean task = "TASK".equals(request.scope());
        return Rows.map(
                "user_id",
                AuthContext.userId(),
                "rule_key",
                UUID.randomUUID().toString(),
                "task_id",
                Ids.optional(request.taskId()),
                "rule_type",
                task ? "TASK_START" : "RECORD_CHECKIN",
                "channel",
                request.channel(),
                "target_installation_id",
                Ids.optional(request.installationId()),
                "trigger_kind",
                task ? "OFFSET" : "DAILY_LOCAL",
                "offset_minutes",
                task ? request.offsetMinutes() == null ? 0 : request.offsetMinutes() : null,
                "local_fire_time",
                task ? null : request.localTime(),
                "time_zone",
                request.timeZone(),
                "is_enabled",
                request.enabled(),
                "respect_quiet_hours",
                true,
                "quiet_policy",
                request.quietPolicy() == null ? "SUPPRESS" : request.quietPolicy(),
                "valid_from",
                LocalDate.now(ZoneId.of(request.timeZone())));
    }

    private void validateRule(ReminderDtos.Create request) {
        if (request.enabled() == null || request.channel() == null || request.timeZone() == null)
            throw invalid("必填提醒字段不能清空");
        StatisticsService.checkedZone(request.timeZone());
        if (request.localTime() != null
                && (request.localTime().getSecond() != 0 || request.localTime().getNano() != 0))
            throw invalid("提醒时间仅精确到分钟，请使用HH:mm");
        boolean task = "TASK".equals(request.scope());
        if (task != (request.taskId() != null)
                || (task && request.localTime() != null)
                || (!task && (request.localTime() == null || request.offsetMinutes() != null)))
            throw invalid("任务提醒与日常记录提醒字段不匹配");
        if (task) sql.owned("task", Ids.parse(request.taskId()), AuthContext.userId());
        if (request.installationId() != null)
            sql.owned(
                    "client_installation",
                    Ids.parse(request.installationId()),
                    AuthContext.userId());
        if ("LOCAL_SYSTEM".equals(request.channel()) && request.installationId() == null)
            throw invalid("系统提醒必须指定手机安装");
    }

    private void cancelDeliveries(long user, Long ruleId, Long taskId) {
        String where = "user_id=? AND status IN ('PENDING','REGISTERED','UNKNOWN','FAILED')";
        List<Object> args = new ArrayList<>(List.of(Rows.utc(Instant.now()), user));
        if (ruleId != null) {
            where += " AND rule_id=?";
            args.add(ruleId);
        }
        if (taskId != null) {
            where += " AND task_id=?";
            args.add(taskId);
        }
        sql.execute(
                "UPDATE reminder_delivery SET status=CASE WHEN registered_at IS NULL THEN"
                        + " 'CANCELLED' ELSE 'CANCEL_REQUESTED'"
                        + " END,updated_at=?,row_version=row_version+1 WHERE "
                        + where,
                args.toArray());
    }

    private Map<String, Object> ruleView(Map<String, Object> row) {
        return Rows.map(
                "id",
                Rows.number(row, "id"),
                "rowVersion",
                Rows.number(row, "row_version"),
                "scope",
                "TASK_START".equals(row.get("rule_type")) ? "TASK" : "DAILY_RECORD",
                "taskId",
                row.get("task_id"),
                "enabled",
                Rows.bool(row, "is_enabled"),
                "channel",
                row.get("channel"),
                "offsetMinutes",
                row.get("offset_minutes"),
                "localTime",
                localTime(row.get("local_fire_time")),
                "timeZone",
                row.get("time_zone"),
                "quietPolicy",
                row.get("quiet_policy"),
                "installationId",
                row.get("target_installation_id"),
                "capabilityState",
                "LOCAL_SYSTEM".equals(row.get("channel")) && !localEnabled()
                        ? "UNVERIFIED"
                        : "AVAILABLE");
    }

    private Map<String, Object> notificationView(Map<String, Object> row) {
        String type = Rows.text(row, "target_type");
        return Rows.map(
                "id",
                Rows.number(row, "id"),
                "rowVersion",
                Rows.number(row, "row_version"),
                "category",
                row.get("category"),
                "title",
                row.get("title"),
                "body",
                crypto.decrypt(row.get("body_cipher")),
                "targetType",
                type == null ? "NONE" : "PRIVACY_REQUEST".equals(type) ? "PRIVACY" : type,
                "targetId",
                row.get("target_id"),
                "readAt",
                Rows.instant(row, "read_at"),
                "createdAt",
                Rows.instant(row, "created_at"));
    }

    private Map<String, Object> deliveryView(Map<String, Object> row) {
        String state = Rows.text(row, "status");
        boolean eligible = eligible(row);
        String command =
                "CANCELLED".equals(state)
                        ? "NONE"
                        : "CANCEL_REQUESTED".equals(state)
                                        || (!eligible && row.get("registered_at") != null)
                                ? "CANCEL"
                                : "PENDING".equals(state) && eligible ? "REGISTER" : "NONE";
        return Rows.map(
                "id",
                Rows.number(row, "id"),
                "rowVersion",
                Rows.number(row, "row_version"),
                "ruleId",
                row.get("rule_id"),
                "taskId",
                row.get("task_id"),
                "taskRowVersion",
                row.get("task_row_version"),
                "installationId",
                row.get("installation_id"),
                "channel",
                row.get("channel"),
                "fireAt",
                Rows.instant(row, "fire_at"),
                "expiresAt",
                Rows.instant(row, "expires_at"),
                "state",
                state,
                "command",
                command,
                "title",
                "生活安排提醒",
                "body",
                "打开知衡查看你的安排。",
                "osRequestId",
                crypto.decrypt(row.get("system_handle_cipher")),
                "lastObservedAt",
                Rows.instant(row, "registered_at"),
                "errorCode",
                row.get("last_error_code"));
    }

    private PageResult<Map<String, Object>> page(
            String table,
            String scope,
            String extraWhere,
            List<Object> extraArgs,
            String cursor,
            int count,
            boolean rules) {
        limit(count);
        long after = Rows.number(cursors.decode(cursor, scope), "id");
        List<Object> args = new ArrayList<>(List.of(AuthContext.userId(), after));
        args.addAll(extraArgs);
        args.add(count + 1);
        List<Map<String, Object>> rows =
                sql.query(
                        "SELECT * FROM "
                                + table
                                + " WHERE user_id=? AND id>? AND deleted_at IS NULL"
                                + extraWhere
                                + " ORDER BY id LIMIT ?",
                        args.toArray());
        boolean more = rows.size() > count;
        if (more) rows = rows.subList(0, count);
        return PageResult.of(
                rows.stream().map(r -> rules ? ruleView(r) : notificationView(r)).toList(),
                more
                        ? cursors.encode(scope, Rows.map("id", Rows.number(rows.getLast(), "id")))
                        : null,
                more);
    }

    private boolean localEnabled() {
        return sql.find(
                        "SELECT value_json FROM app_configuration WHERE environment=? AND"
                            + " config_key='feature.local_reminder' AND is_enabled=1 AND deleted_at"
                            + " IS NULL",
                        properties.getEnvironment())
                .map(
                        r -> {
                            JsonNode value = Rows.json(r, "value_json");
                            return value != null
                                    && value.path("booleanValue")
                                            .asBoolean(value.path("enabled").asBoolean(false));
                        })
                .orElse(false);
    }

    private void finishEvent(long id) {
        sql.execute(
                "UPDATE outbox_event SET"
                    + " status='DONE',processed_at=?,lease_owner=NULL,lease_until=NULL,updated_at=?,row_version=row_version+1"
                    + " WHERE id=?",
                Rows.utc(Instant.now()),
                Rows.utc(Instant.now()),
                id);
    }

    private boolean eligible(Map<String, Object> row) {
        if (Rows.instant(row, "expires_at").isBefore(Instant.now())) return false;
        if ("LOCAL_SYSTEM".equals(row.get("channel")) && !localEnabled()) return false;
        long user = Rows.number(row, "user_id");
        Map<String, Object> clock =
                sql.one("SELECT dataset_epoch FROM user_sync_clock WHERE user_id=?", user);
        if (Rows.number(clock, "dataset_epoch") != Rows.number(row, "captured_dataset_epoch"))
            return false;
        Optional<Map<String, Object>> consent =
                sql.find(
                        "SELECT * FROM user_consent WHERE user_id=? AND purpose_code='NOTIFICATION'"
                                + " AND deleted_at IS NULL AND status='GRANTED'",
                        user);
        if (consent.isEmpty()
                || Rows.number(consent.get(), "consent_version")
                        != Rows.number(row, "captured_consent_version")
                || (Rows.instant(consent.get(), "expires_at") != null
                        && !Rows.instant(consent.get(), "expires_at").isAfter(Instant.now())))
            return false;
        Optional<Map<String, Object>> rule =
                sql.find(
                        "SELECT * FROM reminder_rule WHERE id=? AND user_id=? AND deleted_at IS"
                                + " NULL AND is_enabled=1",
                        Rows.number(row, "rule_id"),
                        user);
        if (rule.isEmpty()
                || Rows.number(rule.get(), "row_version") != Rows.number(row, "rule_row_version"))
            return false;
        Optional<Map<String, Object>> installation =
                sql.find(
                        "SELECT * FROM client_installation WHERE id=? AND user_id=? AND revoked_at"
                                + " IS NULL AND deleted_at IS NULL",
                        Rows.number(row, "installation_id"),
                        user);
        if (installation.isEmpty()) return false;
        if ("LOCAL_SYSTEM".equals(row.get("channel"))) {
            JsonNode caps = Rows.json(installation.get(), "capabilities_json");
            if (!"PHONE".equals(installation.get().get("installation_kind"))
                    || !"GRANTED".equals(installation.get().get("notification_permission"))
                    || caps == null
                    || !"SUPPORTED".equals(caps.path("localReminder").asText())) return false;
        }
        if (row.get("task_id") != null
                && sql.count(
                                "SELECT COUNT(*) FROM task WHERE id=? AND user_id=? AND"
                                    + " row_version=? AND status='PENDING' AND deleted_at IS NULL",
                                Rows.number(row, "task_id"),
                                user,
                                Rows.number(row, "task_row_version"))
                        != 1) return false;
        return true;
    }

    public void dispatchInApp() {
        sql.execute(
                "UPDATE reminder_delivery SET"
                        + " status='EXPIRED',updated_at=?,row_version=row_version+1 WHERE"
                        + " status='PENDING' AND expires_at<?",
                Rows.utc(Instant.now()),
                Rows.utc(Instant.now()));
        for (Map<String, Object> candidate :
                sql.query(
                        "SELECT * FROM reminder_delivery WHERE channel='IN_APP' AND"
                            + " status='PENDING' AND fire_at<=? AND expires_at>? ORDER BY id LIMIT"
                            + " 20",
                        Instant.now(),
                        Instant.now())) {
            long user = Rows.number(candidate, "user_id");
            long epoch = Rows.number(candidate, "captured_dataset_epoch");
            try {
                guard.withUser(
                        user,
                        epoch,
                        "NOTIFICATION",
                        Rows.number(candidate, "captured_consent_version"),
                        () -> {
                            Map<String, Object> delivery =
                                    sql.one(
                                            "SELECT * FROM reminder_delivery WHERE id=? FOR UPDATE",
                                            Rows.number(candidate, "id"));
                            if (!"PENDING".equals(delivery.get("status")) || !eligible(delivery))
                                return null;
                            String dedup = "REMINDER_" + Rows.number(delivery, "id");
                            Optional<Map<String, Object>> old =
                                    sql.find(
                                            "SELECT * FROM notification_inbox WHERE user_id=? AND"
                                                    + " dedup_key=?",
                                            user,
                                            dedup);
                            long id;
                            Map<String, Object> notification;
                            if (old.isPresent()) {
                                notification = old.get();
                                id = Rows.number(notification, "id");
                            } else {
                                id =
                                        sql.insert(
                                                "notification_inbox",
                                                Rows.map(
                                                        "user_id",
                                                        user,
                                                        "notification_key",
                                                        UUID.randomUUID().toString(),
                                                        "category",
                                                        "REMINDER",
                                                        "title",
                                                        "生活安排提醒",
                                                        "body_cipher",
                                                        crypto.encrypt("打开知衡查看你的安排。"),
                                                        "encryption_key_ref",
                                                        crypto.keyRef(),
                                                        "target_type",
                                                        delivery.get("task_id") == null
                                                                ? null
                                                                : "TASK",
                                                        "target_id",
                                                        delivery.get("task_id"),
                                                        "priority",
                                                        "NORMAL",
                                                        "dedup_key",
                                                        dedup,
                                                        "expires_at",
                                                        Rows.instant(delivery, "expires_at")));
                                notification =
                                        sql.one("SELECT * FROM notification_inbox WHERE id=?", id);
                            }
                            String operation =
                                    UUID.nameUUIDFromBytes(dedup.getBytes(StandardCharsets.UTF_8))
                                            .toString();
                            guard.upsert(
                                    user,
                                    epoch,
                                    "NOTIFICATION",
                                    id,
                                    Rows.number(notification, "row_version"),
                                    notificationView(notification),
                                    operation);
                            sql.update(
                                    "reminder_delivery",
                                    Rows.number(delivery, "id"),
                                    Rows.number(delivery, "row_version"),
                                    Rows.map(
                                            "status",
                                            "REGISTERED",
                                            "registered_at",
                                            Instant.now()));
                            return null;
                        });
            } catch (BusinessException ignored) {
                /* Revoked/erased state must not create an inbox item. */
            }
        }
    }

    private void syncRule(long id, long version) {
        mutations.upsert(
                "REMINDER_RULE",
                id,
                version,
                ruleView(sql.owned("reminder_rule", id, AuthContext.userId())));
    }

    private void requireSessionInstallation(long id) {
        if (id != AuthContext.current().installationId()) throw BusinessException.notFound();
        sql.owned("client_installation", id, AuthContext.userId());
    }

    private void expected(Map<String, Object> row, String version) {
        if (Rows.number(row, "row_version") != Ids.parse(version))
            throw BusinessException.conflict("ROW_VERSION_CONFLICT", "提醒或通知已更新");
    }

    private void limit(int value) {
        if (value < 1 || value > 100) throw invalid("分页数量应为1至100");
    }

    private Long nullableId(Map<String, Object> row, String key) {
        return row.get(key) == null ? null : Rows.number(row, key);
    }

    private String safeCode(String code) {
        return code == null
                ? "REGISTRATION_FAILED"
                : code.matches("[A-Z0-9_]{1,48}") ? code : "REGISTRATION_FAILED";
    }

    private LocalTime localTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalTime time) return time;
        if (value instanceof Time time) return time.toLocalTime();
        return LocalTime.parse(value.toString());
    }

    private boolean inQuiet(LocalTime value, LocalTime start, LocalTime end) {
        if (start == null || end == null) return false;
        return start.isBefore(end)
                ? !value.isBefore(start) && value.isBefore(end)
                : !value.isBefore(start) || value.isBefore(end);
    }

    private BusinessException invalid(String message) {
        return BusinessException.badRequest("VALIDATION_FAILED", message);
    }
}
