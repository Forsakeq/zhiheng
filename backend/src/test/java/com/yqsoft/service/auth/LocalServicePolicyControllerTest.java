package com.yqsoft.service.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.yqsoft.controller.profile.LocalServicePolicyController;
import com.yqsoft.dao.SqlStore;
import com.yqsoft.exception.BusinessException;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class LocalServicePolicyControllerTest {
    @Test
    void theLocalDocumentProvidesReadableSectionsAndMatchesTheRegistrationVersion() {
        AuthDeploymentSettings settings =
                new AuthDeploymentSettings(
                        mock(SqlStore.class), AuthDeploymentSettingsTest.localEnvironment());

        var document = new LocalServicePolicyController(settings).servicePolicy().data();

        assertEquals("SERVICE", document.purpose());
        assertEquals(settings.servicePolicy().orElseThrow().version(), document.version());
        assertEquals(5, document.sections().size());
        assertTrue(
                document.sections().stream()
                        .allMatch(
                                section ->
                                        !section.heading().isBlank()
                                                && !section.content().isBlank()));
        assertTrue(
                document.sections().stream()
                        .anyMatch(section -> section.content().contains("MySQL")));
        assertTrue(
                document.sections().stream()
                        .anyMatch(section -> section.content().contains("18周岁")));
        assertTrue(
                document.sections().stream()
                        .anyMatch(section -> section.content().contains("尚非项目正式发布")));
    }

    @Test
    void theLocalDocumentIsUnavailableOutsideLocalDev() {
        MockEnvironment environment = AuthDeploymentSettingsTest.localEnvironment();
        environment.setProperty("zhiheng.environment", "PROD");
        var controller =
                new LocalServicePolicyController(
                        new AuthDeploymentSettings(mock(SqlStore.class), environment));

        BusinessException exception = assertThrows(BusinessException.class, controller::servicePolicy);

        assertEquals(404, exception.getStatus());
    }

    @Test
    void publishingAnExternalServiceDocumentRemovesTheLocalFallback() {
        MockEnvironment environment = AuthDeploymentSettingsTest.localEnvironment();
        environment.setProperty("zhiheng.auth.service-policy-url", "https://example.invalid/service");
        var controller =
                new LocalServicePolicyController(
                        new AuthDeploymentSettings(mock(SqlStore.class), environment));

        assertThrows(BusinessException.class, controller::servicePolicy);
    }
}
