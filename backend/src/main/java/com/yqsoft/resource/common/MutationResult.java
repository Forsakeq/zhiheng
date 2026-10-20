package com.yqsoft.resource.common;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Map;

public record MutationResult(
        String resourceType,
        String id,
        String rowVersion,
        String changeSeq,
        String operationId,
        boolean replayed,
        @JsonIgnore Map<String, Object> extra) {

    @JsonAnyGetter
    public Map<String, Object> additionalFields() {
        return extra;
    }

    public static MutationResult of(String type, long id, long rowVersion) {
        return new MutationResult(
                type, Long.toString(id), Long.toString(rowVersion), null, null, false, Map.of());
    }

    public MutationResult withExtra(Map<String, Object> values) {
        return new MutationResult(
                resourceType, id, rowVersion, changeSeq, operationId, replayed, values);
    }
}
