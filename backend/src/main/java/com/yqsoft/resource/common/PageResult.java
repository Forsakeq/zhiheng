package com.yqsoft.resource.common;

import java.util.List;

public record PageResult<T>(
        List<T> items, String nextCursor, boolean hasMore, Integer totalApproximate) {

    public static <T> PageResult<T> of(List<T> items, String nextCursor, boolean hasMore) {
        return new PageResult<>(items, nextCursor, hasMore, null);
    }
}
