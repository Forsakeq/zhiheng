package com.yqsoft.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yqsoft.dao.SqlStore;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.resource.common.Rows;
import com.yqsoft.service.common.CryptoService;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.function.Supplier;

@Component
public class WorkerGuard {

    private final SqlStore store;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final CryptoService crypto;
    private final ObjectMapper mapper;

    public WorkerGuard(
            SqlStore store,
            Clock clock,
            PlatformTransactionManager manager,
            CryptoService crypto,
            ObjectMapper mapper) {
        this.store = store;
        this.clock = clock;
        this.crypto = crypto;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setTimeout(30);
    }

    public <T> T withUser(
            long userId, long epoch, String purpose, Long consentVersion, Supplier<T> work) {
        return transactions.execute(
                status -> {
                    var sync =
                            store.one(
                                    "SELECT * FROM user_sync_clock WHERE user_id=? FOR UPDATE",
                                    userId);
                    var account = store.one("SELECT * FROM app_user WHERE id=? FOR UPDATE", userId);
                    if (Rows.number(sync, "dataset_epoch") != epoch) {
                        throw BusinessException.conflict("DATA_EPOCH_CHANGED", "作业所依据的数据已失效");
                    }
                    if (!"ACTIVE".equals(Rows.text(account, "status"))
                            || !"OPEN".equals(Rows.text(account, "data_write_fence"))) {
                        throw BusinessException.conflict("DATA_WRITE_FENCED", "当前作业不可写入");
                    }
                    if (purpose != null) {
                        var consent =
                                store.find(
                                                "SELECT * FROM user_consent WHERE user_id=? AND"
                                                        + " purpose_code=? AND deleted_at IS NULL",
                                                userId,
                                                purpose)
                                        .orElseThrow(
                                                () ->
                                                        BusinessException.forbidden(
                                                                "CONSENT_REQUIRED", "用途授权已失效"));
                        if (!"GRANTED".equals(Rows.text(consent, "status"))
                                || consentVersion == null
                                || Rows.number(consent, "consent_version") != consentVersion
                                || (Rows.instant(consent, "expires_at") != null
                                        && Rows.instant(consent, "expires_at")
                                                .isBefore(clock.instant()))) {
                            throw BusinessException.forbidden("CONSENT_CHANGED", "作业用途授权版本已改变");
                        }
                    }
                    return work.get();
                });
    }

    /** Publishes a server-owned projection without inventing an HTTP session. */
    public long upsert(
            long userId,
            long epoch,
            String type,
            long id,
            long version,
            Map<String, Object> projection,
            String operationId) {
        if (!MutationExecutor.SYNC_TYPES.contains(type) || version < 1) {
            throw new IllegalArgumentException("Invalid worker synchronization projection");
        }
        return withUser(
                userId,
                epoch,
                null,
                null,
                () -> {
                    var previous =
                            store.find(
                                    "SELECT change_seq FROM sync_change WHERE user_id=? AND"
                                        + " operation_id=? AND entity_type=? AND entity_id=? AND"
                                        + " entity_row_version=?",
                                    userId,
                                    operationId,
                                    type,
                                    id,
                                    version);
                    if (previous.isPresent()) {
                        return Rows.number(previous.get(), "change_seq");
                    }
                    long sequence =
                            store.count(
                                            "SELECT committed_seq FROM user_sync_clock WHERE"
                                                    + " user_id=?",
                                            userId)
                                    + 1;
                    byte[] content;
                    try {
                        content = crypto.encrypt(mapper.writeValueAsString(projection));
                    } catch (Exception exception) {
                        throw new IllegalArgumentException("Invalid worker projection");
                    }
                    store.insert(
                            "sync_change",
                            Rows.map(
                                    "user_id",
                                    userId,
                                    "change_seq",
                                    sequence,
                                    "dataset_epoch",
                                    epoch,
                                    "entity_type",
                                    type,
                                    "entity_id",
                                    id,
                                    "entity_row_version",
                                    version,
                                    "operation",
                                    "UPSERT",
                                    "changed_at",
                                    clock.instant(),
                                    "payload_schema_version",
                                    1,
                                    "payload_json",
                                    Rows.map(
                                            "schemaVersion",
                                            1,
                                            "ciphertext",
                                            Base64.getEncoder().encodeToString(content),
                                            "keyRef",
                                            crypto.keyRef()),
                                    "operation_id",
                                    operationId));
                    store.execute(
                            "UPDATE user_sync_clock SET committed_seq=?,updated_at=? WHERE"
                                    + " user_id=?",
                            sequence,
                            clock.instant(),
                            userId);
                    return sequence;
                });
    }
}
