package com.yqsoft.controller.auth;

import com.yqsoft.exception.BusinessException;
import com.yqsoft.resource.auth.AuthDtos.*;
import com.yqsoft.resource.auth.AuthViews.*;
import com.yqsoft.resource.common.*;
import com.yqsoft.security.PublicEndpoint;
import com.yqsoft.service.auth.AuthService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@Validated
public class AuthController {
    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    @PublicEndpoint
    @PostMapping("/verifications")
    public ResponseEntity<ApiResponse<VerificationSendView>> verification(
            @Valid @RequestBody VerificationSend request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.sendVerification(request)));
    }

    @PublicEndpoint
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<Tokens>> register(
            @Valid @RequestBody Register request,
            @RequestHeader(name = "X-Client-Platform", required = false) String platform) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.register(request, platform)));
    }

    @PublicEndpoint
    @PostMapping("/login")
    public ApiResponse<Tokens> login(
            @Valid @RequestBody Login request,
            @RequestHeader(name = "X-Client-Platform", required = false) String platform) {
        return ApiResponse.ok(service.login(request, platform));
    }

    @PublicEndpoint
    @PostMapping("/refresh")
    public ApiResponse<Tokens> refresh(@Valid @RequestBody Refresh request) {
        return ApiResponse.ok(service.refresh(request));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody(required = false) Logout request) {
        service.logout(request == null || request.scope() == null ? "CURRENT" : request.scope());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sessions")
    public ApiResponse<PageResult<SessionView>> sessions(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.ok(service.sessions(cursor, limit));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> revoke(@PathVariable String sessionId) {
        service.revokeSession(Ids.parse(sessionId));
        return ResponseEntity.noContent().build();
    }

    @PublicEndpoint
    @PostMapping("/password/reset")
    public ApiResponse<PasswordResetView> reset(@Valid @RequestBody ResetPassword request) {
        return ApiResponse.ok(service.resetPassword(request));
    }

    @PublicEndpoint
    @PostMapping("/external/exchange")
    public ApiResponse<Tokens> exchange(@Valid @RequestBody ExternalLogin request) {
        throw BusinessException.unavailable("FEATURE_NOT_ENABLED", "第三方身份登录尚未启用");
    }
}
