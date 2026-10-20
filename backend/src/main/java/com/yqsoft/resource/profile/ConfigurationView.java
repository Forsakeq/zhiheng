package com.yqsoft.resource.profile;

import java.time.Instant;
import java.util.List;

public record ConfigurationView(
        String apiVersion,
        String minimumAppVersion,
        Instant serverTime,
        boolean localDevelopment,
        AuthenticationView authentication,
        List<ProfileViews.PolicyVersion> policyVersions,
        List<FeatureView> features,
        List<String> supportedRecordTypes,
        List<String> supportedSyncOperationCodes) {
    public record AuthenticationView(boolean registrationEnabled, String verificationDelivery) {}

    public record FeatureView(
            String code, boolean enabled, String requiresConsent, String availabilityReason) {}
}
