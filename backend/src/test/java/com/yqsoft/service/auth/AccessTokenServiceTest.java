package com.yqsoft.service.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.service.common.CryptoService;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

class AccessTokenServiceTest {
    private CryptoService crypto() throws Exception {
        CryptoService crypto = mock(CryptoService.class);
        when(crypto.hmac(eq("jwt"), anyString()))
                .thenAnswer(
                        invocation -> {
                            Mac mac = Mac.getInstance("HmacSHA256");
                            mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
                            return mac.doFinal(
                                    invocation
                                            .<String>getArgument(1)
                                            .getBytes(StandardCharsets.UTF_8));
                        });
        return crypto;
    }

    @Test
    void issuesNumericDateEvenWithApiLongStringSerializer() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);
        mapper.registerModule(module);
        AccessTokenService service =
                new AccessTokenService(crypto(), mapper, new MockEnvironment());
        String token = service.issue(7, 40, 3, "MOBILE", 2, Instant.now().plusSeconds(600));
        var payload = mapper.readTree(Base64.getUrlDecoder().decode(token.split("[.]")[1]));
        assertTrue(payload.get("exp").isIntegralNumber());
        assertTrue(payload.get("iat").isIntegralNumber());
        assertTrue(payload.get("sub").isTextual());
        var claims = service.verify(token);
        assertEquals(7, claims.userId());
        assertEquals("MOBILE", claims.audience());
    }

    @Test
    void rejectsTamperingAndUnapprovedAlgorithms() throws Exception {
        AccessTokenService service =
                new AccessTokenService(crypto(), new ObjectMapper(), new MockEnvironment());
        String token = service.issue(7, 40, 3, "MOBILE", 2, Instant.now().plusSeconds(600));
        String[] parts = token.split("[.]");
        assertThrows(
                BusinessException.class,
                () -> service.verify(parts[0] + "." + parts[1] + "." + parts[2] + "x"));
        String none = "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0";
        assertThrows(
                BusinessException.class,
                () -> service.verify(none + "." + parts[1] + "." + parts[2]));
        assertThrows(BusinessException.class, () -> service.verify("a".repeat(4097)));
    }

    @Test
    void rejectsExpiryAndWrongIssuer() throws Exception {
        CryptoService crypto = crypto();
        AccessTokenService issuer =
                new AccessTokenService(crypto, new ObjectMapper(), new MockEnvironment());
        String expired = issuer.issue(7, 40, 3, "MOBILE", 2, Instant.now().minusSeconds(1));
        assertThrows(BusinessException.class, () -> issuer.verify(expired));
        AccessTokenService other =
                new AccessTokenService(
                        crypto,
                        new ObjectMapper(),
                        new MockEnvironment().withProperty("zhiheng.security.jwt-issuer", "other"));
        String token = issuer.issue(7, 40, 3, "ADMIN", 2, Instant.now().plusSeconds(60));
        assertThrows(BusinessException.class, () -> other.verify(token));
    }
}
