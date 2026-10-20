package com.yqsoft.resource.common;

import java.time.Instant;

public record ApiResponse<T>(
        String code, String message, T data, String requestId, Instant serverTime) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("OK", "成功", data, RequestContext.requestId(), Instant.now());
    }
}
