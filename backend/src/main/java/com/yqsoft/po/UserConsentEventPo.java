package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 授权变更证据。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("user_consent_event")
public class UserConsentEventPo {

    /** 内部主键；API使用十进制字符串 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 服务端写入UTC创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 授权主体 */
    @TableField("user_id")
    private Long userId;

    /** 当前授权行 FK */
    @TableField("consent_id")
    private Long consentId;

    /** GRANT / REVOKE / UPDATE_SCOPE / EXPIRE */
    @TableField("event_type")
    private String eventType;

    /** 变化后的授权版本 */
    @TableField("consent_version")
    private Long consentVersion;

    /** 当时所见说明版本 */
    @TableField("policy_version")
    private String policyVersion;

    /** 当时生效范围白名单 */
    @TableField("scope_json")
    private String scopeJson;

    /** 服务器确认时间 */
    @TableField("occurred_at")
    private LocalDateTime occurredAt;

    /** 动作设备 */
    @TableField("installation_id")
    private Long installationId;

    /** 可重试操作号 */
    @TableField("operation_id")
    private String operationId;

    /** 被展示授权说明的摘要 */
    @TableField("notice_digest")
    private byte[] noticeDigest;
}
