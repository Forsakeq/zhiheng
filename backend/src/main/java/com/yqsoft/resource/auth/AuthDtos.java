package com.yqsoft.resource.auth;

import jakarta.validation.constraints.*;

import java.util.UUID;

public final class AuthDtos {
    private AuthDtos() {}

    public record VerificationSend(
            @NotBlank @Email @Size(max = 254) String destination,
            @NotBlank @Pattern(regexp = "EMAIL") String channel,
            @NotBlank @Pattern(regexp = "REGISTER|RESET_PASSWORD|REAUTH") String purpose,
            @NotBlank @Pattern(regexp = "MOBILE|ADMIN") String clientAudience,
            @Size(max = 2048) String challengeToken) {}

    public record Register(
            @NotBlank @Email @Size(max = 254) String account,
            @NotBlank @Size(min = 12, max = 128) String password,
            @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String verificationId,
            @NotBlank @Pattern(regexp = "[0-9]{6}") String verificationCode,
            @NotNull @AssertTrue Boolean adultConfirmed,
            @NotBlank @Size(max = 32) String policyVersion,
            @NotNull @AssertTrue Boolean termsAccepted,
            @NotNull UUID installationKey,
            @NotBlank @Size(max = 64) String timeZone,
            @Size(min = 1, max = 40) String nickname,
            @Pattern(regexp = "MOBILE") String clientAudience,
            @Pattern(regexp = "ANDROID|HARMONYOS|IOS") String platform) {}

    public record Login(
            @NotBlank @Email @Size(max = 254) String account,
            @NotBlank @Size(max = 128) String password,
            @NotNull UUID installationKey,
            @NotBlank @Size(max = 80) String clientName,
            @NotBlank @Pattern(regexp = "MOBILE|ADMIN") String clientAudience,
            @Size(max = 2048) String challengeToken,
            @Pattern(regexp = "ANDROID|HARMONYOS|IOS|WEB") String platform) {}

    public record Refresh(
            @NotBlank @Size(min = 32, max = 512) String refreshToken,
            @NotNull UUID installationKey,
            @NotBlank @Pattern(regexp = "MOBILE|ADMIN") String clientAudience) {}

    public record Logout(@Pattern(regexp = "CURRENT|ALL") String scope) {}

    public record ResetPassword(
            @NotBlank @Email @Size(max = 254) String account,
            @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String verificationId,
            @NotBlank @Pattern(regexp = "[0-9]{6}") String verificationCode,
            @NotBlank @Size(min = 12, max = 128) String newPassword,
            @NotBlank @Pattern(regexp = "MOBILE|ADMIN") String clientAudience) {}

    public record ReauthProof(
            @NotBlank @Pattern(regexp = "PASSWORD|VERIFICATION") String method,
            @Size(min = 1, max = 128) String password,
            @Pattern(regexp = "[1-9][0-9]{0,18}") String verificationId,
            @Pattern(regexp = "[0-9]{6}") String verificationCode) {}

    public record ExternalLogin(
            @NotBlank @Pattern(regexp = "HUAWEI") String provider,
            @NotBlank @Size(max = 2048) String authorizationCode,
            @NotBlank @Size(max = 256) String state,
            @NotBlank @Size(min = 43, max = 128) String codeVerifier,
            @NotBlank @Size(max = 512) String redirectUri,
            @NotNull UUID installationKey,
            @Pattern(regexp = "MOBILE") String clientAudience) {}
}
