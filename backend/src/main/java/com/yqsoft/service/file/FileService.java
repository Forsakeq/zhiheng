package com.yqsoft.service.file;

import static com.yqsoft.resource.common.Rows.*;

import com.yqsoft.dao.SqlStore;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.persistence.MutationExecutor;
import com.yqsoft.resource.common.Ids;
import com.yqsoft.resource.common.MutationResult;
import com.yqsoft.resource.common.ValidationSupport;
import com.yqsoft.resource.file.FileDtos.*;
import com.yqsoft.security.AuthContext;
import com.yqsoft.service.common.CryptoService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

/** Local private object adapter. No filesystem path is accepted from a client. */
@Service
public class FileService {
    private final SqlStore store;
    private final MutationExecutor mutations;
    private final CryptoService crypto;
    private final ValidationSupport validation;
    private final Path root;
    private final String publicBaseUrl;
    private final String scannerCommand;
    private final TransactionTemplate transactions;

    public FileService(
            SqlStore store,
            MutationExecutor mutations,
            CryptoService crypto,
            ValidationSupport validation,
            @Value("${zhiheng.files.root:.local/files}") String root,
            @Value("${zhiheng.public-base-url:http://127.0.0.1:8080}") String publicBaseUrl,
            @Value("${zhiheng.files.scanner-command:}") String scannerCommand,
            PlatformTransactionManager transactionManager) {
        this.store = store;
        this.mutations = mutations;
        this.crypto = crypto;
        this.validation = validation;
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
        this.scannerCommand = scannerCommand;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public UploadIntentView createIntent(UploadIntentCreate request) {
        validation.validate(request);
        if (request.purpose() == Purpose.VOICE_TEMP || request.purpose() == Purpose.COURSE_IMAGE) {
            throw BusinessException.unavailable("FEATURE_NOT_ENABLED", "语音和课表导入尚未启用");
        }
        long max =
                request.purpose() == Purpose.AVATAR
                        ? 2L * 1024 * 1024
                        : request.mediaType().startsWith("image/")
                                ? 10L * 1024 * 1024
                                : 20L * 1024 * 1024;
        if (request.sizeBytes() > max) {
            throw BusinessException.badRequest("FILE_TOO_LARGE", "文件超出用途允许大小");
        }
        if (request.purpose() == Purpose.AVATAR && !request.mediaType().startsWith("image/")) {
            throw BusinessException.badRequest("INVALID_AVATAR_TYPE", "头像必须是图片");
        }
        AtomicReference<String> rawToken = new AtomicReference<>();
        MutationResult result =
                mutations.mutate(
                        "CREATE_UPLOAD_INTENT",
                        request,
                        true,
                        () -> {
                            if (store.count(
                                            "SELECT COUNT(*) FROM file_object WHERE user_id=? AND"
                                                    + " file_key=?",
                                            AuthContext.userId(),
                                            request.clientFileKey().toString())
                                    != 0) {
                                throw BusinessException.conflict(
                                        "FILE_KEY_EXISTS", "文件意图已存在，请使用原操作回执");
                            }
                            String token = crypto.randomToken();
                            rawToken.set(token);
                            Instant expires = Instant.now().plusSeconds(900);
                            String key = UUID.randomUUID().toString();
                            long id =
                                    store.insert(
                                            "file_object",
                                            map(
                                                    "user_id",
                                                    AuthContext.userId(),
                                                    "file_key",
                                                    request.clientFileKey().toString(),
                                                    "purpose",
                                                    request.purpose().name(),
                                                    "storage_provider",
                                                    "LOCAL_PRIVATE",
                                                    "bucket_code",
                                                    "private",
                                                    "object_key",
                                                    key,
                                                    "original_name_cipher",
                                                    crypto.encrypt(request.originalName()),
                                                    "status",
                                                    "UPLOADING",
                                                    "encryption_key_ref",
                                                    crypto.keyRef(),
                                                    "upload_expires_at",
                                                    expires,
                                                    "upload_token_hash",
                                                    crypto.hashToken(token),
                                                    "expected_byte_size",
                                                    request.sizeBytes(),
                                                    "expected_sha256",
                                                    HexFormat.of().parseHex(request.sha256()),
                                                    "declared_mime_type",
                                                    request.mediaType()));
                            return MutationResult.of("FILE", id, 1);
                        });
        Map<String, Object> row =
                store.owned("file_object", Ids.parse(result.id()), AuthContext.userId());
        Instant expiry = instant(row, "upload_expires_at");
        if (expiry == null
                || expiry.isBefore(Instant.now())
                || !"UPLOADING".equals(text(row, "status"))) {
            throw BusinessException.conflict("UPLOAD_EXPIRED", "上传意图已过期或已完成");
        }
        String url = signedUrl(row, expiry, "upload", "INLINE");
        return new UploadIntentView(
                result,
                number(row, "id"),
                number(row, "row_version"),
                "UPLOADING",
                "PUT",
                url,
                rawToken.get(),
                List.of(
                        new UploadHeader("Content-Type", text(row, "declared_mime_type")),
                        new UploadHeader(
                                "x-checksum-sha256",
                                HexFormat.of().formatHex((byte[]) row.get("expected_sha256")))),
                expiry,
                integer(row, "expected_byte_size"));
    }

    public void receiveContent(
            long id, long expires, long epoch, String signature, InputStream body) {
        Map<String, Object> row = signedRow(id, expires, epoch, signature, "upload", "INLINE");
        if (!"UPLOADING".equals(text(row, "status"))
                || instant(row, "upload_expires_at").isBefore(Instant.now())) {
            throw BusinessException.conflict("UPLOAD_EXPIRED", "上传意图已失效");
        }
        Path target = objectPath(text(row, "object_key"));
        Path temporary =
                target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        long maximum = number(row, "expected_byte_size");
        try {
            Files.createDirectories(root);
            byte[] plaintext;
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                long count = 0;
                int read;
                while ((read = body.read(buffer)) >= 0) {
                    count += read;
                    if (count > maximum)
                        throw BusinessException.badRequest("FILE_TOO_LARGE", "上传内容超过声明大小");
                    output.write(buffer, 0, read);
                }
                if (count != maximum)
                    throw BusinessException.badRequest("FILE_SIZE_MISMATCH", "上传内容大小不符");
                plaintext = output.toByteArray();
            }
            Files.write(
                    temporary,
                    crypto.encrypt(Base64.getEncoder().encodeToString(plaintext)),
                    StandardOpenOption.CREATE_NEW);
            // CREATE_NEW semantics prevent a signed upload from replacing verified bytes.
            transactions.executeWithoutResult(
                    transaction -> {
                        long owner = number(row, "user_id");
                        Map<String, Object> clock =
                                store.one(
                                        "SELECT * FROM user_sync_clock WHERE user_id=? FOR UPDATE",
                                        owner);
                        Map<String, Object> account =
                                store.one(
                                        "SELECT status,data_write_fence FROM app_user WHERE id=?"
                                                + " FOR UPDATE",
                                        owner);
                        Map<String, Object> current =
                                store.one(
                                        "SELECT * FROM file_object WHERE id=? AND user_id=? AND"
                                                + " deleted_at IS NULL FOR UPDATE",
                                        id,
                                        owner);
                        if (epoch != number(clock, "dataset_epoch")
                                || !"ACTIVE".equals(text(account, "status"))
                                || !"OPEN".equals(text(account, "data_write_fence"))
                                || !"UPLOADING".equals(text(current, "status"))
                                || instant(current, "upload_expires_at").isBefore(Instant.now())
                                || expires < Instant.now().getEpochSecond()) {
                            throw BusinessException.conflict("DATA_EPOCH_CHANGED", "数据已清理或上传授权已失效");
                        }
                        if (Files.exists(target))
                            throw BusinessException.conflict(
                                    "FILE_ALREADY_UPLOADED", "内容已上传，请完成核验");
                        try {
                            Files.move(temporary, target);
                        } catch (IOException e) {
                            throw BusinessException.unavailable("FILE_STORAGE_ERROR", "文件暂不可写");
                        }
                    });
        } catch (IOException e) {
            throw BusinessException.unavailable("FILE_STORAGE_ERROR", "私有文件暂不可写");
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
        }
    }

