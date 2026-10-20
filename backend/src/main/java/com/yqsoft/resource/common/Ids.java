package com.yqsoft.resource.common;

import com.yqsoft.exception.BusinessException;

public final class Ids {

    private Ids() {}

    public static long parse(String value) {
        long result = nonNegative(value);
        if (result == 0) {
            throw BusinessException.badRequest("INVALID_ID", "ID或版本必须为正整数字符串");
        }
        return result;
    }

    public static Long optional(String value) {
        return value == null ? null : parse(value);
    }

    public static long nonNegative(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) {
            throw BusinessException.badRequest("INVALID_ID", "请使用十进制整数字符串");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw BusinessException.badRequest("INVALID_ID", "数字超过服务支持范围");
        }
    }
}
