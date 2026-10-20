package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 事务事件待办。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("outbox_event")
public class OutboxEventPo {

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

    /** 个人事件必填，全局内容维护可空 */
    @TableField("user_id")
    private Long userId;

    /** 全局事件 UUID */
    @TableField("event_key")
    private String eventKey;

    /** STAT_INVALIDATED / REMINDER_CANCEL / AI_RUN_QUEUED / PRIVACY_REQUESTED 等白名单 */
    @TableField("event_type")
    private String eventType;

    /** 所属实体类型 */
    @TableField("aggregate_type")
    private String aggregateType;

    /** 实体 ID，删除事件不依赖被删实体 FK */
    @TableField("aggregate_id")
    private Long aggregateId;

    /** 事件所见行版本 */
    @TableField("aggregate_row_version")
    private Long aggregateRowVersion;

    /** 事件协议版本 */
    @TableField("payload_schema_version")
    private Integer payloadSchemaVersion;

    /** 最小 ID/日期/动作；敏感内容仅加密 envelope，禁止完整对话 */
    @TableField("payload_json")
    private String payloadJson;

    /** PENDING / CLAIMED / DONE / RETRY / DEAD / CANCELLED */
    @TableField("status")
    private String status;

    /** 下次可消费时间 */
    @TableField("available_at")
    private LocalDateTime availableAt;

    /** 尝试次数 */
    @TableField("attempt_count")
    private Integer attemptCount;

    /** worker 实例标识 */
    @TableField("lease_owner")
    private String leaseOwner;

    /** 占用租约，崩溃后可重新领取 */
    @TableField("lease_until")
    private LocalDateTime leaseUntil;

    /** 完成时间 */
    @TableField("processed_at")
    private LocalDateTime processedAt;

    /** 脱敏错误码 */
    @TableField("last_error_code")
    private String lastErrorCode;

    /** 健康相关事件必填 */
    @TableField("captured_dataset_epoch")
    private Long capturedDatasetEpoch;

    /** 依赖 AI/通知授权时必填 */
    @TableField("captured_consent_version")
    private Long capturedConsentVersion;

    /** 授权版本对应用途 */
    @TableField("consent_purpose")
    private String consentPurpose;

    /** 依赖外部连接时捕获连接授权修订 */
    @TableField("authorization_revision")
    private Long authorizationRevision;
}
