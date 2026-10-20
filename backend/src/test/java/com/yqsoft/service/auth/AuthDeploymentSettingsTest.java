package com.yqsoft.service.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.yqsoft.dao.SqlStore;
import com.yqsoft.resource.common.Rows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Optional;

class AuthDeploymentSettingsTest {
    private final SqlStore sql = mock(SqlStore.class);

    @Test
    void localRegistrationUsesTheActualLocalDocumentWithoutChangingDatabaseFlags() {
        MockEnvironment environment = localEnvironment();
        AuthDeploymentSettings settings = new AuthDeploymentSettings(sql, environment);

        assertTrue(settings.registrationEnabled());
        assertTrue(settings.localDevelopment());
        assertEquals("CONSOLE", settings.verificationDelivery());
        assertEquals(
                AuthDeploymentSettings.LOCAL_SERVICE_POLICY_PATH,
                settings.servicePolicy().orElseThrow().documentUrl());
        verifyNoInteractions(sql);
    }

    @Test
    void explicitRegistrationDisableAlwaysWinsOverTheLocalOverride() {
        MockEnvironment environment = localEnvironment();
        environment.setProperty("zhiheng.auth.registration-enabled", "false");

        assertFalse(new AuthDeploymentSettings(sql, environment).registrationEnabled());
        verifyNoInteractions(sql);
    }

    @Test
    void defaultLocalProfileIsRecognizedButMixedProdProfileIsNeverLocalDevelopment() {
        MockEnvironment environment = localEnvironment();
        environment.setActiveProfiles();
        environment.setDefaultProfiles("local");
        assertTrue(AuthDeploymentSettings.isLocalDevelopment(environment));

        environment.setActiveProfiles("local", "prod");
        assertFalse(AuthDeploymentSettings.isLocalDevelopment(environment));
        assertThrows(
                IllegalStateException.class,
                () -> AuthDeploymentSettings.verificationDelivery(environment));
    }

    @Test
    void productionRequiresBothThePublishedHttpsDocumentAndTheDatabaseRegistrationGate() {
        MockEnvironment environment = productionEnvironment();
        AuthDeploymentSettings settings = new AuthDeploymentSettings(sql, environment);
        when(sql.find(anyString(), eq("PROD"), eq("auth.registration_enabled")))
                .thenReturn(Optional.of(Rows.map("value_json", "{\"booleanValue\":false}")));

        assertFalse(settings.registrationEnabled());
        when(sql.find(anyString(), eq("PROD"), eq("auth.registration_enabled")))
                .thenReturn(Optional.of(Rows.map("value_json", "{\"booleanValue\":true}")));
        assertTrue(settings.registrationEnabled());

        environment.setProperty("zhiheng.auth.service-policy-url", "http://example.invalid/service");
        assertFalse(settings.registrationEnabled());
        assertTrue(settings.servicePolicy().isEmpty());
    }

    @Test
    void localOverrideCannotBypassDatabaseGateInAnotherProfileOrDeploymentEnvironment() {
        MockEnvironment environment = localEnvironment();
        environment.setProperty("zhiheng.auth.service-policy-url", "https://example.invalid/service");
        environment.setProperty("zhiheng.auth.verification-delivery", "SMTP");
        environment.setProperty("zhiheng.environment", "TEST");

        assertFalse(new AuthDeploymentSettings(sql, environment).registrationEnabled());
        verify(sql).find(anyString(), eq("TEST"), eq("auth.registration_enabled"));

        environment.setProperty("zhiheng.environment", "DEV");
        environment.setActiveProfiles("integration");
        assertFalse(new AuthDeploymentSettings(sql, environment).registrationEnabled());
        verify(sql).find(anyString(), eq("DEV"), eq("auth.registration_enabled"));
    }

    @Test
    void consoleDeliveryIsRejectedOutsideLocalDevAndInvalidModesAreRejected() {
        MockEnvironment productionEnvironment = productionEnvironment();
        productionEnvironment.setProperty("zhiheng.auth.verification-delivery", "CONSOLE");
        assertThrows(
                IllegalStateException.class,
                () -> new VerificationSenderConfiguration(productionEnvironment));

        MockEnvironment environment = localEnvironment();
        environment.setProperty("zhiheng.environment", "PROD");
        MockEnvironment mismatchedEnvironment = environment;
        assertThrows(
                IllegalStateException.class,
                () -> new VerificationSenderConfiguration(mismatchedEnvironment));

        MockEnvironment invalidEnvironment = localEnvironment();
        invalidEnvironment.setProperty("zhiheng.auth.verification-delivery", "ALWAYS_123456");
        assertThrows(
                IllegalStateException.class,
                () -> new VerificationSenderConfiguration(invalidEnvironment));
    }

    @Test
    void blankVersionOrInvalidDocumentNeverProducesAConfirmablePolicy() {
        MockEnvironment environment = localEnvironment();
        environment.setProperty("zhiheng.auth.service-policy-version", "");
        AuthDeploymentSettings settings = new AuthDeploymentSettings(sql, environment);
        assertFalse(settings.registrationEnabled());
        assertTrue(settings.servicePolicy().isEmpty());

        environment.setProperty("zhiheng.auth.service-policy-version", "v2");
        environment.setProperty(
                "zhiheng.auth.service-policy-url", "https://user:secret@example.invalid/service");
        assertTrue(settings.servicePolicy().isEmpty());
    }

    static MockEnvironment localEnvironment() {
        MockEnvironment environment =
                new MockEnvironment()
                        .withProperty("zhiheng.environment", "DEV")
                        .withProperty("zhiheng.auth.registration-enabled", "true")
                        .withProperty("zhiheng.auth.local-development-registration", "true")
                        .withProperty("zhiheng.auth.verification-delivery", "CONSOLE")
                        .withProperty("zhiheng.auth.service-policy-version", "local-service-v1");
        environment.setActiveProfiles("local");
        return environment;
    }

    private MockEnvironment productionEnvironment() {
        MockEnvironment environment =
                new MockEnvironment()
                        .withProperty("zhiheng.environment", "PROD")
                        .withProperty("zhiheng.auth.registration-enabled", "true")
                        .withProperty("zhiheng.auth.local-development-registration", "true")
                        .withProperty("zhiheng.auth.verification-delivery", "SMTP")
                        .withProperty("zhiheng.auth.service-policy-version", "service-v1")
                        .withProperty("zhiheng.auth.service-policy-url", "https://example.invalid/service");
        environment.setActiveProfiles("prod");
        return environment;
    }
}
