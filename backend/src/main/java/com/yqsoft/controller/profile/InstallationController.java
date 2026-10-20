package com.yqsoft.controller.profile;

import com.yqsoft.resource.common.*;
import com.yqsoft.resource.profile.InstallationDtos.*;
import com.yqsoft.resource.profile.ProfileDtos.VersionBody;
import com.yqsoft.resource.profile.ProfileViews.InstallationView;
import com.yqsoft.service.profile.InstallationService;

import jakarta.validation.Valid;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/installations")
public class InstallationController {
    private final InstallationService service;

    public InstallationController(InstallationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<InstallationView>> register(
            @Valid @RequestBody Register request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.register(request)));
    }

    @PatchMapping("/{installationId}")
    public ApiResponse<MutationResult> patch(
            @PathVariable String installationId, @Valid @RequestBody Patch request) {
        return ApiResponse.ok(service.update(Ids.parse(installationId), request));
    }

    @DeleteMapping("/{installationId}")
    public ApiResponse<MutationResult> delete(
            @PathVariable String installationId, @Valid @RequestBody VersionBody request) {
        return ApiResponse.ok(service.revoke(Ids.parse(installationId), request));
    }
}