    public MutationResult complete(long id, FileUploadComplete request) {
        validation.validate(request);
        Map<String, Object> before = store.owned("file_object", id, AuthContext.userId());
        if (!"UPLOADING".equals(text(before, "status"))) {
            // Idempotency replay is resolved inside mutate; no raw token goes into its receipt.
            return completeMutation(id, request, null, null);
        }
        byte[] bytes;
        bytes = readContent(before);
        if (bytes.length > 20 * 1024 * 1024)
            throw BusinessException.badRequest("FILE_TOO_LARGE", "文件超出上限");
        byte[] digest = crypto.sha256(bytes);
        if (bytes.length != request.sizeBytes()
                || bytes.length != number(before, "expected_byte_size")
                || !MessageDigest.isEqual(digest, HexFormat.of().parseHex(request.sha256()))
                || !MessageDigest.isEqual(digest, (byte[]) before.get("expected_sha256"))) {
            throw BusinessException.badRequest("FILE_INTEGRITY_MISMATCH", "文件大小或摘要核验失败");
        }
        String mime = inspect(bytes, text(before, "declared_mime_type"));
        scan(objectPath(text(before, "object_key")), bytes, mime);
        return completeMutation(id, request, digest, mime);
    }

    private MutationResult completeMutation(
            long id, FileUploadComplete request, byte[] digest, String mime) {
        // The canonical request omits bearer uploadToken; its hash is stable and cannot be logged
        // as a token.
        return mutations.mutate(
                "COMPLETE_FILE_UPLOAD",
                map(
                        "id",
                        id,
                        "expectedRowVersion",
                        request.expectedRowVersion(),
                        "sha256",
                        request.sha256(),
                        "sizeBytes",
                        request.sizeBytes(),
                        "proofHash",
                        HexFormat.of().formatHex(crypto.hashToken(request.uploadToken()))),
                true,
                () -> {
                    Map<String, Object> row =
                            store.lockOwned("file_object", id, AuthContext.userId());
                    if (!"UPLOADING".equals(text(row, "status"))
                            || row.get("upload_token_consumed_at") != null
                            || digest == null) {
                        throw BusinessException.conflict(
                                "UPLOAD_ALREADY_COMPLETED", "该文件已核验，不能重复消费凭据");
                    }
                    if (instant(row, "upload_expires_at").isBefore(Instant.now())
                            || !MessageDigest.isEqual(
                                    (byte[]) row.get("upload_token_hash"),
                                    crypto.hashToken(request.uploadToken()))) {
                        throw BusinessException.badRequest("INVALID_UPLOAD_TOKEN", "上传凭据无效或过期");
                    }
                    long version =
                            store.update(
                                    "file_object",
                                    id,
                                    Ids.parse(request.expectedRowVersion()),
                                    map(
                                            "byte_size",
                                            request.sizeBytes(),
                                            "sha256",
                                            digest,
                                            "mime_type",
                                            mime,
                                            "status",
                                            "CLEAN",
                                            "completed_at",
                                            Instant.now(),
                                            "upload_token_consumed_at",
                                            Instant.now()));
                    return MutationResult.of("FILE", id, version);
                });
    }

