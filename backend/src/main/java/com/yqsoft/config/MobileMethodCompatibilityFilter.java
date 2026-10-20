package com.yqsoft.config;

import com.yqsoft.exception.BusinessException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Adapts the classic uni-app transport, whose native request API does not expose PATCH.
 * Authentication, validation, ownership, and idempotency still run on the original PATCH handlers.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class MobileMethodCompatibilityFilter extends OncePerRequestFilter {

    public static final String OVERRIDE_HEADER = "X-HTTP-Method-Override";

    private static final Pattern MOBILE_PATCH_PATH =
            Pattern.compile(
                    "/api/v1/(?:me/(?:profile|preferences|goals/[1-9][0-9]*)"
                            + "|installations/[1-9][0-9]*"
                            + "|record-tags/[1-9][0-9]*"
                            + "|reminder-rules/[1-9][0-9]*)");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        List<String> overrides = Collections.list(request.getHeaders(OVERRIDE_HEADER));
        if (overrides.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (!contextPath.isEmpty() && path.startsWith(contextPath + "/")) {
            path = path.substring(contextPath.length());
        }

        if (!"POST".equals(request.getMethod())
                || overrides.size() != 1
                || !"PATCH".equals(overrides.getFirst())
                || !MOBILE_PATCH_PATH.matcher(path).matches()) {
            throw BusinessException.badRequest(
                    "METHOD_OVERRIDE_NOT_ALLOWED", "仅指定手机端接口支持 POST 转 PATCH，请检查请求方式与地址");
        }

        HttpServletRequest patchRequest =
                new HttpServletRequestWrapper(request) {
                    @Override
                    public String getMethod() {
                        return "PATCH";
                    }
                };
        chain.doFilter(patchRequest, response);
    }
}
