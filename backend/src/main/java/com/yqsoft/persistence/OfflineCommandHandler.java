package com.yqsoft.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.yqsoft.resource.common.MutationResult;

import java.util.Set;

public interface OfflineCommandHandler {

    Set<String> operations();

    MutationResult execute(String operationCode, Long entityId, JsonNode payload);
}
