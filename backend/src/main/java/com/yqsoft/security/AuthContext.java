package com.yqsoft.security;

import com.yqsoft.exception.BusinessException;

import java.util.Optional;

public final class AuthContext {
    private static final ThreadLocal<AuthenticatedUser> HOLDER = new ThreadLocal<>();

    private AuthContext() {}

    public static AuthenticatedUser current() {
        return optional().orElseThrow(() -> new BusinessException(401, "AUTH_REQUIRED", "请先登录"));
    }

    public static Optional<AuthenticatedUser> optional() {
        return Optional.ofNullable(HOLDER.get());
    }

    public static long userId() {
        return current().userId();
    }

    public static void set(AuthenticatedUser user) {
        HOLDER.set(user);
    }

    public static void clear() {
        HOLDER.remove();
    }
}
