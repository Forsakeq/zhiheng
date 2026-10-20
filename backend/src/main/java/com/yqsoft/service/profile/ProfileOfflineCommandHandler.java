package com.yqsoft.service.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.persistence.OfflineCommandHandler;
import com.yqsoft.resource.common.MutationResult;
import com.yqsoft.resource.common.ValidationSupport;
import com.yqsoft.resource.profile.ProfileDtos.*;

import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class ProfileOfflineCommandHandler implements OfflineCommandHandler {
    private final ProfileService service;
    private final ValidationSupport validation;

    public ProfileOfflineCommandHandler(ProfileService service, ValidationSupport validation) {
        this.service = service;
        this.validation = validation;
    }

    @Override
    public Set<String> operations() {
        return Set.of(
                "GOAL_CREATE",
                "GOAL_PATCH",
                "GOAL_CLOSE",
                "AVAILABILITY_CREATE",
                "AVAILABILITY_REPLACE",
                "AVAILABILITY_DELETE",
                "EXCEPTION_PUT",
                "EXCEPTION_DELETE");
    }

    @Override
    public MutationResult execute(String code, Long id, JsonNode payload) {
        return switch (code) {
            case "GOAL_CREATE" -> {
                requireCreate(id);
                yield service.createGoal(validation.convert(payload, GoalCreate.class));
            }
            case "GOAL_PATCH" ->
                    service.updateGoal(requireId(id), validation.convert(payload, GoalPatch.class));
            case "GOAL_CLOSE" ->
                    service.closeGoal(requireId(id), validation.convert(payload, GoalClose.class));
            case "AVAILABILITY_CREATE" -> {
                requireCreate(id);
                yield service.createAvailability(
                        validation.convert(payload, AvailabilityRuleInput.class));
            }
            case "AVAILABILITY_REPLACE" ->
                    service.replaceAvailability(
                            requireId(id),
                            validation.convert(payload, AvailabilityRuleReplace.class));
            case "AVAILABILITY_DELETE" ->
                    service.deleteAvailability(
                            requireId(id), validation.convert(payload, VersionBody.class));
            case "EXCEPTION_PUT" -> {
                requireCreate(id);
                ExceptionSyncPut input = validation.convert(payload, ExceptionSyncPut.class);
                yield service.putException(input.date(), input.input());
            }
            case "EXCEPTION_DELETE" -> {
                requireCreate(id);
                ExceptionSyncDelete input = validation.convert(payload, ExceptionSyncDelete.class);
                yield service.deleteException(
                        input.date(), new VersionBody(input.expectedRowVersion()));
            }
            default -> throw BusinessException.badRequest("UNSUPPORTED_OPERATION", "不支持的离线操作");
        };
    }

    private long requireId(Long id) {
        if (id == null || id <= 0)
            throw BusinessException.badRequest("ENTITY_ID_REQUIRED", "该操作需要对象编号");
        return id;
    }

    private void requireCreate(Long id) {
        if (id != null)
            throw BusinessException.badRequest("UNEXPECTED_ENTITY_ID", "该操作不能指定entityId");
    }
}
