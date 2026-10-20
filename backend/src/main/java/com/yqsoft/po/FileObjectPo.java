package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 私有文件与保留策略。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("file_object")
public class FileObjectPo {

    /** 内部主键；API使用十进制字符串 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 服务端写入UTC创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 服务端写入UTC更新时间；不自动替代业务版本 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    /** 乐观锁版本；每次合法变更递增 */
    @TableField("row_version")
    private Long rowVersion;

    /** 普通软删除UTC时间；隐私清除按工单执行 */
    @TableField("deleted_at")
    private LocalDateTime deletedAt;

    /** 文件所属账号 */
    @TableField("user_id")
    private Long userId;

    /** 公开引用 UUID */
    @TableField("file_key")
    private String fileKey;

    /**
     * AVATAR / RECORD_ATTACHMENT / EXPORT / PRIVACY_MANIFEST / COURSE_IMAGE / VOICE_TEMP /
     * FEEDBACK_ATTACHMENT
     */
    @TableField("purpose")
    private String purpose;

    /** LOCAL_PRIVATE / S3_COMPATIBLE 等启用适配器 */
    @TableField("storage_provider")
    private String storageProvider;

    /** 逻辑私有容器，不向客户端直接公开配置 */
    @TableField("bucket_code")
    private String bucketCode;

    /** 随机存储键，无原文件名/手机号 */
    @TableField("object_key")
    private String objectKey;

    /** 确需展示时加密原文件名 */
    @TableField("original_name_cipher")
    private byte[] originalNameCipher;

    /** 由服务器探测、白名单校验 */
    @TableField("mime_type")
    private String mimeType;

    /** 实际大小 */
    @TableField("byte_size")
    private Long byteSize;

    /** 完整性校验，不做跨用户可观测秒传 */
    @TableField("sha256")
    private byte[] sha256;

    /** UPLOADING / SCANNING / CLEAN / REJECTED / DELETING / DELETED */
    @TableField("status")
    private String status;

    /** 对象内容信封加密密钥引用 */
    @TableField("encryption_key_ref")
    private String encryptionKeyRef;

    /** 上传凭证截止 */
    @TableField("upload_expires_at")
    private LocalDateTime uploadExpiresAt;

    /** 服务器确认上传完整时间 */
    @TableField("completed_at")
    private LocalDateTime completedAt;

    /** 普通保留截止 */
    @TableField("retention_until")
    private LocalDateTime retentionUntil;

    /** 隐私清单/删除证明受限保留窗口，不能由普通文件清除删除 */
    @TableField("restricted_hold_until")
    private LocalDateTime restrictedHoldUntil;

    /** 对象真实清除确认 */
    @TableField("deleted_from_storage_at")
    private LocalDateTime deletedFromStorageAt;

    /** 上传完成一次性token的哈希，仅创建意图首次返回原token */
    @TableField("upload_token_hash")
    private byte[] uploadTokenHash;

    /** 完成校验后一次性消费时间 */
    @TableField("upload_token_consumed_at")
    private LocalDateTime uploadTokenConsumedAt;

    /** 上传意图声明大小，服务生成文件时填实际大小 */
    @TableField("expected_byte_size")
    private Long expectedByteSize;

    /** 声明/预期摘要，完成时必须与服务器实际摘要一致 */
    @TableField("expected_sha256")
    private byte[] expectedSha256;

    /** 意图声明类型，不作为实际安全类型证据 */
    @TableField("declared_mime_type")
    private String declaredMimeType;
}
