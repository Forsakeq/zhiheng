package com.yqsoft.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yqsoft.dao.SqlStore;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.resource.common.Ids;
import com.yqsoft.resource.common.MutationResult;
import com.yqsoft.resource.common.RequestContext;
import com.yqsoft.resource.common.Rows;
import com.yqsoft.security.AuthContext;
import com.yqsoft.service.common.CryptoService;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

@Component
public class MutationExecutor {

    public static final Set<String> SYNC_TYPES =
            Set.of(
                    "PROFILE",
                    "PREFERENCE",
                    "GOAL",
                    "AVAILABILITY_RULE",
                    "AVAILABILITY_EXCEPTION",
                    "RECORD",
                    "RECORD_TAG",
                    "PLAN",
                    "PLAN_VERSION",
                    "TASK",
                    "REMINDER_RULE",
                    "NOTIFICATION");

    private final SqlStore store;
    private final CryptoService crypto;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final ThreadLocal<MutationState> current = new ThreadLocal<>();

    public MutationExecutor(
            SqlStore store,
            CryptoService crypto,
            ObjectMapper mapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.store = store;
        this.crypto = crypto;
        this.mapper = mapper;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setTimeout(30);
    }

    public MutationResult mutate(
            String code, Object request, boolean requireEpoch, Supplier<MutationResult> action) {
        if (active()) {
            if (current.get().userId != AuthContext.userId()) {
                throw new IllegalStateException("Cannot change transaction owner");
            }
            return action.get();
        }
        long userId = AuthContext.userId();
        String operationId = RequestContext.idempotencyKey();
        return transactions.execute(
                transaction -> {
                    var clockRow =
                            store.one(
                                    "SELECT * FROM user_sync_clock WHERE user_id = ? FOR UPDATE",
                                    userId);
                    var account =
                            store.one("SELECT * FROM app_user WHERE id = ? FOR UPDATE", userId);
                    long epoch = Rows.number(clockRow, "dataset_epoch");
                    Long submittedEpoch = RequestContext.dataEpoch();
                    if (requireEpoch && submittedEpoch == null) {
                        throw BusinessException.badRequest(
                                "DATA_EPOCH_REQUIRED", "请提供X-Data-Epoch");
                    }
                    if (submittedEpoch != null && submittedEpoch != epoch) {
                        throw BusinessException.conflict("DATA_EPOCH_CHANGED", "数据已清除或重新初始化，请重新同步");
                    }
                    byte[] hash =
                            crypto.digest(
                                    Rows.map(
                                            "operationCode",
                                            code,
                                            "epoch",
                                            epoch,
                                            "payload",
                                            request));
                    var previous =
                            store.find(
                                    "SELECT * FROM sync_operation WHERE user_id = ? AND"
                                            + " client_operation_id = ?",
                                    userId,
                                    operationId);
                    if (previous.isPresent()) {
                        var saved = previous.get();
                        if (!MessageDigest.isEqual(hash, (byte[]) saved.get("request_hash"))) {
                            throw BusinessException.conflict(
                                    "IDEMPOTENCY_KEY_REUSED", "幂等键已用于另一请求");
                        }
                        if (Rows.instant(saved, "expires_at").isBefore(clock.instant())) {
                            throw new BusinessException(410, "OPERATION_EXPIRED", "操作已过重放期限，请重新确认");
                        }
                        return replay(saved);
                    }
                    if (!"ACTIVE".equals(Rows.text(account, "status"))) {
                        throw BusinessException.forbidden("ACCOUNT_NOT_ACTIVE", "账号当前不可写入");
                    }
                    if (!"OPEN".equals(Rows.text(account, "data_write_fence"))) {
                        throw BusinessException.conflict("DATA_WRITE_FENCED", "数据清理正在执行，请稍后重试");
                    }
                    long initialSeq = Rows.number(clockRow, "committed_seq");
                    long operationRow =
                            store.insert(
                                    "sync_operation",
                                    Rows.map(
                                            "user_id",
                                            userId,
                                            "client_operation_id",
                                            operationId,
                                            "operation_code",
                                            code,
                                            "request_hash",
                                            hash,
                                            "dataset_epoch",
                                            epoch,
                                            "status",
                                            "PROCESSING",
                                            "expires_at",
                                            clock.instant().plus(Duration.ofDays(120)),
                                            "installation_id",
                                            AuthContext.current().installationId() > 0
                                                    ? AuthContext.current().installationId()
                                                    : null));
                    MutationState state = new MutationState(userId, epoch, initialSeq, operationId);
                    current.set(state);
                    try {
                        MutationResult result = action.get();
                        String changeSeq =
                                state.seq == initialSeq ? null : Long.toString(state.seq);
                        Map<String, Object> extra = receiptExtra(result.extra());
                        MutationResult receipt =
                                new MutationResult(
                                        result.resourceType(),
                                        result.id(),
                                        result.rowVersion(),
                                        changeSeq,
                                        operationId,
                                        false,
                                        extra);
                        store.execute(
                                "UPDATE sync_operation SET status='SUCCEEDED', resource_type=?,"
                                    + " resource_id=?, resource_row_version=?, last_change_seq=?,"
                                    + " result_json=?, completed_at=? WHERE id=?",
                                receipt.resourceType(),
                                Ids.optional(receipt.id()),
                                Ids.optional(receipt.rowVersion()),
                                changeSeq == null ? null : Ids.nonNegative(changeSeq),
                                receiptMap(receipt),
                                clock.instant(),
                                operationRow);
                        store.execute(
                                "UPDATE user_sync_clock SET committed_seq=?, updated_at=? WHERE"
                                        + " user_id=?",
                                state.seq,
                                clock.instant(),
                                userId);
                        return new MutationResult(
                                result.resourceType(),
                                result.id(),
                                result.rowVersion(),
                                changeSeq,
                                operationId,
                                false,
                                result.extra());
                    } finally {
                        current.remove();
                    }
                });
    }

