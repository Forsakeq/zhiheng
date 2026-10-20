package com.yqsoft.security;

import java.util.Set;

public record AuthenticatedUser(
        long userId,
        long sessionId,
        long installationId,
        String audience,
        Set<String> permissions) {
    public AuthenticatedUser {
        permissions = Set.copyOf(permissions);
    }
}
