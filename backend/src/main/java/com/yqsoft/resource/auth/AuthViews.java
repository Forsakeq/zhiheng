package com.yqsoft.resource.auth;

import java.time.Instant;

public final class AuthViews {
    private AuthViews() {}

    public record VerificationSendView(
            long verificationId,
            Instant expiresAt,
            int resendAfterSeconds,
            String maskedDestination) {}

    public record Tokens(
            String accessToken,
            Instant accessExpiresAt,
            String refreshToken,
            Instant refreshExpiresAt,
            long sessionId,
            String sessionFamilyId,
            long generation,
            String audience,
            long installationId,
            long userId,
            long datasetEpoch,
            long securityVersion,
            String tokenType) {}

    public record SessionView(
            long id,
            String sessionFamilyId,
            long generation,
            String clientAudience,
            long installationId,
            String clientName,
            Instant createdAt,
            Instant lastSeenAt,
            Instant expiresAt,
            boolean current,
            String status) {}

    public record PasswordResetView(
            boolean passwordChanged, boolean allSessionsRevoked, boolean loginRequired) {}
}
