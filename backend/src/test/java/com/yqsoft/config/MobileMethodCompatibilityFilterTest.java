package com.yqsoft.config;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.resource.common.RequestContext;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;

class MobileMethodCompatibilityFilterTest {

    private final MobileMethodCompatibilityFilter filter = new MobileMethodCompatibilityFilter();

    @Test
    void requestsWithoutOverrideReachTheChainUnchanged() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/api/v1/me/profile");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertSame(request, chain.getRequest());
        assertEquals("PATCH", ((HttpServletRequest) chain.getRequest()).getMethod());
    }

    @Test
    void everyAllowlistedRouteBecomesPatchAndPreservesTheOriginalRequest() throws Exception {
        List<String> paths =
                List.of(
                        "/api/v1/me/profile",
                        "/api/v1/me/preferences",
                        "/api/v1/me/goals/123",
                        "/api/v1/installations/7",
                        "/api/v1/record-tags/42",
                        "/api/v1/reminder-rules/9");
        for (String path : paths) {
            MockHttpServletRequest request = overrideRequest("POST", path, "PATCH");
            request.addHeader("Authorization", "Bearer private-session");
            request.addHeader("Idempotency-Key", "operation-key");
            request.addHeader("X-Data-Epoch", "3");
            request.setQueryString("view=mobile");
            request.setContentType("application/json");
            request.setContent("{\"expectedRowVersion\":\"2\"}".getBytes(StandardCharsets.UTF_8));
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, new MockHttpServletResponse(), chain);

            HttpServletRequest adapted = (HttpServletRequest) chain.getRequest();
            assertEquals("PATCH", adapted.getMethod(), path);
            assertEquals("POST", request.getMethod(), "the original request is not mutated");
            assertEquals(path, adapted.getRequestURI());
            assertEquals("view=mobile", adapted.getQueryString());
            assertEquals("Bearer private-session", adapted.getHeader("Authorization"));
            assertEquals("operation-key", adapted.getHeader("Idempotency-Key"));
            assertEquals("3", adapted.getHeader("X-Data-Epoch"));
            assertEquals(
                    "{\"expectedRowVersion\":\"2\"}",
                    new String(adapted.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void adminUnlistedEncodedAndAmbiguousPathsAreRejectedBeforeDispatch() {
        List<String> paths =
                List.of(
                        "/api/admin/v1/users/1",
                        "/api/v1/records/1",
                        "/api/v1/me/profile/",
                        "/api/v1/me/profile;extra=1",
                        "/api/v1/me/goals/0",
                        "/api/v1/me/goals/01",
                        "/api/v1/me/goals/-1",
                        "/api/v1/me/goals/%31",
                        "/api/v1/me/goals/1/anything",
                        "/api/v1/me/../me/profile",
                        "/api/v1//me/profile");
        for (String path : paths) {
            MockFilterChain chain = new MockFilterChain();
            BusinessException exception =
                    assertThrows(
                            BusinessException.class,
                            () ->
                                    filter.doFilter(
                                            overrideRequest("POST", path, "PATCH"),
                                            new MockHttpServletResponse(),
                                            chain));
            assertEquals(422, exception.getStatus(), path);
            assertEquals("METHOD_OVERRIDE_NOT_ALLOWED", exception.getCode());
            assertNull(chain.getRequest(), "rejected requests never reach MVC");
        }
    }

    @Test
    void onlyOneExactPatchHeaderOnPostIsAccepted() throws Exception {
        for (String method : List.of("GET", "PUT", "DELETE", "PATCH", "OPTIONS")) {
            assertThrows(
                    BusinessException.class,
                    () ->
                            filter.doFilter(
                                    overrideRequest(method, "/api/v1/me/profile", "PATCH"),
                                    new MockHttpServletResponse(),
                                    new MockFilterChain()));
        }
        for (String override : List.of("DELETE", "GET", "patch", " PATCH", "PATCH,DELETE", "")) {
            assertThrows(
                    BusinessException.class,
                    () ->
                            filter.doFilter(
                                    overrideRequest("POST", "/api/v1/me/profile", override),
                                    new MockHttpServletResponse(),
                                    new MockFilterChain()));
        }
        MockHttpServletRequest repeated = overrideRequest("POST", "/api/v1/me/profile", "PATCH");
        repeated.addHeader(MobileMethodCompatibilityFilter.OVERRIDE_HEADER, "PATCH");
        assertThrows(
                BusinessException.class,
                () ->
                        filter.doFilter(
                                repeated, new MockHttpServletResponse(), new MockFilterChain()));

        MockHttpServletRequest deployed =
                overrideRequest("POST", "/zhiheng/api/v1/me/preferences", "PATCH");
        deployed.setContextPath("/zhiheng");
        MockFilterChain deployedChain = new MockFilterChain();
        filter.doFilter(deployed, new MockHttpServletResponse(), deployedChain);
        assertEquals("PATCH", ((HttpServletRequest) deployedChain.getRequest()).getMethod());
    }

    @Test
    void outerRequestContextFilterReturnsTheExistingJsonErrorEnvelope() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        MockHttpServletRequest request =
                overrideRequest("POST", "/api/admin/v1/users/1", "PATCH");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain(new HttpServlet() {}, filter);

        new RequestContextFilter(mapper).doFilter(request, response, chain);

        assertEquals(422, response.getStatus());
        assertEquals(
                "METHOD_OVERRIDE_NOT_ALLOWED",
                mapper.readTree(response.getContentAsString()).path("code").asText());
        assertFalse(response.getHeader("X-Request-Id").isBlank());
        assertEquals(
                response.getHeader("X-Request-Id"),
                mapper.readTree(response.getContentAsString()).path("requestId").asText());
        assertNull(RequestContext.snapshot());
    }

    private MockHttpServletRequest overrideRequest(String method, String path, String value) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader(MobileMethodCompatibilityFilter.OVERRIDE_HEADER, value);
        return request;
    }
}
