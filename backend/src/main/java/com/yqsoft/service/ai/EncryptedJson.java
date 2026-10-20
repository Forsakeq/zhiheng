package com.yqsoft.service.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.resource.common.Rows;
import com.yqsoft.service.common.CryptoService;

import org.springframework.stereotype.Component;

import java.util.Base64;

@Component
public class EncryptedJson {
    private final CryptoService crypto;
    private final ObjectMapper mapper;

    public EncryptedJson(CryptoService crypto, ObjectMapper mapper) {
        this.crypto = crypto;
        this.mapper = mapper;
    }

    public JsonNode seal(Object value) {
        try {
            byte[] ciphertext = crypto.encrypt(mapper.writeValueAsString(value));
            return mapper.valueToTree(
                    Rows.map(
                            "alg",
                            "APP_ENCRYPTION_V1",
                            "keyRef",
                            crypto.keyRef(),
                            "nonce",
                            "embedded",
                            "ciphertext",
                            Base64.getEncoder().encodeToString(ciphertext)));
        } catch (JsonProcessingException ex) {
            throw BusinessException.badRequest("OUTPUT_INVALID", "内容无法序列化");
        }
    }

    public JsonNode open(JsonNode envelope) {
        if (envelope == null || envelope.isNull()) return null;
        try {
            byte[] encrypted = Base64.getDecoder().decode(envelope.path("ciphertext").asText());
            return mapper.readTree(crypto.decrypt(encrypted));
        } catch (Exception ex) {
            throw new BusinessException(500, "ENCRYPTED_CONTENT_INVALID", "内容暂不可读取");
        }
    }
}
