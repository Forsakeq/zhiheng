package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 生活记录主表。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("health_record")
public class HealthRecordPo {

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

    /** 账号 */
    @TableField("user_id")
    private Long userId;

    /** 首次创建时客户端 UUID，所有来源均需稳定值 */
    @TableField("client_record_id")
    private String clientRecordId;

    /** SLEEP / ACTIVITY / MEAL / ENERGY */
    @TableField("record_type")
    private String recordType;

    /** 发生开始 UTC 时间 */
    @TableField("started_at")
    private LocalDateTime startedAt;

    /** SLEEP/ACTIVITY 必填，瞬时记录可空 */
    @TableField("ended_at")
    private LocalDateTime endedAt;

    /** 冻结业务日；睡眠取起床所在当地日 */
    @TableField("record_local_date")
    private LocalDate recordLocalDate;

    /** 确认后的 IANA 时区 */
    @TableField("record_time_zone")
    private String recordTimeZone;

    /** 发生时相对 UTC 偏移，-840–840 */
    @TableField("utc_offset_minutes")
    private Integer utcOffsetMinutes;

    /** 外部数据声称的原始时区，和确认值分别保留 */
    @TableField("source_timezone")
    private String sourceTimezone;

    /** MANUAL / AI_CONFIRMED / EXTERNAL */
    @TableField("source_type")
    private String sourceType;

    /** EXTERNAL 时必填、同用户外部授权 */
    @TableField("source_connection_id")
    private Long sourceConnectionId;

    /** 外部稳定记录键，不是随机每次导入 */
    @TableField("provider_record_key")
    private String providerRecordKey;

    /** VALID / INVALID；无效不参与统计 */
    @TableField("quality_status")
    private String qualityStatus;

    /** 用户或校验给出的可读原因 */
    @TableField("invalid_reason")
    private String invalidReason;

    /** 用户备注密文，明文上限 2000 字 */
    @TableField("notes_cipher")
    private byte[] notesCipher;

    /** 备注密钥引用 */
    @TableField("encryption_key_ref")
    private String encryptionKeyRef;

    /** 用户确认/已验证导入落库时间 */
    @TableField("confirmed_at")
    private LocalDateTime confirmedAt;

    /** AI_CONFIRMED 时的来源草稿 */
    @TableField("ai_draft_id")
    private Long aiDraftId;

    /** 外部导入批次 */
    @TableField("import_batch_id")
    private Long importBatchId;
}
