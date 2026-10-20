package com.yqsoft.controller.privacy;

import com.yqsoft.resource.common.ApiResponse;
import com.yqsoft.resource.common.Ids;
import com.yqsoft.resource.privacy.PrivacyDtos;
import com.yqsoft.security.PublicEndpoint;
import com.yqsoft.service.privacy.PrivacyService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/privacy")
public class PrivacyController {

    private final PrivacyService service;

    public PrivacyController(PrivacyService service) {
        this.service = service;
    }

    @PostMapping("/requests/preview")
    public ApiResponse<?> preview(@Valid @RequestBody PrivacyDtos.Preview request) {
        return ApiResponse.ok(service.preview(request));
    }

    @PostMapping("/requests/{requestId}/confirm")
    public ResponseEntity<?> confirm(
            @PathVariable String requestId, @Valid @RequestBody PrivacyDtos.Confirm request) {
        return ResponseEntity.accepted()
                .body(ApiResponse.ok(service.confirm(Ids.parse(requestId), request)));
    }

    @GetMapping("/requests")
    public ApiResponse<?> list(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(service.list(cursor, limit));
    }

    @GetMapping("/requests/{requestId}")
    public ApiResponse<?> get(@PathVariable String requestId) {
        return ApiResponse.ok(service.get(Ids.parse(requestId)));
    }

    @PostMapping("/requests/{requestId}/cancel")
    public ApiResponse<?> cancel(
            @PathVariable String requestId, @Valid @RequestBody PrivacyDtos.VersionBody request) {
        return ApiResponse.ok(
                service.cancel(Ids.parse(requestId), Ids.parse(request.expectedRowVersion())));
    }

    @PublicEndpoint
    @PostMapping("/receipts/status")
    public ApiResponse<?> receipt(@Valid @RequestBody PrivacyDtos.Receipt request) {
        return ApiResponse.ok(service.receipt(request.receiptToken()));
    }

    @PostMapping("/requests/{requestId}/download")
    public ApiResponse<?> download(
            @PathVariable String requestId, @Valid @RequestBody PrivacyDtos.Download request) {
        return ApiResponse.ok(service.download(Ids.parse(requestId), request));
    }
}
