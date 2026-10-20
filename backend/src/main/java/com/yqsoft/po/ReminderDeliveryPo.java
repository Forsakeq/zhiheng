package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次系统提醒调度/投递尝试。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("reminder_delivery")
public class ReminderDeliveryPo {

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

    /** 提醒规则 */
    @TableField("rule_id")
    private Long ruleId;

    /** 提醒所对应任务 */
    @TableField("task_id")
    private Long taskId;

    /** 注册时任务版本 */
    @TableField("task_row_version")
    private Long taskRowVersion;

    /** 目标设备 */
    @TableField("installation_id")
    private Long installationId;

    /** LOCAL_SYSTEM / IN_APP */
    @TableField("channel")
    private String channel;

    /** 计算后的 UTC 触发时间 */
    @TableField("fire_at")
    private LocalDateTime fireAt;

    /** taskId+taskVersion+ruleVersion+channel+installation+fireAt 的规范摘要 */
    @TableField("dedup_key")
    private byte[] dedupKey;

    /**
     * PENDING / REGISTERED / DELIVERED / UNKNOWN / FAILED / CANCEL_REQUESTED / CANCELLED / EXPIRED
     */
    @TableField("status")
    private String status;

    /** 平台返回的撤销句柄密文，不在日志泄露 */
    @TableField("system_handle_cipher")
    private byte[] systemHandleCipher;

    /** 密钥引用 */
    @TableField("encryption_key_ref")
    private String encryptionKeyRef;

    /** 系统确认注册，不代表已经展示 */
    @TableField("registered_at")
    private LocalDateTime registeredAt;

    /** 仅有可信系统回调时填写 */
    @TableField("delivered_at")
    private LocalDateTime deliveredAt;

    /** 撤销确认时间 */
    @TableField("cancelled_at")
    private LocalDateTime cancelledAt;

    /** 有限重试次数 */
    @TableField("attempt_count")
    private Integer attemptCount;

    /** 权限/数量限制/能力不支持等结构码 */
    @TableField("last_error_code")
    private String lastErrorCode;

    /** 过期后禁止补投陈旧任务提醒 */
    @TableField("expires_at")
    private LocalDateTime expiresAt;

    /** 调度时健康数据代际 */
    @TableField("captured_dataset_epoch")
    private Long capturedDatasetEpoch;

    /** 调度时通知授权代际 */
    @TableField("captured_consent_version")
    private Long capturedConsentVersion;

    /** 本次调度采用的提醒规则版本，参与 dedup_key */
    @TableField("rule_row_version")
    private Long ruleRowVersion;
}
