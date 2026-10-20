package com.yqsoft.service.file;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.yqsoft.resource.common.Rows;
import com.yqsoft.service.common.CryptoService;
import com.yqsoft.support.MySqlFlowSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

import javax.imageio.ImageIO;

@EnabledIfEnvironmentVariable(named = "ZH_TEST_DB_URL", matches = ".+")
class FileMySqlIntegrationTest extends MySqlFlowSupport {

    @Autowired CryptoService crypto;
    @Autowired FileService files;

    @Test
    void privateUploadVerificationEncryptedStorageDownloadAndDeletion() throws Exception {
        var user = registerUser();
        byte[] bytes;
        try (var output = new ByteArrayOutputStream()) {
            ImageIO.write(new BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB), "png", output);
            bytes = output.toByteArray();
        }
        String digest = HexFormat.of().formatHex(crypto.sha256(bytes));
        var intent =
                request(
                        post("/api/v1/files/upload-intents"),
                        user,
                        Rows.map(
                                "clientFileKey",
                                UUID.randomUUID().toString(),
                                "purpose",
                                "AVATAR",
                                "originalName",
                                "测试头像.png",
                                "mediaType",
                                "image/png",
                                "sizeBytes",
                                bytes.length,
                                "sha256",
                                digest),
                        201);
        String fileId = intent.path("fileId").asText();
        var upload =
                mvc.perform(
                                put(URI.create(intent.path("uploadUrl").asText()))
                                        .contentType("image/png")
                                        .content(bytes))
                        .andReturn()
                        .getResponse();
        assertEquals(204, upload.getStatus(), upload.getContentAsString());

        var completed =
                request(
                        post("/api/v1/files/" + fileId + "/complete"),
                        user,
                        Rows.map(
                                "expectedRowVersion",
                                intent.path("fileRowVersion").asText(),
                                "uploadToken",
                                intent.path("uploadToken").asText(),
                                "sha256",
                                digest,
                                "sizeBytes",
                                bytes.length),
                        200);
        var row = sql.one("SELECT * FROM file_object WHERE id=?", Long.parseLong(fileId));
        byte[] storedBytes = Files.readAllBytes(files.objectPath(Rows.text(row, "object_key")));
        assertFalse(Arrays.equals(bytes, storedBytes));
        assertEquals(1, storedBytes[0]);
        assertArrayEquals(bytes, files.readContent(row));

        var download =
                request(
                        post("/api/v1/files/" + fileId + "/download-url"),
                        user,
                        Rows.map("disposition", "ATTACHMENT"),
                        200);
        var response =
                mvc.perform(get(URI.create(download.path("url").asText())))
                        .andReturn()
                        .getResponse();
        assertEquals(200, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertArrayEquals(bytes, response.getContentAsByteArray());

        sql.execute(
                "UPDATE file_object SET retention_until=? WHERE id=?",
                java.time.Instant.now().minusSeconds(1),
                Long.parseLong(fileId));
        request(
                post("/api/v1/files/" + fileId + "/download-url"),
                user,
                Rows.map("disposition", "ATTACHMENT"),
                410);
        var expired =
                mvc.perform(get(URI.create(download.path("url").asText())))
                        .andReturn()
                        .getResponse();
        assertEquals(410, expired.getStatus());
        sql.execute(
                "UPDATE file_object SET retention_until=NULL WHERE id=?", Long.parseLong(fileId));

        request(
                delete("/api/v1/files/" + fileId),
                user,
                Rows.map("expectedRowVersion", completed.path("rowVersion").asText()),
                200);
        assertFalse(Files.exists(files.objectPath(Rows.text(row, "object_key"))));
        assertEquals(
                "DELETED",
                Rows.text(
                        sql.one(
                                "SELECT status FROM file_object WHERE id=?",
                                Long.parseLong(fileId)),
                        "status"));
        var denied =
                mvc.perform(get(URI.create(download.path("url").asText())))
                        .andReturn()
                        .getResponse();
        assertEquals(404, denied.getStatus());
    }
}
