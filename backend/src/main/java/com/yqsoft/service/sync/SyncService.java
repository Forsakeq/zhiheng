package com.yqsoft.service.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yqsoft.dao.SqlStore;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.persistence.MutationExecutor;
import com.yqsoft.persistence.OfflineCommandHandler;
import com.yqsoft.persistence.SyncProjectionProvider;
import com.yqsoft.resource.common.Ids;
import com.yqsoft.resource.common.RequestContext;
import com.yqsoft.resource.common.Rows;
import com.yqsoft.resource.sync.SyncDtos;
import com.yqsoft.security.AuthContext;
import com.yqsoft.service.common.CryptoService;
import com.yqsoft.service.common.CursorService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class SyncService {

    private static final Map<String, String> TABLES =
            Map.ofEntries(
                    Map.entry("PROFILE", "user_profile"),
                            Map.entry("PREFERENCE", "user_preference"),
                    Map.entry("GOAL", "user_goal"),
                            Map.entry("AVAILABILITY_RULE", "availability_rule"),
                    Map.entry("AVAILABILITY_EXCEPTION", "availability_exception"),
                            Map.entry("RECORD", "health_record"),
                    Map.entry("RECORD_TAG", "record_tag"), Map.entry("PLAN", "plan"),
                    Map.entry("PLAN_VERSION", "plan_version"), Map.entry("TASK", "task"),
                    Map.entry("REMINDER_RULE", "reminder_rule"),
                            Map.entry("NOTIFICATION", "notification_inbox"));

    private final SqlStore sql;
    private final ObjectMapper mapper;
    private final CryptoService crypto;
    private final CursorService cursors;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final List<SyncProjectionProvider> projections;
    private final Map<String, OfflineCommandHandler> operations = new LinkedHashMap<>();

    public SyncService(
            SqlStore sql,
            ObjectMapper mapper,
            CryptoService crypto,
            CursorService cursors,
            Clock clock,
            PlatformTransactionManager manager,
            List<SyncProjectionProvider> projections,
            List<OfflineCommandHandler> handlers) {
        this.sql = sql;
        this.mapper = mapper;
        this.crypto = crypto;
        this.cursors = cursors;
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
        this.projections = projections;
        handlers.forEach(
                handler ->
                        handler.operations()
                                .forEach(
                                        code -> {
                                            if (operations.put(code, handler) != null) {
                                                throw new IllegalStateException(
                                                        "Duplicate offline operation code");
                                            }
                                        }));
    }

    public Map<String, Object> begin(SyncDtos.BootstrapBegin request) {
        long installation = Ids.parse(request.installationId());
        long epoch = Ids.parse(request.datasetEpoch());
        Set<String> unique = new HashSet<>(request.entityTypes());
        if (unique.size() != request.entityTypes().size()
                || !MutationExecutor.SYNC_TYPES.containsAll(unique)) {
            throw BusinessException.badRequest("INVALID_ENTITY_TYPES", "同步实体类型无效或重复");
        }
        return transactions.execute(
                transaction -> {
                    var sync = lock(installation, epoch);
                    var expires = clock.instant().plus(Duration.ofHours(2));
                    String bootstrap = UUID.randomUUID().toString();
                    long start = Rows.number(sync, "committed_seq");
                    sql.execute(
                            "UPDATE client_installation SET sync_state='INITIALIZING',"
                                    + " bootstrap_id=?, bootstrap_from_seq=?, bootstrap_epoch=?,"
                                    + " bootstrap_expires_at=?, bootstrap_completed_at=NULL,"
                                    + " bootstrap_progress_json=?, updated_at=? WHERE id=?",
                            bootstrap,
                            start,
                            epoch,
                            expires,
                            Rows.map("entityTypes", request.entityTypes(), "doneTypes", List.of()),
                            clock.instant(),
                            installation);
                    return Rows.map(
                            "bootstrapId",
                            bootstrap,
                            "installationId",
                            request.installationId(),
                            "snapshotFromSeq",
                            Long.toString(start),
                            "datasetEpoch",
                            request.datasetEpoch(),
                            "entityTypes",
                            request.entityTypes(),
                            "expiresAt",
                            expires,
                            "retainedFromSeq",
                            Long.toString(Rows.number(sync, "retained_from_seq")));
                });
    }

    public Map<String, Object> page(
            String bootstrapId,
            long installation,
            long epoch,
            String type,
            String cursor,
            int limit) {
        bounded(limit, 100);
        if (!TABLES.containsKey(type)) {
            throw BusinessException.badRequest("INVALID_ENTITY_TYPE", "同步实体类型无效");
        }
        return transactions.execute(
                transaction -> {
                    lock(installation, epoch);
                    var device =
                            sql.owned("client_installation", installation, AuthContext.userId());
                    checkBootstrap(device, bootstrapId, epoch);
                    JsonNode progress = Rows.json(device, "bootstrap_progress_json");
                    List<String> types = new ArrayList<>();
                    progress.path("entityTypes").forEach(node -> types.add(node.asText()));
                    if (!types.contains(type)) {
                        throw BusinessException.badRequest("ENTITY_NOT_REQUESTED", "实体不在本次初始化范围");
                    }
                    String scope = "bootstrap:" + bootstrapId + ":" + type;
                    long after =
                            Long.parseLong(
                                    cursors.decode(cursor, scope)
                                            .getOrDefault("afterId", "0")
                                            .toString());
                    var rows =
                            sql.query(
                                    "SELECT id, row_version FROM "
                                            + sql.metadata().table(TABLES.get(type))
                                            + " WHERE user_id=? AND deleted_at IS NULL AND id>?"
                                            + " ORDER BY id LIMIT ?",
                                    AuthContext.userId(),
                                    after,
                                    limit + 1);
                    boolean more = rows.size() > limit;
                    List<Map<String, Object>> items = new ArrayList<>();
                    for (var row : rows.subList(0, Math.min(limit, rows.size()))) {
                        long id = Rows.number(row, "id");
                        items.add(
                                Rows.map(
                                        "entityType",
                                        type,
                                        "id",
                                        Long.toString(id),
                                        "rowVersion",
                                        Long.toString(Rows.number(row, "row_version")),
                                        "deletedAt",
                                        null,
                                        "payload",
                                        project(type, id)));
                    }
                    Set<String> done = new HashSet<>();
                    progress.path("doneTypes").forEach(node -> done.add(node.asText()));
                    if (!more) {
                        done.add(type);
                    }
                    long baseline = Rows.number(device, "bootstrap_from_seq");
                    sql.execute(
                            "UPDATE client_installation SET bootstrap_progress_json=?,"
                                + " last_delivered_seq=GREATEST(last_delivered_seq,?), updated_at=?"
                                + " WHERE id=?",
                            Rows.map(
                                    "entityTypes",
                                    types,
                                    "doneTypes",
                                    done.stream().sorted().toList()),
                            done.containsAll(types) ? baseline : 0,
                            clock.instant(),
                            installation);
                    String next =
                            more
                                    ? cursors.encode(
                                            scope,
                                            Rows.map(
                                                    "afterId",
                                                    Rows.number(rows.get(limit - 1), "id")))
                                    : null;
                    return Rows.map(
                            "bootstrapId",
                            bootstrapId,
                            "datasetEpoch",
                            Long.toString(epoch),
                            "snapshotFromSeq",
                            Long.toString(baseline),
                            "entityType",
                            type,
                            "items",
                            items,
                            "nextCursor",
                            next,
                            "hasMore",
                            more,
                            "expiresAt",
                            Rows.instant(device, "bootstrap_expires_at"));
                });
    }

    public Map<String, Object> changes(
            long installation, long epoch, long after, int limit, String bootstrap) {
        bounded(limit, 500);
        return transactions.execute(
                transaction -> {
                    var sync = lock(installation, epoch);
                    var device =
                            sql.owned("client_installation", installation, AuthContext.userId());
                    boolean initializing = "INITIALIZING".equals(Rows.text(device, "sync_state"));
                    if (initializing) {
                        checkBootstrap(device, bootstrap, epoch);
                        var progress = Rows.json(device, "bootstrap_progress_json");
                        if (progress.path("doneTypes").size()
                                != progress.path("entityTypes").size()) {
                            throw BusinessException.conflict("BOOTSTRAP_INCOMPLETE", "请先完成全部实体分页");
                        }
                    } else if (!"READY".equals(Rows.text(device, "sync_state"))
                            || Rows.number(device, "sync_epoch") != epoch) {
                        throw BusinessException.conflict("BOOTSTRAP_REQUIRED", "请先完成同步初始化");
                    }
                    if (after < Rows.number(sync, "retained_from_seq") - 1) {
                        throw new BusinessException(410, "CURSOR_EXPIRED", "同步日志已过期，请重新初始化");
                    }
                    if (after > Rows.number(device, "last_delivered_seq")) {
                        throw BusinessException.badRequest("UNDELIVERED_CURSOR", "游标超过已交付范围");
                    }
                    var rows =
                            sql.query(
                                    "SELECT * FROM sync_change WHERE user_id=? AND dataset_epoch=? "
                                            + "AND change_seq>? ORDER BY change_seq LIMIT ?",
                                    AuthContext.userId(),
                                    epoch,
                                    after,
                                    limit + 1);
                    boolean more = rows.size() > limit;
                    List<Map<String, Object>> items = new ArrayList<>();
                    long next = after;
                    for (var row : rows.subList(0, Math.min(limit, rows.size()))) {
                        next = Rows.number(row, "change_seq");
                        JsonNode payload = Rows.json(row, "payload_json");
                        if (payload.has("ciphertext")) {
                            try {
                                payload =
                                        mapper.readTree(
                                                crypto.decrypt(
                                                        Base64.getDecoder()
                                                                .decode(
                                                                        payload.path("ciphertext")
                                                                                .asText())));
                            } catch (Exception exception) {
                                throw new IllegalStateException(
                                        "Stored synchronization projection is invalid");
                            }
                        }
                        items.add(
                                Rows.map(
                                        "changeSeq",
                                        Long.toString(next),
                                        "entityType",
                                        Rows.text(row, "entity_type"),
                                        "entityId",
                                        Long.toString(Rows.number(row, "entity_id")),
                                        "rowVersion",
                                        Long.toString(Rows.number(row, "entity_row_version")),
                                        "operation",
                                        Rows.text(row, "operation"),
                                        "changedAt",
                                        Rows.instant(row, "changed_at"),
                                        "payload",
                                        payload));
                    }
                    sql.execute(
                            "UPDATE client_installation SET"
                                + " last_delivered_seq=GREATEST(last_delivered_seq,?), updated_at=?"
                                + " WHERE id=?",
                            next,
                            clock.instant(),
                            installation);
                    return Rows.map(
                            "datasetEpoch",
                            Long.toString(epoch),
                            "items",
                            items,
                            "nextAfterSeq",
                            Long.toString(next),
                            "hasMore",
                            more,
                            "serverCommittedSeq",
                            Long.toString(Rows.number(sync, "committed_seq")),
                            "retainedFromSeq",
                            Long.toString(Rows.number(sync, "retained_from_seq")),
                            "nextCursor",
                            null);
                });
    }

    public Map<String, Object> acknowledge(SyncDtos.Ack request) {
        long installation = Ids.parse(request.installationId());
        long epoch = Ids.parse(request.datasetEpoch());
        long ack = Ids.nonNegative(request.ackSeq());
        return transactions.execute(
                transaction -> {
                    var sync = lock(installation, epoch);
                    var device =
                            sql.owned("client_installation", installation, AuthContext.userId());
                    if (ack < Rows.number(device, "last_ack_seq")
                            || ack > Rows.number(device, "last_delivered_seq")) {
                        throw BusinessException.badRequest("INVALID_ACK", "确认序号须单调且不能超过已交付范围");
                    }
                    boolean complete = Boolean.TRUE.equals(request.bootstrapCompleted());
                    if (complete) {
                        checkBootstrap(
                                device,
                                request.bootstrapId() == null
                                        ? null
                                        : request.bootstrapId().toString(),
                                epoch);
                        JsonNode progress = Rows.json(device, "bootstrap_progress_json");
                        if (progress.path("doneTypes").size() != progress.path("entityTypes").size()
                                || ack < Rows.number(device, "bootstrap_from_seq")
                                || ack != Rows.number(device, "last_delivered_seq")
                                || ack != Rows.number(sync, "committed_seq")) {
                            throw BusinessException.conflict("BOOTSTRAP_INCOMPLETE", "初始化数据尚未完整落地");
                        }
                        sql.execute(
                                "UPDATE client_installation SET"
                                    + " sync_state='READY',sync_epoch=?,bootstrap_completed_at=?"
                                    + " WHERE id=?",
                                epoch,
                                clock.instant(),
                                installation);
                    } else if (!"READY".equals(Rows.text(device, "sync_state"))
                            || Rows.number(device, "sync_epoch") != epoch) {
                        throw BusinessException.conflict("BOOTSTRAP_REQUIRED", "请完成初始化握手");
                    }
                    sql.execute(
                            "UPDATE client_installation SET"
                                    + " last_ack_seq=?,last_ack_at=?,last_seen_at=? WHERE id=?",
                            ack,
                            clock.instant(),
                            clock.instant(),
                            installation);
                    return Rows.map(
                            "installationId",
                            request.installationId(),
                            "datasetEpoch",
                            request.datasetEpoch(),
                            "lastAckSeq",
                            request.ackSeq(),
                            "bootstrapCompleted",
                            complete,
                            "serverCommittedSeq",
                            Long.toString(Rows.number(sync, "committed_seq")));
                });
    }

    public Map<String, Object> operations(SyncDtos.Batch batch) {
        long installation = Ids.parse(batch.installationId());
        long epoch = Ids.parse(batch.datasetEpoch());
        transactions.execute(status -> lock(installation, epoch));
        List<Map<String, Object>> results = new ArrayList<>();
        String stopReason = null;
        var original = RequestContext.snapshot();
        try {
            for (var operation : batch.operations()) {
                if (stopReason != null) {
                    results.add(
                            Rows.map(
                                    "clientOperationId",
                                    operation.clientOperationId().toString(),
                                    "status",
                                    "NOT_PROCESSED",
                                    "result",
                                    null,
                                    "error",
                                    null,
                                    "conflict",
                                    null));
                    continue;
                }
                try {
                    if (Ids.parse(operation.datasetEpoch()) != epoch
                            || operation
                                    .clientOccurredAt()
                                    .isBefore(clock.instant().minus(Duration.ofDays(30)))
                            || operation
                                    .clientOccurredAt()
                                    .isAfter(clock.instant().plusSeconds(300))) {
                        throw BusinessException.conflict(
                                "OFFLINE_REBASE_REQUIRED", "离线操作已过期或时钟不可信，请重新确认");
                    }
                    OfflineCommandHandler handler = operations.get(operation.operationCode());
                    if (handler == null) {
                        throw BusinessException.badRequest("OPERATION_NOT_ALLOWED", "该操作不允许离线提交");
                    }
                    RequestContext.set(
                            original.requestId(), operation.clientOperationId().toString(), epoch);
                    var result =
                            handler.execute(
                                    operation.operationCode(),
                                    Ids.optional(operation.entityId()),
                                    operation.payload());
                    results.add(
                            Rows.map(
                                    "clientOperationId",
                                    operation.clientOperationId().toString(),
                                    "status",
                                    result.replayed() ? "REPLAYED" : "APPLIED",
                                    "result",
                                    result,
                                    "error",
                                    null,
                                    "conflict",
                                    null));
                } catch (BusinessException exception) {
                    results.add(
                            Rows.map(
                                    "clientOperationId",
                                    operation.clientOperationId().toString(),
                                    "status",
                                    exception.getStatus() == 409 ? "CONFLICT" : "REJECTED",
                                    "result",
                                    null,
                                    "error",
                                    Rows.map(
                                            "code",
                                            exception.getCode(),
                                            "message",
                                            exception.getMessage(),
                                            "fieldErrors",
                                            List.of(),
                                            "retryable",
                                            false,
                                            "retryAfterSeconds",
                                            null,
                                            "conflict",
                                            null),
                                    "conflict",
                                    null));
                    if (Set.of("DATA_EPOCH_CHANGED", "DATA_WRITE_FENCED", "ACCOUNT_NOT_ACTIVE")
                            .contains(exception.getCode())) {
                        stopReason = exception.getCode();
                    }
                }
            }
        } finally {
            RequestContext.restore(original);
        }
        long sequence =
                sql.count(
                        "SELECT committed_seq FROM user_sync_clock WHERE user_id=?",
                        AuthContext.userId());
        return Rows.map(
                "datasetEpoch",
                batch.datasetEpoch(),
                "results",
                results,
                "lastCommittedSeq",
                Long.toString(sequence),
                "stopReason",
                stopReason);
    }

    private Map<String, Object> lock(long installation, long epoch) {
        long user = AuthContext.userId();
        if (installation != AuthContext.current().installationId()) {
            throw BusinessException.notFound();
        }
        var sync = sql.one("SELECT * FROM user_sync_clock WHERE user_id=? FOR UPDATE", user);
        var account = sql.one("SELECT * FROM app_user WHERE id=? FOR UPDATE", user);
        if (Rows.number(sync, "dataset_epoch") != epoch) {
            throw BusinessException.conflict("DATA_EPOCH_CHANGED", "数据代际已改变，请清理旧缓存重新同步");
        }
        if (!"OPEN".equals(Rows.text(account, "data_write_fence"))) {
            throw BusinessException.conflict("DATA_WRITE_FENCED", "数据清理期间暂停同步");
        }
        var device = sql.owned("client_installation", installation, user);
        if (device.get("revoked_at") != null) {
            throw BusinessException.forbidden("INSTALLATION_REVOKED", "安装凭据已撤销");
        }
        return sync;
    }

    private void checkBootstrap(Map<String, Object> device, String bootstrap, long epoch) {
        if (!"INITIALIZING".equals(Rows.text(device, "sync_state"))
                || bootstrap == null
                || !bootstrap.equals(Rows.text(device, "bootstrap_id"))
                || epoch != Rows.number(device, "bootstrap_epoch")) {
            throw BusinessException.conflict("BOOTSTRAP_REQUIRED", "初始化租约无效");
        }
        if (Rows.instant(device, "bootstrap_expires_at") == null
                || Rows.instant(device, "bootstrap_expires_at").isBefore(clock.instant())) {
            throw new BusinessException(410, "CURSOR_EXPIRED", "初始化租约已过期");
        }
    }

    private Map<String, Object> project(String type, long id) {
        return projections.stream()
                .filter(provider -> provider.entityTypes().contains(type))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Missing synchronization projection provider"))
                .project(type, id);
    }

    private void bounded(int limit, int max) {
        if (limit < 1 || limit > max) {
            throw BusinessException.badRequest("INVALID_PAGE_SIZE", "分页数量超过允许范围");
        }
    }
}