    public void upsert(String type, long id, long version, Map<String, Object> projection) {
        assertSafeProjection(projection);
        byte[] encrypted;
        try {
            encrypted = crypto.encrypt(mapper.writeValueAsString(projection));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid synchronization projection");
        }
        change(
                type,
                id,
                version,
                "UPSERT",
                Rows.map(
                        "schemaVersion",
                        1,
                        "ciphertext",
                        Base64.getEncoder().encodeToString(encrypted),
                        "keyRef",
                        crypto.keyRef()));
    }

    public void delete(String type, long id, long version) {
        change(
                type,
                id,
                version,
                "DELETE",
                Rows.map(
                        "id",
                        Long.toString(id),
                        "rowVersion",
                        Long.toString(version),
                        "datasetEpoch",
                        Long.toString(currentEpoch())));
    }

    private void change(
            String type, long id, long version, String operation, Map<String, Object> payload) {
        if (!SYNC_TYPES.contains(type)) {
            throw new IllegalArgumentException("Unknown synchronization entity type");
        }
        MutationState state = state();
        state.seq = Math.addExact(state.seq, 1);
        store.insert(
                "sync_change",
                Rows.map(
                        "user_id",
                        state.userId,
                        "change_seq",
                        state.seq,
                        "dataset_epoch",
                        state.epoch,
                        "entity_type",
                        type,
                        "entity_id",
                        id,
                        "entity_row_version",
                        version,
                        "operation",
                        operation,
                        "changed_at",
                        clock.instant(),
                        "payload_schema_version",
                        1,
                        "payload_json",
                        payload,
                        "operation_id",
                        state.operationId));
    }

    public void outbox(
            String eventType,
            String aggregateType,
            long id,
            Long version,
            Map<String, Object> payload) {
        MutationState state = state();
        store.insert(
                "outbox_event",
                Rows.map(
                        "user_id",
                        state.userId,
                        "event_key",
                        UUID.randomUUID().toString(),
                        "event_type",
                        eventType,
                        "aggregate_type",
                        aggregateType,
                        "aggregate_id",
                        id,
                        "aggregate_row_version",
                        version,
                        "payload_schema_version",
                        1,
                        "payload_json",
                        payload,
                        "status",
                        "PENDING",
                        "available_at",
                        clock.instant(),
                        "captured_dataset_epoch",
                        state.epoch));
    }

    public Map<String, Object> assertConsent(String purpose) {
        var consent =
                store.find(
                                "SELECT * FROM user_consent WHERE user_id=? AND purpose_code=? AND"
                                        + " deleted_at IS NULL",
                                state().userId,
                                purpose)
                        .orElseThrow(
                                () ->
                                        BusinessException.forbidden(
                                                "CONSENT_REQUIRED", "请先确认相应用途授权"));
        if (!"GRANTED".equals(Rows.text(consent, "status"))
                || (Rows.instant(consent, "expires_at") != null
                        && Rows.instant(consent, "expires_at").isBefore(clock.instant()))) {
            throw BusinessException.forbidden("CONSENT_REQUIRED", "用途授权未生效或已撤回");
        }
        return consent;
    }

