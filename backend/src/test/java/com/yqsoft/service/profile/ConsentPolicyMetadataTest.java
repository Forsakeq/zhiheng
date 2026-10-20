package com.yqsoft.service.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yqsoft.dao.SqlStore;
import com.yqsoft.persistence.MutationExecutor;
import com.yqsoft.security.AuthContext;
import com.yqsoft.security.AuthenticatedUser;
import com.yqsoft.service.common.CryptoService;
import com.yqsoft.service.common.CursorService;
import com.yqsoft.service.privacy.PrivacyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConsentPolicyMetadataTest {
    private final SqlStore sql = mock(SqlStore.class);
    private final MockEnvironment environment = new MockEnvironment();
    private ProfileService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        ObjectProvider<PrivacyService> privacy = mock(ObjectProvider.class);
        service = new ProfileService(sql, mock(MutationExecutor.class), mock(CryptoService.class),
                mock(CursorService.class), new ObjectMapper(), environment, privacy);
        when(sql.query(anyString(), eq(7L))).thenReturn(List.of());
        AuthContext.set(new AuthenticatedUser(7, 2, 3, "MOBILE", Set.of()));
    }

    @AfterEach void clearIdentity() { AuthContext.clear(); }

    @Test void configuredPoliciesExposeActualVersionAndDistinctPurposeUrls() {
        environment.withProperty("zhiheng.auth.consent-policy-version", "consent-2026-10")
                .withProperty("zhiheng.auth.ai-policy-url", "https://example.invalid/ai")
                .withProperty("zhiheng.auth.notification-policy-url", "https://example.invalid/notification")
                .withProperty("zhiheng.auth.external-health-policy-url", "https://example.invalid/external");
        var result = service.consents();
        assertTrue(result.items().isEmpty());
        assertEquals(List.of("AI_PROCESSING", "NOTIFICATION", "EXTERNAL_HEALTH"),
                result.currentPolicyVersions().stream().map(item -> item.purpose()).toList());
        assertTrue(result.currentPolicyVersions().stream().allMatch(item -> "consent-2026-10".equals(item.version())));
        assertEquals(List.of("https://example.invalid/ai", "https://example.invalid/notification", "https://example.invalid/external"),
                result.currentPolicyVersions().stream().map(item -> item.documentUrl()).toList());
    }

    @Test void missingOrInvalidDocumentsRemainUnavailableWithoutInventingLinks() {
        environment.withProperty("zhiheng.auth.consent-policy-version", "v1")
                .withProperty("zhiheng.auth.ai-policy-url", "http://example.invalid/ai")
                .withProperty("zhiheng.auth.notification-policy-url", "https://")
                .withProperty("zhiheng.auth.external-health-policy-url", "https://user:password@example.invalid/private");
        assertTrue(service.consents().currentPolicyVersions().stream().allMatch(item -> item.documentUrl() == null));
        environment.setProperty("zhiheng.auth.ai-policy-url", "");
        assertNull(service.consents().currentPolicyVersions().getFirst().documentUrl());
    }

    @Test void anUnpublishedVersionDoesNotProduceAConfirmablePolicy() {
        environment.withProperty("zhiheng.auth.ai-policy-url", "https://example.invalid/ai");
        assertTrue(service.consents().currentPolicyVersions().isEmpty());
    }
}
