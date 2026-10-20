package com.yqsoft.resource.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yqsoft.exception.BusinessException;

import jakarta.validation.Validator;

import org.springframework.stereotype.Component;

@Component
public class ValidationSupport {

    private final Validator validator;
    private final ObjectMapper mapper;

    public ValidationSupport(Validator validator, ObjectMapper mapper) {
        this.validator = validator;
        this.mapper = mapper;
    }

    public <T> T validate(T value) {
        if (value == null || !validator.validate(value).isEmpty()) {
            throw BusinessException.badRequest("VALIDATION_FAILED", "请求字段不完整或格式错误");
        }
        return value;
    }

    public <T> T convert(JsonNode input, Class<T> type) {
        try {
            return validate(mapper.treeToValue(input, type));
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw BusinessException.badRequest("VALIDATION_FAILED", "请求字段格式错误或包含未知字段");
        }
    }
}