    public boolean active() {
        return current.get() != null;
    }

    public long currentEpoch() {
        return state().epoch;
    }

    public long currentSeq() {
        return state().seq;
    }

    private MutationState state() {
        MutationState state = current.get();
        if (state == null) {
            throw new IllegalStateException("A mutation transaction is required");
        }
        return state;
    }

    private MutationResult replay(Map<String, Object> saved) {
        var receipt = Rows.json(saved, "result_json");
        Map<String, Object> extra = new LinkedHashMap<>();
        receipt.fields()
                .forEachRemaining(
                        field -> {
                            if (!Set.of(
                                            "resourceType",
                                            "id",
                                            "rowVersion",
                                            "changeSeq",
                                            "operationId",
                                            "replayed")
                                    .contains(field.getKey())) {
                                extra.put(
                                        field.getKey(),
                                        mapper.convertValue(field.getValue(), Object.class));
                            }
                        });
        return new MutationResult(
                receipt.path("resourceType").asText(),
                receipt.path("id").asText(),
                receipt.path("rowVersion").asText(),
                receipt.path("changeSeq").isNull() ? null : receipt.path("changeSeq").asText(),
                receipt.path("operationId").asText(),
                true,
                extra);
    }

    private Map<String, Object> receiptMap(MutationResult result) {
        Map<String, Object> value =
                Rows.map(
                        "resourceType",
                        result.resourceType(),
                        "id",
                        result.id(),
                        "rowVersion",
                        result.rowVersion(),
                        "changeSeq",
                        result.changeSeq(),
                        "operationId",
                        result.operationId(),
                        "replayed",
                        false);
        value.putAll(result.extra());
        return value;
    }

    private Map<String, Object> receiptExtra(Map<String, Object> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (input == null) {
            return result;
        }
        input.forEach(
                (key, value) -> {
                    String lower = key.toLowerCase(java.util.Locale.ROOT);
                    if (lower.contains("token")
                            || lower.contains("credential")
                            || lower.contains("secret")) {
                        result.put("tokenUnavailable", true);
                    } else if (key.equals("results")
                            && value instanceof List<?> items
                            && items.stream().allMatch(MutationResult.class::isInstance)) {
                        result.put(
                                key,
                                items.stream()
                                        .map(MutationResult.class::cast)
                                        .map(
                                                item ->
                                                        receiptMap(
                                                                new MutationResult(
                                                                        item.resourceType(),
                                                                        item.id(),
                                                                        item.rowVersion(),
                                                                        item.changeSeq(),
                                                                        item.operationId(),
                                                                        item.replayed(),
                                                                        receiptExtra(
                                                                                item.extra()))))
                                        .toList());
                    } else if (key.endsWith("Id")
                            || key.endsWith("Ids")
                            || key.endsWith("Version")
                            || Set.of(
                                            "status",
                                            "draftRevisionNo",
                                            "revisionNo",
                                            "appliedResourceType")
                                    .contains(key)
                            || value instanceof Boolean) {
                        result.put(key, value);
                    }
                });
        return result;
    }

    private void assertSafeProjection(Object value) {
        if (value instanceof Map<?, ?> object) {
            object.forEach(
                    (key, item) -> {
                        String field = key.toString().toLowerCase(java.util.Locale.ROOT);
                        if (field.endsWith("_cipher")
                                || field.endsWith("_hash")
                                || field.contains("password")
                                || field.contains("refreshtoken")
                                || field.contains("accesstoken")) {
                            throw new IllegalArgumentException(
                                    "Credentials and storage ciphertext cannot be synchronized");
                        }
                        assertSafeProjection(item);
                    });
        } else if (value instanceof List<?> items) {
            items.forEach(this::assertSafeProjection);
        }
    }

    private static final class MutationState {
        private final long userId;
        private final long epoch;
        private final String operationId;
        private long seq;

        private MutationState(long userId, long epoch, long seq, String operationId) {
            this.userId = userId;
            this.epoch = epoch;
            this.seq = seq;
            this.operationId = operationId;
        }
    }
}
