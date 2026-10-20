package com.yqsoft.service.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.persistence.OfflineCommandHandler;
import com.yqsoft.resource.common.MutationResult;
import com.yqsoft.resource.common.ValidationSupport;
import com.yqsoft.resource.plan.PlanDtos.*;

import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class TaskOfflineHandler implements OfflineCommandHandler {
    private final PlanService plans;
    private final ValidationSupport validation;

    public TaskOfflineHandler(PlanService plans, ValidationSupport validation) {
        this.plans = plans;
        this.validation = validation;
    }

    public Set<String> operations() {
        return Set.of("TASK_COMPLETE", "TASK_SKIP", "TASK_REOPEN", "TASK_RECORD_LINKS_REPLACE");
    }

    public MutationResult execute(String code, Long id, JsonNode payload) {
        if (id == null || id < 1)
            throw BusinessException.badRequest("ENTITY_ID_REQUIRED", "任务命令需要任务编号");
        return switch (code) {
            case "TASK_COMPLETE" ->
                    plans.completeTask(id, validation.convert(payload, TaskStateCommand.class));
            case "TASK_SKIP" ->
                    plans.skipTask(id, validation.convert(payload, TaskStateCommand.class));
            case "TASK_REOPEN" ->
                    plans.reopenTask(id, validation.convert(payload, TaskReopen.class));
            case "TASK_RECORD_LINKS_REPLACE" ->
                    plans.recordLinks(id, validation.convert(payload, TaskRecordLinksPut.class));
            default -> throw BusinessException.badRequest("UNKNOWN_OPERATION", "离线命令不受支持");
        };
    }
}
