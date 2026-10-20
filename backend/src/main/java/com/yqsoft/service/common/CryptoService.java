package com.yqsoft.service.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yqsoft.config.AppProperties;

import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@Service
public class CryptoService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] encryptionKey;
    private final byte[] hmacKey;
    private final ObjectMapper mapper;
    private final String keyRef;

    public CryptoService(AppProperties properties, ObjectMapper mapper) throws Exception {
        this.mapper = mapper;
        var settings = properties.getSecurity();
        String encryption = settings.getEncryptionKey();
        String hmac = settings.getHmacKey();
        boolean external = !encryption.isBlank() && !hmac.isBlank();
        if (!external) {
            if (settings.isRequireExternalKeys() || !encryption.isBlank() || !hmac.isBlank()) {
                throw new IllegalStateException(
                        "Both external encryption and HMAC keys are required");
            }
            var path = settings.getKeyFile().toAbsolutePath().normalize();
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                byte[] first = new byte[32];
                byte[] second = new byte[32];
                RANDOM.nextBytes(first);
                RANDOM.nextBytes(second);
                String content =
                        "encryption="
                                + Base64.getEncoder().encodeToString(first)
                                + "\nhmac="
                                + Base64.getEncoder().encodeToString(second)
                                + "\n";
                try {
                    Files.writeString(
                            path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                } catch (java.nio.file.FileAlreadyExistsException ignored) {
                    // Another local application instance completed initialization first.
                }
            }
            Properties local = new Properties();
            try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                local.load(reader);
            }
            encryption = local.getProperty("encryption", "");
            hmac = local.getProperty("hmac", "");
        }
        this.encryptionKey = Base64.getDecoder().decode(encryption);
        this.hmacKey = Base64.getDecoder().decode(hmac);
        if (encryptionKey.length != 32 || hmacKey.length < 32) {
            throw new IllegalStateException("Encryption/HMAC keys must contain at least 256 bits");
        }
        this.keyRef = external ? "env-v1" : "local-v1";
    }

    public byte[] encrypt(String text) {
        if (text == null) {
            return null;
        }
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(encryptionKey, "AES"),
                    new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + nonce.length + encrypted.length)
                    .put((byte) 1)
                    .put(nonce)
                    .put(encrypted)
                    .array();
        } catch (Exception exception) {
            throw new IllegalStateException("Encryption failed");
        }
    }

    public String decrypt(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof byte[] bytes) || bytes.length < 29 || bytes[0] != 1) {
            throw new IllegalStateException("Encrypted value has an unsupported envelope");
        }
        try {
            ByteBuffer envelope = ByteBuffer.wrap(bytes);
            envelope.get();
            byte[] nonce = new byte[12];
            envelope.get(nonce);
            byte[] ciphertext = new byte[envelope.remaining()];
            envelope.get(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(encryptionKey, "AES"),
                    new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Encrypted value authentication failed");
        }
    }

    public String keyRef() {
        return keyRef;
    }

    public byte[] hmac(String purpose, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return mac.doFinal((purpose + "\0" + value).getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("HMAC unavailable");
        }
    }

    public byte[] hashToken(String token) {
        return hmac("token", token);
    }

    public String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    public byte[] digest(Object value) {
        try {
            return hmac("request", mapper.writeValueAsString(canonical(mapper.valueToTree(value))));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Request cannot be canonicalized");
        }
    }

    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            node.fields()
                    .forEachRemaining(
                            entry -> sorted.put(entry.getKey(), canonical(entry.getValue())));
            ObjectNode result = mapper.createObjectNode();
            sorted.forEach(result::set);
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            node.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return node;
    }
}
