package com.yqsoft.service.common;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yqsoft.config.AppProperties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;

class CryptoServiceTest {

    @TempDir Path directory;

    @Test
    void stableLocalKeysDecryptAcrossRestartsAndTamperingFails() throws Exception {
        var properties = new AppProperties();
        properties.getSecurity().setKeyFile(directory.resolve("private.keys"));
        var first = new CryptoService(properties, new ObjectMapper());
        byte[] encrypted = first.encrypt("个人记录正文");
        var restarted = new CryptoService(properties, new ObjectMapper());
        assertEquals("个人记录正文", restarted.decrypt(encrypted));
        assertFalse(Arrays.equals(encrypted, first.encrypt("个人记录正文")));
        assertArrayEquals(first.hashToken("test-token"), restarted.hashToken("test-token"));
        encrypted[encrypted.length - 1] ^= 1;
        assertThrows(IllegalStateException.class, () -> restarted.decrypt(encrypted));
    }

    @Test
    void productionRequiresCompleteExternalKeyMaterial() {
        var properties = new AppProperties();
        properties.getSecurity().setRequireExternalKeys(true);
        assertThrows(
                IllegalStateException.class,
                () -> new CryptoService(properties, new ObjectMapper()));
        assertFalse(directory.resolve("private.keys").toFile().exists());
    }
}