    public DownloadView download(long id, FileDownloadRequest request) {
        Map<String, Object> row = requireClean(id, null);
        if (Set.of("EXPORT", "PRIVACY_MANIFEST").contains(text(row, "purpose"))) {
            throw BusinessException.forbidden("RESTRICTED_FILE", "此文件必须经数据控制流程下载");
        }
        Instant expires = Instant.now().plusSeconds(300);
        String disposition =
                request == null || request.disposition() == null
                        ? "INLINE"
                        : request.disposition().name();
        return new DownloadView(
                signedUrl(row, expires, "download", disposition),
                expires,
                crypto.decrypt(row.get("original_name_cipher")),
                text(row, "mime_type"),
                integer(row, "byte_size"));
    }

    /** Caller must have performed the privacy export ownership/reauthentication checks. */
    public DownloadView exportDownload(long id) {
        Map<String, Object> row = requireClean(id, "EXPORT");
        Instant expires = Instant.now().plusSeconds(60);
        return new DownloadView(
                signedUrl(row, expires, "download", "ATTACHMENT"),
                expires,
                crypto.decrypt(row.get("original_name_cipher")),
                text(row, "mime_type"),
                integer(row, "byte_size"));
    }

    public Map<String, Object> signedDownloadRow(
            long id, long expires, long epoch, String signature, String disposition) {
        Map<String, Object> row = signedRow(id, expires, epoch, signature, "download", disposition);
        if (!"CLEAN".equals(text(row, "status"))) throw BusinessException.notFound();
        return row;
    }

