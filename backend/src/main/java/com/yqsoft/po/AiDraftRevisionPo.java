package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 草稿不可变修订。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("ai_draft_revision")
public class AiDraftRevisionPo {

    /** 内部主键；API使用十进制字符串 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 服务端写入UTC创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 账号 */
    @TableField("user_id")
    private Long userId;

    /** 草稿 FK */
    @TableField("draft_id")
    private Long draftId;

    /** 草稿内 1 起连续递增 */
    @TableField("revision_no")
    private Integer revisionNo;

    /** 用户编辑基于的上一修订 */
    @TableField("source_revision")
    private Integer sourceRevision;

    /** MODEL / USER / REVALIDATION */
    @TableField("edit_source")
    private String editSource;

    /** 结构化动作协议版本 */
    @TableField("payload_schema_version")
    private Integer payloadSchemaVersion;

    /** 加密 envelope（alg/keyRef/nonce/ciphertext），解密后按 kind schema 校验；不明文落健康描述 */
    @TableField("payload_json")
    private String payloadJson;

    /** 最小资源 ID/rowVersion/计划 activeVersion/epoch，禁止复制无关历史 */
    @TableField("base_snapshot_json")
    private String baseSnapshotJson;

    /** NOT_VALIDATED / NEEDS_INPUT / VALID / INVALID */
    @TableField("validation_status")
    private String validationStatus;

    /** 字段码、规则码、缺失列表、冲突 ID、校验版本，不保存敏感原文 */
    @TableField("validation_report_json")
    private String validationReportJson;

    /** 该修订校验结束时间 */
    @TableField("validated_at")
    private LocalDateTime validatedAt;

    /** 规范化解密内容的用户域 HMAC */
    @TableField("payload_digest")
    private byte[] payloadDigest;
}
