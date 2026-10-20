package com.yqsoft.service.record;

import static com.yqsoft.resource.common.Rows.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yqsoft.dao.SqlStore;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.persistence.MutationExecutor;
import com.yqsoft.resource.common.*;
import com.yqsoft.resource.record.RecordDtos.*;
import com.yqsoft.security.AuthContext;
import com.yqsoft.service.common.CryptoService;
import com.yqsoft.service.common.CursorService;
import com.yqsoft.service.file.FileService;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class RecordService {
    private final SqlStore store;
    private final MutationExecutor mutations;
    private final CryptoService crypto;
    private final CursorService cursors;
    private final ValidationSupport validation;
    private final FileService files;
    private final ObjectMapper mapper;

    public RecordService(
            SqlStore store,
            MutationExecutor mutations,
            CryptoService crypto,
            CursorService cursors,
            ValidationSupport validation,
            FileService files,
            ObjectMapper mapper) {
        this.store = store;
        this.mutations = mutations;
        this.crypto = crypto;
        this.cursors = cursors;
        this.validation = validation;
        this.files = files;
        this.mapper = mapper;
    }

    public PageResult<RecordView> list(RecordQuery query) {
        validation.validate(query);
        LocalDate today = LocalDate.now(userZone());
        LocalDate from = query.from() == null ? today.minusDays(29) : query.from();
        LocalDate to = query.to() == null ? today : query.to();
        requireRange(from, to, 366);
        if ((query.from() == null) != (query.to() == null))
            throw invalid("DATE_RANGE_REQUIRED", "起止日期必须一起填写");
        int limit = query.limit() == null ? 20 : query.limit();
        boolean ascending = "OCCURRED_ASC".equals(query.sort());
        if (query.sort() != null && !Set.of("OCCURRED_ASC", "OCCURRED_DESC").contains(query.sort()))
            throw invalid("INVALID_SORT", "排序方式不支持");
        boolean deleted = Boolean.TRUE.equals(query.includeDeleted());
        String scope =
                "records:"
                        + from
                        + ":"
                        + to
                        + ":"
                        + query.recordType()
                        + ":"
                        + query.tagId()
                        + ":"
                        + deleted
                        + ":"
                        + ascending;
        Map<String, Object> cursor = cursors.decode(query.cursor(), scope);
        List<Object> parameters = new ArrayList<>(List.of(AuthContext.userId(), from, to));
        StringBuilder sql =
                new StringBuilder(
                        "SELECT r.* FROM health_record r WHERE r.user_id=? AND r.record_local_date"
                                + " BETWEEN ? AND ?");
        sql.append(
                deleted
                        ? " AND (r.deleted_at IS NULL OR r.deleted_at>=UTC_TIMESTAMP(3)-INTERVAL 7"
                                + " DAY)"
                        : " AND r.deleted_at IS NULL");
        if (query.recordType() != null) {
            sql.append(" AND r.record_type=?");
            parameters.add(query.recordType().name());
        }
        if (query.tagId() != null) {
            long tag = Ids.parse(query.tagId());
            store.owned("record_tag", tag, AuthContext.userId());
            sql.append(
                    " AND EXISTS (SELECT 1 FROM record_tag_relation tr WHERE tr.user_id=r.user_id"
                            + " AND tr.record_id=r.id AND tr.tag_id=?)");
            parameters.add(tag);
        }
        if (!cursor.isEmpty()) {
            Instant at = Instant.parse(cursor.get("at").toString());
            long id = Ids.parse(cursor.get("id").toString());
            sql.append(
                    ascending
                            ? " AND (r.started_at>? OR (r.started_at=? AND r.id>?))"
                            : " AND (r.started_at<? OR (r.started_at=? AND r.id<?))");
            parameters.add(utc(at));
            parameters.add(utc(at));
            parameters.add(id);
        }
        sql.append(
                ascending
                        ? " ORDER BY r.started_at,r.id LIMIT ?"
                        : " ORDER BY r.started_at DESC,r.id DESC LIMIT ?");
        parameters.add(limit + 1);
        List<Map<String, Object>> rows = store.query(sql.toString(), parameters.toArray());
        boolean more = rows.size() > limit;
        if (more) rows = rows.subList(0, limit);
        String next = null;
        if (more) {
            Map<String, Object> last = rows.getLast();
            next =
                    cursors.encode(
                            scope,
                            map(
                                    "at",
                                    instant(last, "started_at").toString(),
                                    "id",
                                    Long.toString(number(last, "id"))));
        }
        return PageResult.of(rows.stream().map(this::view).toList(), next, more);
    }

    public RecordView get(long id) {
        return view(store.owned("health_record", id, AuthContext.userId()));
    }

    public RecordTagView tag(long id) {
        return tagView(store.owned("record_tag", id, AuthContext.userId()));
    }

    public MutationResult create(RecordCreate request) {
        return createInternal(request, "MANUAL", null);
    }

    public MutationResult createConfirmed(RecordCreate request) {
        return createInternal(request, "AI_CONFIRMED", null);
    }

    public MutationResult createConfirmed(RecordCreate request, long draftId) {
        store.owned("ai_draft", draftId, AuthContext.userId());
        return createInternal(request, "AI_CONFIRMED", draftId);
    }

    private MutationResult createInternal(RecordCreate request, String source, Long draftId) {
        validation.validate(request);
        validateRecord(request);
        return mutations.mutate(
                "RECORD_CREATE",
                map("record", request, "source", source, "draftId", draftId),
                true,
                () -> {
                    Optional<Map<String, Object>> duplicate =
                            store.find(
                                    "SELECT * FROM health_record WHERE user_id=? AND"
                                            + " client_record_id=?",
                                    AuthContext.userId(),
                                    request.clientRecordId().toString());
                    if (duplicate.isPresent())
                        throw BusinessException.conflict(
                                "CLIENT_RECORD_EXISTS", "该记录编号已保存，请核对已有记录");
                    validateTagIds(request.tagIds());
                    long id =
                            store.insert(
                                    "health_record",
                                    map(
                                            "user_id",
                                            AuthContext.userId(),
                                            "client_record_id",
                                            request.clientRecordId().toString(),
                                            "record_type",
                                            request.recordType().name(),
                                            "started_at",
                                            request.startedAt(),
                                            "ended_at",
                                            request.endedAt(),
                                            "record_local_date",
                                            request.recordLocalDate(),
                                            "record_time_zone",
                                            request.recordTimeZone(),
                                            "utc_offset_minutes",
                                            request.utcOffsetMinutes(),
                                            "source_type",
                                            source,
                                            "quality_status",
                                            "VALID",
                                            "notes_cipher",
                                            crypto.encrypt(request.notes()),
                                            "encryption_key_ref",
                                            request.notes() == null ? null : crypto.keyRef(),
                                            "confirmed_at",
                                            Instant.now(),
                                            "ai_draft_id",
                                            draftId));
                    saveDetail(id, request);
                    replaceTagRelations(id, request.tagIds());
                    syncRecord(id, 1);
                    invalidate(id, Set.of(request.recordLocalDate()));
                    return MutationResult.of("RECORD", id, 1);
                });
    }

    public MutationResult replace(long id, RecordReplace request) {
        validation.validate(request);
        return mutations.mutate(
                "RECORD_REPLACE",
                map("id", id, "record", request),
                true,
                () -> {
                    Map<String, Object> old =
                            store.lockOwned("health_record", id, AuthContext.userId());
                    RecordCreate complete =
                            new RecordCreate(
                                    UUID.fromString(text(old, "client_record_id")),
                                    RecordType.valueOf(text(old, "record_type")),
                                    request.startedAt(),
                                    request.endedAt(),
                                    request.recordTimeZone(),
                                    request.recordLocalDate(),
                                    request.utcOffsetMinutes(),
                                    request.notes(),
                                    request.tagIds(),
                                    request.detail());
                    validateRecord(complete);
                    validateTagIds(request.tagIds());
                    assertExternalAuthorization(old);
                    long version =
                            store.update(
                                    "health_record",
                                    id,
                                    Ids.parse(request.expectedRowVersion()),
                                    map(
                                            "started_at",
                                            request.startedAt(),
                                            "ended_at",
                                            request.endedAt(),
                                            "record_local_date",
                                            request.recordLocalDate(),
                                            "record_time_zone",
                                            request.recordTimeZone(),
                                            "utc_offset_minutes",
                                            request.utcOffsetMinutes(),
                                            "notes_cipher",
                                            crypto.encrypt(request.notes()),
                                            "encryption_key_ref",
                                            request.notes() == null ? null : crypto.keyRef()));
                    store.execute(
                            "DELETE FROM "
                                    + detailTable(complete.recordType())
                                    + " WHERE user_id=? AND record_id=?",
                            AuthContext.userId(),
                            id);
                    saveDetail(id, complete);
                    replaceTagRelations(id, request.tagIds());
                    syncRecord(id, version);
                    invalidate(
                            id,
                            new HashSet<>(
                                    List.of(
                                            date(old, "record_local_date"),
                                            request.recordLocalDate())));
                    return MutationResult.of("RECORD", id, version);
                });
    }

    public MutationResult delete(long id, VersionBody request) {
        validation.validate(request);
        return mutations.mutate(
                "RECORD_DELETE",
                map("id", id, "request", request),
                true,
                () -> {
                    Map<String, Object> row =
                            store.lockOwned("health_record", id, AuthContext.userId());
                    long version =
                            store.update(
                                    "health_record",
                                    id,
                                    Ids.parse(request.expectedRowVersion()),
                                    map("deleted_at", Instant.now()));
                    mutations.delete("RECORD", id, version);
                    invalidate(id, Set.of(date(row, "record_local_date")));
                    return MutationResult.of("RECORD", id, version);
                });
    }

    public MutationResult restore(long id, VersionBody request) {
        validation.validate(request);
        return mutations.mutate(
                "RECORD_RESTORE",
                map("id", id, "request", request),
                true,
                () -> {
                    Map<String, Object> row =
                            store.ownedIncludingDeleted("health_record", id, AuthContext.userId());
                    Instant deleted = instant(row, "deleted_at");
                    if (deleted == null
                            || deleted.isBefore(Instant.now().minus(7, ChronoUnit.DAYS)))
                        throw BusinessException.conflict("RECORD_NOT_RESTORABLE", "记录不在普通删除恢复窗口内");
                    assertExternalAuthorization(row);
                    store.one(
                            "SELECT record_id FROM "
                                    + detailTable(RecordType.valueOf(text(row, "record_type")))
                                    + " WHERE user_id=? AND record_id=?",
                            AuthContext.userId(),
                            id);
                    long version =
                            store.update(
                                    "health_record",
                                    id,
                                    Ids.parse(request.expectedRowVersion()),
                                    map("deleted_at", null));
                    syncRecord(id, version);
                    invalidate(id, Set.of(date(row, "record_local_date")));
                    return MutationResult.of("RECORD", id, version);
                });
    }

    public PageResult<RecordTagView> listTags(String cursor, Integer requestedLimit) {
        int limit = requestedLimit == null ? 20 : requestedLimit;
        if (limit < 1 || limit > 100) throw invalid("INVALID_LIMIT", "分页大小应为1到100");
        Map<String, Object> position = cursors.decode(cursor, "record-tags");
        long after = position.isEmpty() ? 0 : Ids.parse(position.get("id").toString());
        List<Map<String, Object>> rows =
                store.query(
                        "SELECT * FROM record_tag WHERE user_id=? AND deleted_at IS NULL AND id>?"
                                + " ORDER BY id LIMIT ?",
                        AuthContext.userId(),
                        after,
                        limit + 1);
        boolean more = rows.size() > limit;
        if (more) rows = rows.subList(0, limit);
        String next =
                more
                        ? cursors.encode(
                                "record-tags",
                                map("id", Long.toString(number(rows.getLast(), "id"))))
                        : null;
        return PageResult.of(rows.stream().map(this::tagView).toList(), next, more);
    }

    public MutationResult createTag(RecordTagCreate request) {
        validation.validate(request);
        return mutations.mutate(
                "TAG_CREATE",
                request,
                true,
                () -> {
                    String name = cleanName(request.name());
                    byte[] hash = tagHash(name);
                    Optional<Map<String, Object>> previous =
                            store.find(
                                    "SELECT * FROM record_tag WHERE user_id=? AND"
                                            + " normalized_name_hash=? FOR UPDATE",
                                    AuthContext.userId(),
                                    hash);
                    if (previous.isPresent()) {
                        Map<String, Object> row = previous.get();
                        if (row.get("deleted_at") == null)
                            throw BusinessException.conflict("TAG_NAME_EXISTS", "同名标签已存在");
                        long id = number(row, "id");
                        long version =
                                store.update(
                                        "record_tag",
                                        id,
                                        number(row, "row_version"),
                                        map(
                                                "deleted_at",
                                                null,
                                                "is_archived",
                                                0,
                                                "name_cipher",
                                                crypto.encrypt(name),
                                                "encryption_key_ref",
                                                crypto.keyRef(),
                                                "color_token",
                                                request.color() == null
                                                        ? "TEAL"
                                                        : request.color().name(),
                                                "sort_order",
                                                request.sortOrder() == null
                                                        ? 0
                                                        : request.sortOrder()));
                        mutations.upsert(
                                "RECORD_TAG",
                                id,
                                version,
                                map(
                                        "id",
                                        Long.toString(id),
                                        "rowVersion",
                                        Long.toString(version),
                                        "name",
                                        name,
                                        "color",
                                        request.color() == null ? "TEAL" : request.color().name(),
                                        "sortOrder",
                                        request.sortOrder() == null ? 0 : request.sortOrder()));
                        return MutationResult.of("RECORD_TAG", id, version);
                    }
                    long id =
                            store.insert(
                                    "record_tag",
                                    map(
                                            "user_id",
                                            AuthContext.userId(),
                                            "tag_code",
                                            UUID.randomUUID().toString(),
                                            "name_cipher",
                                            crypto.encrypt(name),
                                            "normalized_name_hash",
                                            hash,
                                            "encryption_key_ref",
                                            crypto.keyRef(),
                                            "color_token",
                                            request.color() == null
                                                    ? "TEAL"
                                                    : request.color().name(),
                                            "sort_order",
                                            request.sortOrder() == null ? 0 : request.sortOrder(),
                                            "is_archived",
                                            0));
                    mutations.upsert(
                            "RECORD_TAG",
                            id,
                            1,
                            map(
                                    "id",
                                    Long.toString(id),
                                    "rowVersion",
                                    "1",
                                    "name",
                                    name,
                                    "color",
                                    request.color() == null ? "TEAL" : request.color().name(),
                                    "sortOrder",
                                    request.sortOrder() == null ? 0 : request.sortOrder()));
                    return MutationResult.of("RECORD_TAG", id, 1);
                });
    }

    public MutationResult updateTag(long id, RecordTagPatch request) {
        validation.validate(request);
        if (request.name() == null && request.color() == null && request.sortOrder() == null)
            throw invalid("EMPTY_PATCH", "至少修改一个字段");
        return mutations.mutate(
                "TAG_PATCH",
                map("id", id, "request", request),
                true,
                () -> {
                    store.lockOwned("record_tag", id, AuthContext.userId());
                    Map<String, Object> values = new LinkedHashMap<>();
                    if (request.name() != null) {
                        String name = cleanName(request.name());
                        byte[] hash = tagHash(name);
                        if (store.count(
                                        "SELECT COUNT(*) FROM record_tag WHERE user_id=? AND"
                                                + " normalized_name_hash=? AND id<>?",
                                        AuthContext.userId(),
                                        hash,
                                        id)
                                > 0) throw BusinessException.conflict("TAG_NAME_EXISTS", "同名标签已存在");
                        values.put("name_cipher", crypto.encrypt(name));
                        values.put("normalized_name_hash", hash);
                        values.put("encryption_key_ref", crypto.keyRef());
                    }
                    if (request.color() != null) values.put("color_token", request.color().name());
                    if (request.sortOrder() != null) values.put("sort_order", request.sortOrder());
                    long version =
                            store.update(
                                    "record_tag",
                                    id,
                                    Ids.parse(request.expectedRowVersion()),
                                    values);
                    RecordTagView view =
                            tagView(store.owned("record_tag", id, AuthContext.userId()));
                    mutations.upsert(
                            "RECORD_TAG",
                            id,
                            version,
                            map(
                                    "id",
                                    Long.toString(id),
                                    "rowVersion",
                                    Long.toString(version),
                                    "name",
                                    view.name(),
                                    "color",
                                    view.color(),
                                    "sortOrder",
                                    view.sortOrder()));
                    return MutationResult.of("RECORD_TAG", id, version);
                });
    }

    public MutationResult deleteTag(long id, VersionBody request) {
        validation.validate(request);
        return mutations.mutate(
                "TAG_DELETE",
                map("id", id, "request", request),
                true,
                () -> {
                    store.lockOwned("record_tag", id, AuthContext.userId());
                    List<Map<String, Object>> records =
                            store.query(
                                    "SELECT r.* FROM health_record r JOIN record_tag_relation x ON"
                                            + " x.record_id=r.id AND x.user_id=r.user_id WHERE"
                                            + " x.user_id=? AND x.tag_id=?",
                                    AuthContext.userId(),
                                    id);
                    store.execute(
                            "DELETE FROM record_tag_relation WHERE user_id=? AND tag_id=?",
                            AuthContext.userId(),
                            id);
                    long version =
                            store.update(
                                    "record_tag",
                                    id,
                                    Ids.parse(request.expectedRowVersion()),
                                    map("deleted_at", Instant.now(), "is_archived", 1));
                    mutations.delete("RECORD_TAG", id, version);
                    for (Map<String, Object> record : records) {
                        long recordId = number(record, "id");
                        long recordVersion =
                                store.update(
                                        "health_record",
                                        recordId,
                                        number(record, "row_version"),
                                        Map.of());
                        if (record.get("deleted_at") == null) syncRecord(recordId, recordVersion);
                    }
                    return MutationResult.of("RECORD_TAG", id, version);
                });
    }

    public MutationResult replaceTags(long id, RecordTagsPut request) {
        validation.validate(request);
        return mutations.mutate(
                "RECORD_TAGS_REPLACE",
                map("id", id, "request", request),
                true,
                () -> {
                    store.lockOwned("health_record", id, AuthContext.userId());
                    validateTagIds(request.tagIds());
                    replaceTagRelations(id, request.tagIds());
                    long version =
                            store.update(
                                    "health_record",
                                    id,
                                    Ids.parse(request.expectedRowVersion()),
                                    Map.of());
                    syncRecord(id, version);
                    return MutationResult.of("RECORD", id, version);
                });
    }

    public MutationResult attach(long id, AttachmentCreate request) {
        validation.validate(request);
        return mutations.mutate(
                "ATTACH_RECORD_FILE",
                map("id", id, "request", request),
                true,
                () -> {
                    store.lockOwned("health_record", id, AuthContext.userId());
                    long fileId = Ids.parse(request.fileId());
                    Map<String, Object> file = files.requireClean(fileId, "RECORD_ATTACHMENT");
                    if (store.count(
                                    "SELECT COUNT(*) FROM record_attachment WHERE record_id=? AND"
                                            + " user_id=?",
                                    id,
                                    AuthContext.userId())
                            >= 9) throw invalid("TOO_MANY_ATTACHMENTS", "每条记录最多9个附件");
                    if (store.count(
                                    "SELECT COUNT(*) FROM record_attachment WHERE record_id=? AND"
                                            + " file_id=?",
                                    id,
                                    fileId)
                            > 0) throw BusinessException.conflict("ATTACHMENT_EXISTS", "该文件已关联此记录");
                    long attachment =
                            store.insert(
                                    "record_attachment",
                                    map(
                                            "user_id",
                                            AuthContext.userId(),
                                            "record_id",
                                            id,
                                            "file_id",
                                            fileId,
                                            "attachment_role",
                                            text(file, "mime_type").startsWith("image/")
                                                    ? "PHOTO"
                                                    : "NOTE_ATTACHMENT",
                                            "sort_order",
                                            request.sortOrder() == null ? 0 : request.sortOrder(),
                                            "caption_cipher",
                                            crypto.encrypt(request.caption()),
                                            "encryption_key_ref",
                                            request.caption() == null ? null : crypto.keyRef(),
                                            "operation_id",
                                            RequestContext.idempotencyKey()));
                    long version =
                            store.update(
                                    "health_record",
                                    id,
                                    Ids.parse(request.parentRecordRowVersion()),
                                    Map.of());
                    syncRecord(id, version);
                    return MutationResult.of("RECORD", id, version)
                            .withExtra(map("attachmentId", Long.toString(attachment)));
                });
    }

    public MutationResult removeAttachment(long id, long attachmentId, AttachmentRemove request) {
        validation.validate(request);
        return mutations.mutate(
                "REMOVE_RECORD_ATTACHMENT",
                map("id", id, "attachmentId", attachmentId, "request", request),
                true,
                () -> {
                    store.lockOwned("health_record", id, AuthContext.userId());
                    store.one(
                            "SELECT id FROM record_attachment WHERE id=? AND record_id=? AND"
                                    + " user_id=?",
                            attachmentId,
                            id,
                            AuthContext.userId());
                    store.execute(
                            "DELETE FROM record_attachment WHERE id=? AND record_id=? AND"
                                    + " user_id=?",
                            attachmentId,
                            id,
                            AuthContext.userId());
                    long version =
                            store.update(
                                    "health_record",
                                    id,
                                    Ids.parse(request.parentRecordRowVersion()),
                                    Map.of());
                    syncRecord(id, version);
                    return MutationResult.of("RECORD", id, version);
                });
    }

    public CoverageView coverage(LocalDate from, LocalDate to, List<RecordType> requestedTypes) {
        requireRange(from, to, 366);
        List<RecordType> types =
                requestedTypes == null || requestedTypes.isEmpty()
                        ? List.of(RecordType.values())
                        : requestedTypes;
        if (new HashSet<>(types).size() != types.size() || types.size() > 4)
            throw invalid("DUPLICATE_TYPE", "记录类型不能重复");
        List<Map<String, Object>> rows =
                store.query(
                        "SELECT record_type,record_local_date,COUNT(*) record_count FROM"
                            + " health_record WHERE user_id=? AND deleted_at IS NULL AND"
                            + " quality_status='VALID' AND record_local_date BETWEEN ? AND ? GROUP"
                            + " BY record_type,record_local_date",
                        AuthContext.userId(),
                        from,
                        to);
        int total = (int) ChronoUnit.DAYS.between(from, to) + 1;
        List<CoverageItem> items = new ArrayList<>();
        for (RecordType type : types) {
            Set<LocalDate> dates = new HashSet<>();
            int count = 0;
            for (Map<String, Object> row : rows)
                if (type.name().equals(text(row, "record_type"))) {
                    dates.add(date(row, "record_local_date"));
                    count += integer(row, "record_count");
                }
            List<LocalDate> missing =
                    from.datesUntil(to.plusDays(1)).filter(day -> !dates.contains(day)).toList();
            items.add(
                    new CoverageItem(
                            type,
                            dates.size(),
                            count,
                            missing,
                            BigDecimal.valueOf(dates.size())
                                    .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP)));
        }
        long seq =
                store.count(
                        "SELECT committed_seq FROM user_sync_clock WHERE user_id=?",
                        AuthContext.userId());
        return new CoverageView(from, to, total, items, seq);
    }

    public static void validateRecord(RecordCreate request) {
        if (request.recordType() == null
                || request.detail() == null
                || !request.recordType().name().equals(request.detail().detailType()))
            throw invalid("DETAIL_TYPE_MISMATCH", "记录类型与唯一详情不一致");
        ZoneId zone;
        try {
            zone = ZoneId.of(request.recordTimeZone());
        } catch (Exception e) {
            throw invalid("INVALID_TIME_ZONE", "时区无效");
        }
        Instant reference =
                request.recordType() == RecordType.SLEEP ? request.endedAt() : request.startedAt();
        if (reference == null || request.startedAt() == null)
            throw invalid("RECORD_TIME_REQUIRED", "请填写完整发生时间");
        if (!reference.atZone(zone).toLocalDate().equals(request.recordLocalDate())
                || zone.getRules().getOffset(reference).getTotalSeconds() / 60
                        != request.utcOffsetMinutes())
            throw invalid("RECORD_DATE_MISMATCH", "业务日或偏移与发生时间不一致");
        if (request.recordType() == RecordType.SLEEP
                || request.recordType() == RecordType.ACTIVITY) {
            if (request.endedAt() == null || !request.endedAt().isAfter(request.startedAt()))
                throw invalid("INVALID_INTERVAL", "结束时间必须晚于开始");
            long minutes = Duration.between(request.startedAt(), request.endedAt()).toMinutes();
            if (minutes < 1 || (request.recordType() == RecordType.SLEEP && minutes > 2160))
                throw invalid("INVALID_INTERVAL", "发生区间长度不符合记录类型");
            if (request.detail() instanceof ActivityDetailInput detail) {
                if (detail.actualDurationMinutes() > minutes)
                    throw invalid("ACTIVITY_DURATION_EXCEEDS_INTERVAL", "实际活动时长不能大于发生区间");
                if (detail.activityType() == ActivityType.OTHER
                        && (detail.activityName() == null || detail.activityName().isBlank()))
                    throw invalid("ACTIVITY_NAME_REQUIRED", "其他活动请填写名称");
            }
        } else if (request.endedAt() != null)
            throw invalid("INSTANT_RECORD_END_NOT_ALLOWED", "餐次和精力记录不填写结束时间");
    }

    private void saveDetail(long id, RecordCreate request) {
        Map<String, Object> values =
                map(
                        "user_id",
                        AuthContext.userId(),
                        "record_id",
                        id,
                        "detail_type",
                        request.recordType().name());
        switch (request.detail()) {
            case SleepDetailInput d ->
                    values.putAll(
                            map(
                                    "sleep_start_at",
                                    request.startedAt(),
                                    "wake_at",
                                    request.endedAt(),
                                    "elapsed_minutes",
                                    Duration.between(request.startedAt(), request.endedAt())
                                            .toMinutes(),
                                    "rested_level",
                                    d.restedLevel(),
                                    "sleep_kind",
                                    d.sleepKind() == null ? "MAIN" : d.sleepKind(),
                                    "measurement_method",
                                    "SELF_REPORTED",
                                    "interruption_note_cipher",
                                    crypto.encrypt(d.interruptionNote()),
                                    "encryption_key_ref",
                                    d.interruptionNote() == null ? null : crypto.keyRef()));
            case ActivityDetailInput d ->
                    values.putAll(
                            map(
                                    "activity_type",
                                    d.activityType().name(),
                                    "custom_activity_name",
                                    d.activityName(),
                                    "actual_duration_minutes",
                                    d.actualDurationMinutes(),
                                    "duration_method",
                                    "SELF_REPORTED",
                                    "perceived_intensity",
                                    d.intensity() == null ? null : d.intensity().name(),
                                    "distance_meters",
                                    d.distanceMeters(),
                                    "distance_method",
                                    d.distanceMeters() == null ? null : "SELF_REPORTED",
                                    "location_label_cipher",
                                    crypto.encrypt(d.locationLabel()),
                                    "encryption_key_ref",
                                    d.locationLabel() == null ? null : crypto.keyRef()));
            case MealDetailInput d ->
                    values.putAll(
                            map(
                                    "meal_type",
                                    d.mealType().name(),
                                    "description_cipher",
                                    crypto.encrypt(d.description()),
                                    "encryption_key_ref",
                                    crypto.keyRef(),
                                    "vegetable_present",
                                    d.vegetablePresent(),
                                    "fruit_present",
                                    d.fruitPresent(),
                                    "protein_food_present",
                                    d.proteinFoodPresent(),
                                    "whole_grain_present",
                                    d.wholeGrainPresent(),
                                    "satiety_level",
                                    d.satietyLevel(),
                                    "meal_context",
                                    d.mealContext() == null ? null : d.mealContext().name()));
            case EnergyDetailInput d ->
                    values.putAll(
                            map(
                                    "energy_level",
                                    d.energyLevel(),
                                    "scale_version",
                                    1,
                                    "context_code",
                                    d.contextCode() == null ? null : d.contextCode().name(),
                                    "description_cipher",
                                    crypto.encrypt(d.description()),
                                    "encryption_key_ref",
                                    d.description() == null ? null : crypto.keyRef(),
                                    "reported_method",
                                    requestSourceMethod(request)));
        }
        store.insert(detailTable(request.recordType()), values);
    }

    private String requestSourceMethod(RecordCreate request) {
        return "SELF_REPORT";
    }

    private RecordView view(Map<String, Object> row) {
        long id = number(row, "id");
        RecordType type = RecordType.valueOf(text(row, "record_type"));
        Map<String, Object> d =
                store.one(
                        "SELECT * FROM " + detailTable(type) + " WHERE record_id=? AND user_id=?",
                        id,
                        AuthContext.userId());
        DetailView detail =
                switch (type) {
                    case SLEEP ->
                            new SleepDetailView(
                                    "SLEEP",
                                    integer(d, "elapsed_minutes"),
                                    nullableInt(d, "rested_level"),
                                    text(d, "sleep_kind"),
                                    crypto.decrypt(d.get("interruption_note_cipher")),
                                    store.count(
                                                    "SELECT COUNT(*) FROM health_record WHERE"
                                                        + " user_id=? AND record_type='SLEEP' AND"
                                                        + " deleted_at IS NULL AND id<>? AND"
                                                        + " started_at<? AND ended_at>?",
                                                    AuthContext.userId(),
                                                    id,
                                                    row.get("ended_at"),
                                                    row.get("started_at"))
                                            > 0,
                                    text(d, "measurement_method"));
                    case ACTIVITY ->
                            new ActivityDetailView(
                                    "ACTIVITY",
                                    text(d, "activity_type"),
                                    integer(d, "actual_duration_minutes"),
                                    text(d, "perceived_intensity"),
                                    text(d, "custom_activity_name"),
                                    d.get("distance_meters") == null
                                            ? null
                                            : new BigDecimal(d.get("distance_meters").toString()),
                                    crypto.decrypt(d.get("location_label_cipher")),
                                    text(d, "duration_method"));
                    case MEAL ->
                            new MealDetailView(
                                    "MEAL",
                                    text(d, "meal_type"),
                                    crypto.decrypt(d.get("description_cipher")),
                                    nullableBool(d, "vegetable_present"),
                                    nullableBool(d, "fruit_present"),
                                    nullableBool(d, "protein_food_present"),
                                    nullableBool(d, "whole_grain_present"),
                                    nullableInt(d, "satiety_level"),
                                    text(d, "meal_context"));
                    case ENERGY ->
                            new EnergyDetailView(
                                    "ENERGY",
                                    integer(d, "energy_level"),
                                    List.of("很低", "较低", "一般", "较好", "很好")
                                            .get(integer(d, "energy_level") - 1),
                                    text(d, "context_code"),
                                    crypto.decrypt(d.get("description_cipher")),
                                    integer(d, "scale_version"));
                };
        List<Long> tags =
                store
                        .query(
                                "SELECT tag_id FROM record_tag_relation WHERE user_id=? AND"
                                        + " record_id=? ORDER BY tag_id",
                                AuthContext.userId(),
                                id)
                        .stream()
                        .map(r -> number(r, "tag_id"))
                        .toList();
        List<AttachmentView> attachments =
                store
                        .query(
                                "SELECT a.*,f.mime_type,f.byte_size FROM record_attachment a JOIN"
                                        + " file_object f ON f.id=a.file_id AND f.user_id=a.user_id"
                                        + " WHERE a.user_id=? AND a.record_id=? AND f.deleted_at IS"
                                        + " NULL ORDER BY a.sort_order,a.id",
                                AuthContext.userId(),
                                id)
                        .stream()
                        .map(
                                a ->
                                        new AttachmentView(
                                                number(a, "id"),
                                                number(row, "row_version"),
                                                number(a, "file_id"),
                                                crypto.decrypt(a.get("caption_cipher")),
                                                integer(a, "sort_order"),
                                                text(a, "mime_type"),
                                                integer(a, "byte_size")))
                        .toList();
        return new RecordView(
                id,
                number(row, "row_version"),
                UUID.fromString(text(row, "client_record_id")),
                type,
                instant(row, "started_at"),
                instant(row, "ended_at"),
                date(row, "record_local_date"),
                text(row, "record_time_zone"),
                integer(row, "utc_offset_minutes"),
                text(row, "source_type"),
                row.get("source_connection_id") == null
                        ? null
                        : number(row, "source_connection_id"),
                text(row, "quality_status"),
                text(row, "invalid_reason"),
                crypto.decrypt(row.get("notes_cipher")),
                detail,
                tags,
                attachments,
                instant(row, "created_at"),
                instant(row, "updated_at"),
                instant(row, "deleted_at"));
    }

    private RecordTagView tagView(Map<String, Object> row) {
        return new RecordTagView(
                number(row, "id"),
                number(row, "row_version"),
                crypto.decrypt(row.get("name_cipher")),
                text(row, "color_token"),
                integer(row, "sort_order"));
    }

    private void validateTagIds(List<String> ids) {
        if (ids == null) return;
        if (ids.size() > 10 || new HashSet<>(ids).size() != ids.size())
            throw invalid("INVALID_TAG_SET", "标签最多10个且不能重复");
        for (String id : ids) store.owned("record_tag", Ids.parse(id), AuthContext.userId());
    }

    private void replaceTagRelations(long record, List<String> ids) {
        store.execute(
                "DELETE FROM record_tag_relation WHERE user_id=? AND record_id=?",
                AuthContext.userId(),
                record);
        if (ids != null)
            for (String tag : ids)
                store.insert(
                        "record_tag_relation",
                        map(
                                "user_id",
                                AuthContext.userId(),
                                "record_id",
                                record,
                                "tag_id",
                                Ids.parse(tag),
                                "assigned_source",
                                "MANUAL",
                                "operation_id",
                                RequestContext.idempotencyKey()));
    }

    private void assertExternalAuthorization(Map<String, Object> row) {
        if ("EXTERNAL".equals(text(row, "source_type"))) {
            Map<String, Object> connection =
                    store.owned(
                            "external_data_connection",
                            number(row, "source_connection_id"),
                            AuthContext.userId());
            if (!"ACTIVE".equals(text(connection, "status")))
                throw BusinessException.conflict(
                        "SOURCE_AUTHORIZATION_REVOKED", "来源授权已撤回，不能恢复或改写来源事实");
        }
    }

    private void syncRecord(long id, long version) {
        Map<String, Object> projection = mapper.convertValue(get(id), new TypeReference<>() {});
        mutations.upsert("RECORD", id, version, projection);
    }

    private void invalidate(long id, Set<LocalDate> days) {
        for (LocalDate day : days) {
            store.execute(
                    "UPDATE daily_stat SET status='STALE',updated_at=? WHERE user_id=? AND"
                            + " stat_local_date=?",
                    utc(Instant.now()),
                    AuthContext.userId(),
                    day);
            store.execute(
                    "UPDATE weekly_review SET status='STALE',updated_at=? WHERE user_id=? AND"
                            + " week_start_date<=? AND week_end_date>=? AND deleted_at IS NULL",
                    utc(Instant.now()),
                    AuthContext.userId(),
                    day,
                    day);
        }
        mutations.outbox(
                "STAT_INVALIDATED",
                "RECORD",
                id,
                null,
                map("dates", days.stream().map(LocalDate::toString).sorted().toList()));
    }

    private byte[] tagHash(String name) {
        return crypto.hmac(
                "record-tag:" + AuthContext.userId(),
                Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT));
    }

    private String cleanName(String name) {
        String clean = Normalizer.normalize(name.strip(), Normalizer.Form.NFKC);
        if (clean.isBlank() || clean.length() > 24) throw invalid("INVALID_TAG_NAME", "标签名长度不符");
        return clean;
    }

    private ZoneId userZone() {
        return ZoneId.of(
                text(
                        store.one(
                                "SELECT time_zone FROM user_profile WHERE user_id=?",
                                AuthContext.userId()),
                        "time_zone"));
    }

    public static void requireRange(LocalDate from, LocalDate to, int maximumDays) {
        if (from == null
                || to == null
                || from.isAfter(to)
                || ChronoUnit.DAYS.between(from, to) + 1 > maximumDays)
            throw invalid("INVALID_DATE_RANGE", "日期范围无效或过长");
    }

    private static String detailTable(RecordType type) {
        return switch (type) {
            case SLEEP -> "sleep_record_detail";
            case ACTIVITY -> "activity_record_detail";
            case MEAL -> "meal_record_detail";
            case ENERGY -> "energy_record_detail";
        };
    }

    private static Integer nullableInt(Map<String, Object> row, String column) {
        return row.get(column) == null ? null : integer(row, column);
    }

    private static Boolean nullableBool(Map<String, Object> row, String column) {
        return row.get(column) == null ? null : bool(row, column);
    }

    private static BusinessException invalid(String code, String message) {
        return BusinessException.badRequest(code, message);
    }
}
