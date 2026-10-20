package com.yqsoft.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.resource.common.Ids;
import com.yqsoft.resource.common.RequestContext;
import com.yqsoft.resource.common.Rows;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

@Component("zhihengRequestContextFilter")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestContextFilter extends OncePerRequestFilter {

    private final ObjectMapper mapper;

    public RequestContextFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        response.setHeader("X-Request-Id", requestId);
        response.setHeader("X-Content-Type-Options", "nosniff");
        try {
            Long epoch = Ids.optional(request.getHeader("X-Data-Epoch"));
            RequestContext.set(requestId, request.getHeader("Idempotency-Key"), epoch);
            if (request.getContentType() != null
                    && request.getContentType().startsWith("application/json")
                    && request.getContentLengthLong() > 262144) {
                throw new BusinessException(413, "REQUEST_TOO_LARGE", "JSON请求不得超过256KiB");
            }
            chain.doFilter(request, response);
        } catch (BusinessException exception) {
            if (!response.isCommitted()) {
                response.setStatus(exception.getStatus());
                response.setContentType("application/json;charset=UTF-8");
                mapper.writeValue(
                        response.getOutputStream(),
                        Rows.map(
                                "code",
                                exception.getCode(),
                                "message",
                                exception.getMessage(),
                                "data",
                                null,
                                "requestId",
                                requestId,
                                "serverTime",
                                Instant.now()));
            }
        } finally {
            RequestContext.clear();
        }
    }
}
