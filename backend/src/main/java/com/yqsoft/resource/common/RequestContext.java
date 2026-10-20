package com.yqsoft.resource.common;

import com.yqsoft.exception.BusinessException;

import java.util.UUID;

public final class RequestContext {

    private static final ThreadLocal<State> HOLDER = new ThreadLocal<>();

    private RequestContext() {}

    public record State(String requestId, String idempotencyKey, Long epoch) {}

    public static void set(String requestId, String idempotencyKey, Long epoch) {
        HOLDER.set(new State(requestId, idempotencyKey, epoch));
    }

    public static State snapshot() {
        return HOLDER.get();
    }

    public static void restore(State state) {
        if (state == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(state);
        }
    }

    public static String requestId() {
        State state = HOLDER.get();
        return state == null ? UUID.randomUUID().toString() : state.requestId();
    }

    public static String idempotencyKey() {
        State state = HOLDER.get();
        String key = state == null ? null : state.idempotencyKey();
        if (key == null || !key.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            throw BusinessException.badRequest(
                    "IDEMPOTENCY_KEY_REQUIRED", "请提供UUID格式的Idempotency-Key");
        }
        return UUID.fromString(key).toString();
    }

    public static Long dataEpoch() {
        State state = HOLDER.get();
        return state == null ? null : state.epoch();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
