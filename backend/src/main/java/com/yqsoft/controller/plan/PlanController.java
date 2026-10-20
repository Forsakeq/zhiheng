package com.yqsoft.controller.plan;

import com.yqsoft.resource.common.*;
import com.yqsoft.resource.plan.PlanDtos.*;
import com.yqsoft.service.plan.PlanService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class PlanController {
    private final PlanService plans;

    public PlanController(PlanService plans) {
        this.plans = plans;
    }

    @GetMapping("/plans")
    public ApiResponse<PageResult<PlanView>> listPlans(@Valid @ModelAttribute PlanQuery query) {
        return ApiResponse.ok(plans.listPlans(query));
    }

    @PostMapping("/plans")
    public ResponseEntity<ApiResponse<MutationResult>> createPlan(
            @Valid @RequestBody PlanCreate request) {
        return ResponseEntity.status(201).body(ApiResponse.ok(plans.createPlan(request)));
    }

    @GetMapping("/plans/{planId}")
    public ApiResponse<PlanDetailView> getPlan(@PathVariable String planId) {
        return ApiResponse.ok(plans.getPlan(Ids.parse(planId)));
    }

    @PostMapping("/plans/{planId}/archive")
    public ApiResponse<MutationResult> archivePlan(
            @PathVariable String planId, @Valid @RequestBody PlanArchive request) {
        return ApiResponse.ok(plans.archive(Ids.parse(planId), request));
    }

    @GetMapping("/plans/{planId}/versions")
    public ApiResponse<PageResult<PlanVersionView>> listPlanVersions(
            @PathVariable String planId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") Integer limit) {
        return ApiResponse.ok(plans.listVersions(Ids.parse(planId), cursor, limit));
    }

    @GetMapping("/plans/{planId}/versions/{versionId}")
    public ApiResponse<PlanVersionDetail> getPlanVersion(
            @PathVariable String planId, @PathVariable String versionId) {
        return ApiResponse.ok(plans.getVersion(Ids.parse(planId), Ids.parse(versionId)));
    }

    @PostMapping("/plans/{planId}/versions")
    public ResponseEntity<ApiResponse<MutationResult>> createPlanVersion(
            @PathVariable String planId, @Valid @RequestBody PlanVersionCreate request) {
        return ResponseEntity.status(201)
                .body(ApiResponse.ok(plans.createVersion(Ids.parse(planId), request)));
    }

    @PutMapping("/plans/{planId}/versions/{versionId}")
    public ApiResponse<MutationResult> replacePlanVersionDraft(
            @PathVariable String planId,
            @PathVariable String versionId,
            @Valid @RequestBody PlanVersionReplace request) {
        return ApiResponse.ok(
                plans.replaceVersion(Ids.parse(planId), Ids.parse(versionId), request));
    }

    @PostMapping("/plans/{planId}/versions/{versionId}/validate")
    public ApiResponse<PlanValidationView> validatePlanVersion(
            @PathVariable String planId,
            @PathVariable String versionId,
            @Valid @RequestBody PlanValidationRequest request) {
        return ApiResponse.ok(plans.validate(Ids.parse(planId), Ids.parse(versionId), request));
    }

    @PostMapping("/plans/{planId}/versions/{versionId}/preview")
    public ApiResponse<PlanPreview> previewPlanActivation(
            @PathVariable String planId,
            @PathVariable String versionId,
            @Valid @RequestBody PlanScopeRequest request) {
        return ApiResponse.ok(plans.preview(Ids.parse(planId), Ids.parse(versionId), request));
    }

    @PostMapping("/plans/{planId}/versions/{versionId}/activate")
    public ApiResponse<MutationResult> activatePlanVersion(
            @PathVariable String planId,
            @PathVariable String versionId,
            @Valid @RequestBody PlanActivate request) {
        return ApiResponse.ok(plans.activate(Ids.parse(planId), Ids.parse(versionId), request));
    }

    @PostMapping("/plans/{planId}/versions/{versionId}/restore")
    public ResponseEntity<ApiResponse<MutationResult>> copyHistoricalPlanVersion(
            @PathVariable String planId,
            @PathVariable String versionId,
            @Valid @RequestBody PlanRestore request) {
        return ResponseEntity.status(201)
                .body(
                        ApiResponse.ok(
                                plans.restoreVersion(
                                        Ids.parse(planId), Ids.parse(versionId), request)));
    }

    @PostMapping("/plans/{planId}/versions/{versionId}/discard")
    public ApiResponse<MutationResult> discardPlanVersion(
            @PathVariable String planId,
            @PathVariable String versionId,
            @Valid @RequestBody VersionBody request) {
        return ApiResponse.ok(plans.discard(Ids.parse(planId), Ids.parse(versionId), request));
    }

    @GetMapping("/tasks")
    public ApiResponse<PageResult<TaskView>> listTasks(@Valid @ModelAttribute TaskQuery query) {
        return ApiResponse.ok(plans.listTasks(query));
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<TaskDetailView> getTask(@PathVariable String taskId) {
        return ApiResponse.ok(plans.taskDetail(Ids.parse(taskId)));
    }

    @PostMapping("/tasks/{taskId}/complete")
    public ApiResponse<MutationResult> completeTask(
            @PathVariable String taskId, @Valid @RequestBody TaskStateCommand request) {
        return ApiResponse.ok(plans.completeTask(Ids.parse(taskId), request));
    }

    @PostMapping("/tasks/{taskId}/skip")
    public ApiResponse<MutationResult> skipTask(
            @PathVariable String taskId, @Valid @RequestBody TaskStateCommand request) {
        return ApiResponse.ok(plans.skipTask(Ids.parse(taskId), request));
    }

    @PostMapping("/tasks/{taskId}/reopen")
    public ApiResponse<MutationResult> reopenTask(
            @PathVariable String taskId, @Valid @RequestBody TaskReopen request) {
        return ApiResponse.ok(plans.reopenTask(Ids.parse(taskId), request));
    }

    @GetMapping("/tasks/{taskId}/events")
    public ApiResponse<PageResult<TaskEventView>> listTaskEvents(
            @PathVariable String taskId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") Integer limit) {
        return ApiResponse.ok(plans.events(Ids.parse(taskId), cursor, limit));
    }

    @PutMapping("/tasks/{taskId}/record-links")
    public ApiResponse<MutationResult> replaceTaskRecordLinks(
            @PathVariable String taskId, @Valid @RequestBody TaskRecordLinksPut request) {
        return ApiResponse.ok(plans.recordLinks(Ids.parse(taskId), request));
    }

    @PostMapping("/plans/{planId}/rule-commands")
    public ResponseEntity<ApiResponse<MutationResult>> commandPlanRule(
            @PathVariable String planId, @Valid @RequestBody PlanRuleCommand request) {
        return ResponseEntity.status(201)
                .body(ApiResponse.ok(plans.ruleCommand(Ids.parse(planId), request)));
    }
}
