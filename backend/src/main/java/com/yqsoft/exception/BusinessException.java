package com.yqsoft.exception;

public class BusinessException extends RuntimeException {

    private final int status;
    private final String code;

    public BusinessException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static BusinessException badRequest(String code, String message) {
        return new BusinessException(422, code, message);
    }

    public static BusinessException conflict(String code, String message) {
        return new BusinessException(409, code, message);
    }

    public static BusinessException notFound() {
        return new BusinessException(404, "RESOURCE_NOT_FOUND", "资源不存在");
    }

    public static BusinessException forbidden(String code, String message) {
        return new BusinessException(403, code, message);
    }

    public static BusinessException unavailable(String code, String message) {
        return new BusinessException(503, code, message);
    }
}
