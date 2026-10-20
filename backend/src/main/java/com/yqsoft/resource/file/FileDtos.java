package com.yqsoft.resource.file;

import com.yqsoft.resource.common.MutationResult;

import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class FileDtos {
    private FileDtos() {}

    public enum Purpose {
        AVATAR,
        RECORD_ATTACHMENT,
        FEEDBACK_ATTACHMENT,
        VOICE_TEMP,
        COURSE_IMAGE
    }

    public enum Disposition {
        INLINE,
        ATTACHMENT
    }

    public record UploadIntentCreate(
            @NotNull UUID clientFileKey,
            @NotNull Purpose purpose,
            @NotBlank @Size(max = 120) String originalName,
            @NotNull
                    @Pattern(
                            regexp =
                                    "image/jpeg|image/png|image/webp|audio/mpeg|audio/wav|application/pdf")
                    String mediaType,
            @NotNull @Min(1) @Max(20971520) Long sizeBytes,
            @NotNull @Pattern(regexp = "[a-f0-9]{64}") String sha256) {}

    public record FileUploadComplete(
            @NotBlank String expectedRowVersion,
            @NotBlank @Size(min = 32, max = 512) String uploadToken,
            @NotNull @Pattern(regexp = "[a-f0-9]{64}") String sha256,
            @NotNull @Min(1) @Max(20971520) Long sizeBytes) {}

    public record FileDownloadRequest(Disposition disposition) {}

    public record VersionBody(@NotBlank String expectedRowVersion) {}

    public record UploadHeader(String name, String value) {}

    public record UploadIntentView(
            MutationResult mutation,
            Long fileId,
            Long fileRowVersion,
            String status,
            String uploadMethod,
            String uploadUrl,
            String uploadToken,
            List<UploadHeader> requiredHeaders,
            Instant expiresAt,
            Integer maxBytes) {}

    public record DownloadView(
            String url, Instant expiresAt, String fileName, String mediaType, Integer sizeBytes) {}
}
