package com.yqsoft.controller.sync;

import com.yqsoft.resource.common.ApiResponse;
import com.yqsoft.resource.common.Ids;
import com.yqsoft.resource.sync.SyncDtos;
import com.yqsoft.service.sync.SyncService;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/sync")
public class SyncController {

    private final SyncService service;

    public SyncController(SyncService service) {
        this.service = service;
    }

    @PostMapping("/bootstrap")
    public ApiResponse<?> begin(@Valid @RequestBody SyncDtos.BootstrapBegin request) {
        return ApiResponse.ok(service.begin(request));
    }

    @GetMapping("/bootstrap/{bootstrapId}/entities")
    public ApiResponse<?> entities(
            @PathVariable String bootstrapId,
            @RequestParam String installationId,
            @RequestParam String datasetEpoch,
            @RequestParam String entityType,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(
                service.page(
                        bootstrapId,
                        Ids.parse(installationId),
                        Ids.parse(datasetEpoch),
                        entityType,
                        cursor,
                        limit));
    }

    @GetMapping("/changes")
    public ApiResponse<?> changes(
            @RequestParam String installationId,
            @RequestParam String datasetEpoch,
            @RequestParam String afterSeq,
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(required = false) String bootstrapId) {
        return ApiResponse.ok(
                service.changes(
                        Ids.parse(installationId),
                        Ids.parse(datasetEpoch),
                        Ids.nonNegative(afterSeq),
                        limit,
                        bootstrapId));
    }

    @PostMapping("/operations")
    public ApiResponse<?> operations(@Valid @RequestBody SyncDtos.Batch request) {
        return ApiResponse.ok(service.operations(request));
    }

    @PostMapping("/ack")
    public ApiResponse<Map<String, Object>> acknowledge(@Valid @RequestBody SyncDtos.Ack request) {
        return ApiResponse.ok(service.acknowledge(request));
    }
}
