package com.yqsoft.service.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.service.common.CryptoService;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

@Service
public class AccessTokenService {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final String HEADER = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9";
    private final CryptoService crypto;
    private final ObjectMapper mapper;
    private final String issuer;

    public AccessTokenService(CryptoService crypto, ObjectMapper mapper, Environment environment) {
        this.crypto = crypto;
        this.mapper = mapper;
        this.issuer = environment.getProperty("zhiheng.security.jwt-issuer", "zhiheng");
    }

    public String issue(
            long userId,
            long sessionId,
            long installationId,
            String audience,
            long securityVersion,
            Instant expiresAt) {
        ObjectNode claims = mapper.createObjectNode();
        claims.put("iss", issuer);
        claims.put("sub", Long.toString(userId));
        claims.put("sid", Long.toString(sessionId));
        claims.put("iid", Long.toString(installationId));
        claims.put("sv", Long.toString(securityVersion));
        claims.put("aud", audience);
        // NumericNode keeps RFC NumericDate numeric despite the API Long-as-string serializer.
        claims.put("iat", Instant.now().getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());
        try {
            String content =
                    HEADER + "." + ENCODER.encodeToString(mapper.writeValueAsBytes(claims));
            return content + "." + ENCODER.encodeToString(crypto.hmac("jwt", content));
        } catch (Exception exception) {
            throw new IllegalStateException("无法签发访问令牌");
        }
    }

    public Claims verify(String token) {
        try {
            if (token.length() > 4096) {
                throw invalid();
            }
            String[] parts = token.split("[.]", -1);
            if (parts.length != 3 || !HEADER.equals(parts[0])) {
                throw invalid();
            }
            byte[] expected = crypto.hmac("jwt", parts[0] + "." + parts[1]);
            if (!MessageDigest.isEqual(expected, DECODER.decode(parts[2]))) {
                throw invalid();
            }
            JsonNode payload = mapper.readTree(DECODER.decode(parts[1]));
            if (!payload.isObject()
                    || !issuer.equals(payload.path("iss").asText())
                    || !payload.path("exp").isIntegralNumber()
                    || !payload.path("iat").isIntegralNumber()) {
                throw invalid();
            }
            Instant now = Instant.now();
            if (payload.path("exp").asLong() <= now.getEpochSecond()) {
                throw new BusinessException(401, "TOKEN_EXPIRED", "登录已过期");
            }
            if (payload.path("iat").asLong() > now.plusSeconds(30).getEpochSecond()) {
                throw invalid();
            }
            String audience = payload.path("aud").asText();
            if (!"MOBILE".equals(audience) && !"ADMIN".equals(audience)) {
                throw invalid();
            }
            return new Claims(
                    positive(payload, "sub"),
                    positive(payload, "sid"),
                    positive(payload, "iid"),
                    positive(payload, "sv"),
                    audience);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private long positive(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || !node.isTextual() || !node.asText().matches("[1-9][0-9]{0,18}")) {
            throw invalid();
        }
        return Long.parseLong(node.asText());
    }

    private BusinessException invalid() {
        return new BusinessException(401, "INVALID_TOKEN", "无效的登录凭据");
    }

    public record Claims(
            long userId,
            long sessionId,
            long installationId,
            long securityVersion,
            String audience) {}
}
