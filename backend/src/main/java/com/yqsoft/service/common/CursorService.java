package com.yqsoft.service.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.resource.common.Rows;
import com.yqsoft.security.AuthContext;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

@Service
public class CursorService {

    private final CryptoService crypto;
    private final ObjectMapper mapper;

    public CursorService(CryptoService crypto, ObjectMapper mapper) {
        this.crypto = crypto;
        this.mapper = mapper;
    }

    public String encode(String scope, Map<String, Object> state) {
        try {
            String json =
                    mapper.writeValueAsString(
                            Rows.map(
                                    "userId",
                                    AuthContext.userId(),
                                    "scope",
                                    scope,
                                    "state",
                                    state));
            String payload =
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(json.getBytes(StandardCharsets.UTF_8));
            return payload
                    + "."
                    + Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(crypto.hmac("cursor", payload));
        } catch (Exception exception) {
            throw new IllegalStateException("Cursor generation failed");
        }
    }

    public Map<String, Object> decode(String cursor, String scope) {
        if (cursor == null || cursor.isBlank()) {
            return Map.of();
        }
        try {
            if (cursor.length() > 2048) {
                throw new IllegalArgumentException();
            }
            String[] parts = cursor.split("\\.", -1);
            if (parts.length != 2
                    || !MessageDigest.isEqual(
                            crypto.hmac("cursor", parts[0]),
                            Base64.getUrlDecoder().decode(parts[1]))) {
                throw new IllegalArgumentException();
            }
            var root = mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if (!root.path("scope").asText().equals(scope)
                    || root.path("userId").asLong() != AuthContext.userId()) {
                throw new IllegalArgumentException();
            }
            return mapper.convertValue(root.get("state"), new TypeReference<>() {});
        } catch (Exception exception) {
            throw BusinessException.badRequest("INVALID_CURSOR", "游标无效或不属于当前查询");
        }
    }
}
