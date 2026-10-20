package com.yqsoft.controller.profile;

import com.yqsoft.resource.common.ApiResponse;
import com.yqsoft.resource.profile.ConfigurationView;
import com.yqsoft.security.PublicEndpoint;
import com.yqsoft.service.profile.ClientConfigurationService;

import jakarta.validation.constraints.*;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Validated
public class ClientConfigurationController {
    private final ClientConfigurationService service;

    public ClientConfigurationController(ClientConfigurationService service) {
        this.service = service;
    }

    @PublicEndpoint
    @GetMapping("/configuration")
    public ApiResponse<ConfigurationView> configuration(
            @RequestParam @NotBlank String platform,
            @RequestParam @NotBlank @Size(max = 32) String appVersion,
            @RequestParam(required = false) @Size(max = 64) String osVersion) {
        return ApiResponse.ok(service.configuration(platform, appVersion, osVersion));
    }
}