    public MutationResult deleteUnused(long id, VersionBody request) {
        validation.validate(request);
        return mutations.mutate(
                "DELETE_UNUSED_FILE",
                map("id", id, "request", request),
                true,
                () -> {
                    Map<String, Object> row =
                            store.lockOwned("file_object", id, AuthContext.userId());
                    if (Set.of("EXPORT", "PRIVACY_MANIFEST").contains(text(row, "purpose"))) {
                        throw BusinessException.forbidden("RESTRICTED_FILE", "此文件由数据控制工单管理");
                    }
                    if (store.count(
                                            "SELECT COUNT(*) FROM record_attachment WHERE user_id=?"
                                                    + " AND file_id=?",
                                            AuthContext.userId(),
                                            id)
                                    > 0
                            || store.count(
                                            "SELECT COUNT(*) FROM user_profile WHERE user_id=? AND"
                                                    + " avatar_file_id=?",
                                            AuthContext.userId(),
                                            id)
                                    > 0
                            || store.count(
                                            "SELECT COUNT(*) FROM feedback_ticket WHERE user_id=?"
                                                + " AND deleted_at IS NULL AND"
                                                + " JSON_CONTAINS(attachment_file_ids_json,JSON_QUOTE(?))",
                                            AuthContext.userId(),
                                            Long.toString(id))
                                    > 0) {
                        throw BusinessException.conflict("FILE_IN_USE", "文件仍被业务内容引用");
                    }
                    long version =
                            store.update(
                                    "file_object",
                                    id,
                                    Ids.parse(request.expectedRowVersion()),
                                    map("status", "DELETING", "deleted_at", Instant.now()));
                    mutations.outbox(
                            "FILE_DELETE_REQUESTED",
                            "FILE",
                            id,
                            version,
                            map("fileId", Long.toString(id)));
                    try {
                        Files.deleteIfExists(objectPath(text(row, "object_key")));
                    } catch (IOException e) {
                        throw BusinessException.unavailable("FILE_DELETE_FAILED", "文件暂时无法清理，请重试");
                    }
                    store.execute(
                            "UPDATE file_object SET status='DELETED',deleted_from_storage_at=?"
                                    + " WHERE id=?",
                            utc(Instant.now()),
                            id);
                    store.execute(
                            "UPDATE outbox_event SET status='DONE',processed_at=?,updated_at=?"
                                    + " WHERE user_id=? AND event_type='FILE_DELETE_REQUESTED' AND"
                                    + " aggregate_id=? AND status='PENDING'",
                            utc(Instant.now()),
                            utc(Instant.now()),
                            AuthContext.userId(),
                            id);
                    return MutationResult.of("FILE", id, version);
                });
    }

