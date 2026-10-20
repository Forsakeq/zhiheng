package com.yqsoft.controller.file;

import com.yqsoft.resource.common.ApiResponse;
import com.yqsoft.resource.common.Ids;
import com.yqsoft.resource.common.MutationResult;
import com.yqsoft.resource.common.Rows;
import com.yqsoft.resource.file.FileDtos.*;
import com.yqsoft.security.PublicEndpoint;
import com.yqsoft.service.file.FileService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {
    private final FileService files;

    public FileController(FileService files) {
        this.files = files;
    }

    @PostMapping("/upload-intents")
    public ResponseEntity<ApiResponse<UploadIntentView>> createUploadIntent(
            @Valid @RequestBody UploadIntentCreate request) {
        return ResponseEntity.status(201).body(ApiResponse.ok(files.createIntent(request)));
    }

    @PostMapping("/{fileId}/complete")
    public ApiResponse<MutationResult> completeFileUpload(
            @PathVariable String fileId, @Valid @RequestBody FileUploadComplete request) {
        return ApiResponse.ok(files.complete(Ids.parse(fileId), request));
    }

    @PostMapping("/{fileId}/download-url")
    public ApiResponse<DownloadView> createFileDownloadUrl(
            @PathVariable String fileId,
            @RequestBody(required = false) FileDownloadRequest request) {
        return ApiResponse.ok(files.download(Ids.parse(fileId), request));
    }

    @DeleteMapping("/{fileId}")
    public ApiResponse<MutationResult> deleteUnusedFile(
            @PathVariable String fileId, @Valid @RequestBody VersionBody request) {
        return ApiResponse.ok(files.deleteUnused(Ids.parse(fileId), request));
    }

    @PublicEndpoint
    @PutMapping("/{fileId}/content")
    public ResponseEntity<Void> uploadContent(
            @PathVariable String fileId,
            @RequestParam long expires,
            @RequestParam long epoch,
            @RequestParam String signature,
            HttpServletRequest request)
            throws IOException {
        files.receiveContent(
                Ids.parse(fileId), expires, epoch, signature, request.getInputStream());
        return ResponseEntity.noContent().build();
    }

    @PublicEndpoint
    @GetMapping("/{fileId}/content")
    public ResponseEntity<ByteArrayResource> downloadContent(
            @PathVariable String fileId,
            @RequestParam long expires,
            @RequestParam long epoch,
            @RequestParam String signature,
            @RequestParam(defaultValue = "INLINE") String disposition) {
        Map<String, Object> row =
                files.signedDownloadRow(Ids.parse(fileId), expires, epoch, signature, disposition);
        ByteArrayResource resource = new ByteArrayResource(files.readContent(row));
        String extension =
                switch (Rows.text(row, "mime_type")) {
                    case "image/png" -> ".png";
                    case "image/jpeg" -> ".jpg";
                    case "image/webp" -> ".webp";
                    case "audio/wav" -> ".wav";
                    case "audio/mpeg" -> ".mp3";
                    case "application/pdf" -> ".pdf";
                    default -> ".bin";
                };
        ContentDisposition contentDisposition =
                ("ATTACHMENT".equals(disposition)
                                ? ContentDisposition.attachment()
                                : ContentDisposition.inline())
                        .filename("file-" + fileId + extension)
                        .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(Rows.text(row, "mime_type")))
                .contentLength(Rows.number(row, "byte_size"))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                .body(resource);
    }
}
