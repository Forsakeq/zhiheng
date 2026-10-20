package com.yqsoft.service.record;

import com.fasterxml.jackson.databind.JsonNode;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.persistence.OfflineCommandHandler;
import com.yqsoft.resource.common.MutationResult;
import com.yqsoft.resource.common.ValidationSupport;
import com.yqsoft.resource.record.RecordDtos.*;

import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class RecordOfflineHandler implements OfflineCommandHandler {
    private final RecordService records;
    private final ValidationSupport validation;

    public RecordOfflineHandler(RecordService records, ValidationSupport validation) {
        this.records = records;
        this.validation = validation;
    }

    public Set<String> operations() {
        return Set.of(
                "RECORD_CREATE",
                "RECORD_REPLACE",
                "RECORD_DELETE",
                "RECORD_RESTORE",
                "TAG_CREATE",
                "TAG_PATCH",
                "TAG_DELETE",
                "RECORD_TAGS_REPLACE");
    }

    public MutationResult execute(String code, Long id, JsonNode payload) {
        if (!Set.of("RECORD_CREATE", "TAG_CREATE").contains(code) && (id == null || id < 1))
            throw BusinessException.badRequest("ENTITY_ID_REQUIRED", "该离线命令需要对象编号");
        return switch (code) {
            case "RECORD_CREATE" -> records.create(validation.convert(payload, RecordCreate.class));
            case "RECORD_REPLACE" ->
                    records.replace(id, validation.convert(payload, RecordReplace.class));
            case "RECORD_DELETE" ->
                    records.delete(id, validation.convert(payload, VersionBody.class));
            case "RECORD_RESTORE" ->
                    records.restore(id, validation.convert(payload, VersionBody.class));
            case "TAG_CREATE" ->
                    records.createTag(validation.convert(payload, RecordTagCreate.class));
            case "TAG_PATCH" ->
                    records.updateTag(id, validation.convert(payload, RecordTagPatch.class));
            case "TAG_DELETE" ->
                    records.deleteTag(id, validation.convert(payload, VersionBody.class));
            case "RECORD_TAGS_REPLACE" ->
                    records.replaceTags(id, validation.convert(payload, RecordTagsPut.class));
            default -> throw BusinessException.badRequest("UNKNOWN_OPERATION", "离线命令不受支持");
        };
    }
}
