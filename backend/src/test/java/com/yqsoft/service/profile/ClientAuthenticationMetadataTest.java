package com.yqsoft.service.profile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.yqsoft.dao.SqlStore;
import com.yqsoft.resource.profile.ProfileViews.PolicyVersion;
import com.yqsoft.service.auth.AuthDeploymentSettings;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Optional;

class ClientAuthenticationMetadataTest {
    @Test
    void publicMetadataUsesTheSameRegistrationGateAndPublishedPolicyAsAuthentication() {
        SqlStore sql = mock(SqlStore.class);
        AuthDeploymentSettings settings = mock(AuthDeploymentSettings.class);
        when(sql.query(anyString(), eq("DEV"))).thenReturn(List.of());
        when(settings.localDevelopment()).thenReturn(true);
        when(settings.registrationEnabled()).thenReturn(true);
        when(settings.verificationDelivery()).thenReturn("CONSOLE");
        when(settings.servicePolicy())
                .thenReturn(
                        Optional.of(
                                new PolicyVersion(
                                        "SERVICE",
                                        "local-service-v1",
                                        "知衡本地服务说明",
                                        AuthDeploymentSettings.LOCAL_SERVICE_POLICY_PATH)));
        ClientConfigurationService service =
                new ClientConfigurationService(sql, new MockEnvironment(), settings);

        var configuration = service.configuration("ANDROID", "0.1.0", null);

        assertTrue(configuration.localDevelopment());
        assertTrue(configuration.authentication().registrationEnabled());
        assertEquals("CONSOLE", configuration.authentication().verificationDelivery());
        assertEquals("local-service-v1", configuration.policyVersions().getFirst().version());
        verify(settings).registrationEnabled();

        when(settings.registrationEnabled()).thenReturn(false);
        assertFalse(service.configuration("ANDROID", "0.1.0", null).authentication().registrationEnabled());
    }
}
