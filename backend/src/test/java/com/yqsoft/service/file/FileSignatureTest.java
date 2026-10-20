package com.yqsoft.service.file;

import static org.junit.jupiter.api.Assertions.*;

import com.yqsoft.exception.BusinessException;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

class FileSignatureTest {
    @Test
    void aRenamedHtmlFileIsNotAcceptedAsAnImage() {
        assertThrows(
                BusinessException.class,
                () ->
                        FileService.inspect(
                                "<html>script</html>".getBytes(StandardCharsets.UTF_8),
                                "image/png"));
    }

    @Test
    void mediaContainersMustMatchTheirDeclaredSubtype() {
        byte[] wav = "RIFF1234WAVEpayload".getBytes(StandardCharsets.US_ASCII);
        assertEquals("audio/wav", FileService.inspect(wav, "audio/wav"));
        assertThrows(BusinessException.class, () -> FileService.inspect(wav, "image/webp"));
    }
}