    public Map<String, Object> requireClean(long id, String purpose) {
        Map<String, Object> row = store.owned("file_object", id, AuthContext.userId());
        if (!"CLEAN".equals(text(row, "status"))
                || (purpose != null && !purpose.equals(text(row, "purpose")))) {
            throw BusinessException.badRequest("FILE_NOT_AVAILABLE", "文件未核验完成或用途不匹配");
        }
        assertRetention(row);
        return row;
    }

    /** Server-generated exports/manifests use the same encrypted private adapter. */
    public long createServerFile(
            long userId,
            String purpose,
            String originalName,
            String mediaType,
            byte[] content,
            Instant retentionUntil) {
        return createServerFile(
                userId, purpose, originalName, mediaType, content, retentionUntil, null);
    }

    public long createServerFile(
            long userId,
            String purpose,
            String originalName,
            String mediaType,
            byte[] content,
            Instant retentionUntil,
            Long expectedEpoch) {
        if (!Set.of("EXPORT", "PRIVACY_MANIFEST").contains(purpose)) {
            throw new IllegalArgumentException(
                    "Only controlled export/manifest generation is allowed");
        }
        if (content == null || content.length > 64 * 1024 * 1024) {
            throw BusinessException.badRequest("SERVER_FILE_TOO_LARGE", "生成文件超出单文件限制");
        }
        String key = UUID.randomUUID().toString();
        Path path = objectPath(key);
        byte[] encrypted = crypto.encrypt(Base64.getEncoder().encodeToString(content));
        byte[] digest = crypto.sha256(content);
        try {
            return transactions.execute(
                    transaction -> {
                        Map<String, Object> clock =
                                store.one(
                                        "SELECT * FROM user_sync_clock WHERE user_id=? FOR UPDATE",
                                        userId);
                        Map<String, Object> account =
                                store.one(
                                        "SELECT status,data_write_fence FROM app_user WHERE id=?"
                                                + " FOR UPDATE",
                                        userId);
                        if (!"ACTIVE".equals(text(account, "status"))
                                || !"OPEN".equals(text(account, "data_write_fence"))
                                || expectedEpoch != null
                                        && expectedEpoch != number(clock, "dataset_epoch")) {
                            throw BusinessException.conflict("DATA_EPOCH_CHANGED", "数据已清理或导出授权已失效");
                        }
                        try {
                            Files.createDirectories(root);
                            Files.write(path, encrypted, StandardOpenOption.CREATE_NEW);
                        } catch (IOException e) {
                            throw BusinessException.unavailable("FILE_STORAGE_ERROR", "私有文件暂不可写");
                        }
                        return store.insert(
                                "file_object",
                                map(
                                        "user_id",
                                        userId,
                                        "file_key",
                                        UUID.randomUUID().toString(),
                                        "purpose",
                                        purpose,
                                        "storage_provider",
                                        "LOCAL_PRIVATE",
                                        "bucket_code",
                                        "private",
                                        "object_key",
                                        key,
                                        "original_name_cipher",
                                        crypto.encrypt(originalName),
                                        "mime_type",
                                        mediaType,
                                        "byte_size",
                                        content.length,
                                        "sha256",
                                        digest,
                                        "status",
                                        "CLEAN",
                                        "encryption_key_ref",
                                        crypto.keyRef(),
                                        "completed_at",
                                        Instant.now(),
                                        "retention_until",
                                        retentionUntil,
                                        "restricted_hold_until",
                                        "PRIVACY_MANIFEST".equals(purpose) ? retentionUntil : null,
                                        "expected_byte_size",
                                        content.length,
                                        "expected_sha256",
                                        digest,
                                        "declared_mime_type",
                                        mediaType));
                    });
        } catch (RuntimeException e) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    public byte[] readContent(Map<String, Object> row) {
        try {
            Path path = objectPath(text(row, "object_key"));
            if (Files.size(path) > 90L * 1024 * 1024)
                throw BusinessException.unavailable("FILE_STORAGE_CORRUPTED", "私有文件体积异常");
            byte[] plaintext = Base64.getDecoder().decode(crypto.decrypt(Files.readAllBytes(path)));
            if (row.get("sha256") instanceof byte[] expected
                    && !MessageDigest.isEqual(expected, crypto.sha256(plaintext))) {
                throw BusinessException.unavailable("FILE_INTEGRITY_ERROR", "文件完整性核验失败");
            }
            return plaintext;
        } catch (IOException e) {
            throw BusinessException.conflict("UPLOAD_INCOMPLETE", "文件内容尚未上传或已清理");
        } catch (IllegalArgumentException e) {
            throw BusinessException.unavailable("FILE_STORAGE_CORRUPTED", "文件加密内容无法验证");
        }
    }

    public Path objectPath(String objectKey) {
        if (objectKey == null || !objectKey.matches("[A-Za-z0-9_-]{1,100}"))
            throw BusinessException.notFound();
        Path path = root.resolve(objectKey).normalize();
        if (!path.startsWith(root)) throw BusinessException.notFound();
        return path;
    }

    private String signedUrl(
            Map<String, Object> row, Instant expiry, String action, String disposition) {
        long epoch =
                store.count(
                        "SELECT dataset_epoch FROM user_sync_clock WHERE user_id=?",
                        number(row, "user_id"));
        String signature =
                signature(
                        number(row, "id"),
                        number(row, "user_id"),
                        expiry.getEpochSecond(),
                        epoch,
                        action,
                        disposition);
        return publicBaseUrl
                + "/api/v1/files/"
                + number(row, "id")
                + "/content?expires="
                + expiry.getEpochSecond()
                + "&epoch="
                + epoch
                + "&disposition="
                + disposition
                + "&signature="
                + signature;
    }

    private Map<String, Object> signedRow(
            long id, long expires, long epoch, String provided, String action, String disposition) {
        if (expires < Instant.now().getEpochSecond()
                || expires > Instant.now().plusSeconds(901).getEpochSecond()) {
            throw BusinessException.forbidden("FILE_LINK_EXPIRED", "文件链接已过期");
        }
        Map<String, Object> row =
                store.one("SELECT * FROM file_object WHERE id=? AND deleted_at IS NULL", id);
        long owner = number(row, "user_id");
        Map<String, Object> user =
                store.one("SELECT status,data_write_fence FROM app_user WHERE id=?", owner);
        long currentEpoch =
                store.count("SELECT dataset_epoch FROM user_sync_clock WHERE user_id=?", owner);
        if (!"ACTIVE".equals(text(user, "status"))
                || !"OPEN".equals(text(user, "data_write_fence"))
                || epoch != currentEpoch
                || provided == null
                || !MessageDigest.isEqual(
                        signature(id, owner, expires, epoch, action, disposition)
                                .getBytes(StandardCharsets.US_ASCII),
                        provided.getBytes(StandardCharsets.US_ASCII))) {
            throw BusinessException.forbidden("INVALID_FILE_LINK", "文件链接无效");
        }
        if ("download".equals(action)) {
            assertRetention(row);
        }
        return row;
    }

    private void assertRetention(Map<String, Object> row) {
        Instant retention = instant(row, "retention_until");
        if (retention != null && !retention.isAfter(Instant.now())) {
            throw new BusinessException(410, "FILE_RETENTION_EXPIRED", "文件已超过保留期");
        }
    }

    private String signature(
            long id, long owner, long expiry, long epoch, String action, String disposition) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        crypto.hmac(
                                "private-file-url",
                                id
                                        + "|"
                                        + owner
                                        + "|"
                                        + expiry
                                        + "|"
                                        + epoch
                                        + "|"
                                        + action
                                        + "|"
                                        + disposition));
    }

    public static String inspect(byte[] bytes, String declared) {
        boolean valid =
                switch (declared) {
                    case "image/png" ->
                            bytes.length >= 8
                                    && bytes[0] == (byte) 137
                                    && bytes[1] == 80
                                    && bytes[2] == 78
                                    && bytes[3] == 71;
                    case "image/jpeg" ->
                            bytes.length >= 4
                                    && bytes[0] == (byte) 255
                                    && bytes[1] == (byte) 216
                                    && bytes[2] == (byte) 255;
                    case "image/webp" ->
                            bytes.length >= 12
                                    && new String(bytes, 0, 4, StandardCharsets.US_ASCII)
                                            .equals("RIFF")
                                    && new String(bytes, 8, 4, StandardCharsets.US_ASCII)
                                            .equals("WEBP");
                    case "audio/wav" ->
                            bytes.length >= 12
                                    && new String(bytes, 0, 4, StandardCharsets.US_ASCII)
                                            .equals("RIFF")
                                    && new String(bytes, 8, 4, StandardCharsets.US_ASCII)
                                            .equals("WAVE");
                    case "audio/mpeg" ->
                            bytes.length >= 3
                                    && (new String(bytes, 0, 3, StandardCharsets.US_ASCII)
                                                    .equals("ID3")
                                            || (bytes[0] == (byte) 255 && (bytes[1] & 224) == 224));
                    case "application/pdf" ->
                            bytes.length >= 5
                                    && new String(bytes, 0, 5, StandardCharsets.US_ASCII)
                                            .equals("%PDF-");
                    default -> false;
                };
        if (!valid) throw BusinessException.badRequest("FILE_SIGNATURE_MISMATCH", "文件内容签名与声明类型不符");
        return declared;
    }

    private void scan(Path path, byte[] bytes, String mime) {
        if (mime.equals("image/png") || mime.equals("image/jpeg")) {
            try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext())
                    throw BusinessException.badRequest("INVALID_IMAGE", "图片无法解码");
                var reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 1 || height < 1 || (long) width * height > 40_000_000) {
                        throw BusinessException.badRequest("INVALID_IMAGE", "图片像素过大");
                    }
                    if (reader.read(0) == null)
                        throw BusinessException.badRequest("INVALID_IMAGE", "图片无法解码");
                } finally {
                    reader.dispose();
                }
            } catch (IOException e) {
                throw BusinessException.badRequest("INVALID_IMAGE", "图片无法安全解码");
            }
        } else if (scannerCommand.isBlank()) {
            throw BusinessException.unavailable(
                    "FILE_SCANNER_NOT_CONFIGURED", "此类型需要配置实际文件扫描器后才能使用");
        }
        if (!scannerCommand.isBlank()) {
            Path scanFile = null;
            try {
                scanFile = Files.createTempFile(root, "scan-", ".tmp");
                Files.write(scanFile, bytes);
                Process process =
                        new ProcessBuilder(scannerCommand, "--no-summary", scanFile.toString())
                                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                .redirectError(ProcessBuilder.Redirect.DISCARD)
                                .start();
                if (!process.waitFor(15, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw BusinessException.unavailable("FILE_SCAN_TIMEOUT", "文件扫描超时");
                }
                if (process.exitValue() == 1)
                    throw BusinessException.badRequest("UNSAFE_FILE", "文件未通过安全扫描");
                if (process.exitValue() != 0)
                    throw BusinessException.unavailable("FILE_SCAN_FAILED", "文件扫描服务不可用");
            } catch (IOException e) {
                throw BusinessException.unavailable("FILE_SCAN_FAILED", "文件扫描服务不可用");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw BusinessException.unavailable("FILE_SCAN_INTERRUPTED", "文件扫描已中断");
            } finally {
                if (scanFile != null)
                    try {
                        Files.deleteIfExists(scanFile);
                    } catch (IOException ignored) {
                    }
            }
        }
    }
}
