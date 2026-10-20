package com.yqsoft.interceptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.yqsoft.dao.SqlStore;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.resource.common.Rows;
import com.yqsoft.security.*;
import com.yqsoft.service.auth.AccessTokenService;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.web.method.HandlerMethod;

import java.time.Instant;
import java.util.*;

class AuthInterceptorTest {
    private final SqlStore sql = mock(SqlStore.class);
    private final AccessTokenService tokens = mock(AccessTokenService.class);
    private final AuthInterceptor interceptor = new AuthInterceptor(tokens, sql);

    @AfterEach
    void clear() {
        AuthContext.clear();
    }

    private Map<String, Object> session() {
        return Rows.map(
                "installation_id",
                3L,
                "security_version",
                2L,
                "current_security_version",
                2L,
                "client_audience",
                "MOBILE",
                "revoked_at",
                null,
                "account_deleted_at",
                null,
                "installation_revoked_at",
                null,
                "installation_deleted_at",
                null,
                "expires_at",
                Instant.now().plusSeconds(600),
                "account_status",
                "ACTIVE",
                "account_write_fence",
                "OPEN");
    }

    private MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addHeader("Authorization", "Bearer test");
        return request;
    }

    private HandlerMethod handler(String name) throws Exception {
        return new HandlerMethod(new Endpoint(), Endpoint.class.getMethod(name));
    }

    @Test
    void publicRouteDoesNotCreateAnAuthenticatedIdentity() throws Exception {
        assertTrue(
                interceptor.preHandle(
                        new MockHttpServletRequest("GET", "/api/v1/configuration"),
                        new MockHttpServletResponse(),
                        handler("publicEndpoint")));
        assertTrue(AuthContext.optional().isEmpty());
        verifyNoInteractions(tokens, sql);
    }

    @Test
    void checksDatabaseSessionAndSetsOnlyCurrentSubject() throws Exception {
        when(tokens.verify("test"))
                .thenReturn(new AccessTokenService.Claims(7, 40, 3, 2, "MOBILE"));
        when(sql.find(anyString(), eq(40L), eq(7L))).thenReturn(Optional.of(session()));
        when(sql.query(anyString(), eq(7L))).thenReturn(List.of());
        assertTrue(
                interceptor.preHandle(
                        request("/api/v1/me"), new MockHttpServletResponse(), handler("ordinary")));
        assertEquals(7, AuthContext.userId());
    }

    @Test
    void rejectsRevokedSessionsAndSecurityVersionChanges() throws Exception {
        when(tokens.verify("test"))
                .thenReturn(new AccessTokenService.Claims(7, 40, 3, 2, "MOBILE"));
        Map<String, Object> row = session();
        row.put("current_security_version", 3L);
        when(sql.find(anyString(), eq(40L), eq(7L))).thenReturn(Optional.of(row));
        assertThrows(
                BusinessException.class,
                () ->
                        interceptor.preHandle(
                                request("/api/v1/me"),
                                new MockHttpServletResponse(),
                                handler("ordinary")));
        assertTrue(AuthContext.optional().isEmpty());
    }

    @Test
    void mobileTokenCannotEnterAdminRoutes() throws Exception {
        when(tokens.verify("test"))
                .thenReturn(new AccessTokenService.Claims(7, 40, 3, 2, "MOBILE"));
        when(sql.find(anyString(), eq(40L), eq(7L))).thenReturn(Optional.of(session()));
        assertThrows(
                BusinessException.class,
                () ->
                        interceptor.preHandle(
                                request("/api/admin/v1/users"),
                                new MockHttpServletResponse(),
                                handler("ordinary")));
    }

    @Test
    void adminCannotReadMobileHealthRoutesAndMustHavePermission() throws Exception {
        Map<String, Object> row = session();
        row.put("client_audience", "ADMIN");
        when(tokens.verify("test")).thenReturn(new AccessTokenService.Claims(7, 40, 3, 2, "ADMIN"));
        when(sql.find(anyString(), eq(40L), eq(7L))).thenReturn(Optional.of(row));
        when(sql.query(anyString(), eq(7L)))
                .thenReturn(List.of(Rows.map("permission_code", "content.read")));
        assertThrows(
                BusinessException.class,
                () ->
                        interceptor.preHandle(
                                request("/api/v1/records"),
                                new MockHttpServletResponse(),
                                handler("ordinary")));
        assertThrows(
                BusinessException.class,
                () ->
                        interceptor.preHandle(
                                request("/api/admin/v1/knowledge/versions/5/publish"),
                                new MockHttpServletResponse(),
                                handler("publish")));
    }

    public static class Endpoint {
        @PublicEndpoint
        public void publicEndpoint() {}

        public void ordinary() {}

        @RequirePermission("content.publish")
        public void publish() {}
    }
}
