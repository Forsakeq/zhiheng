package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 按用途的当前授权。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("user_consent")
public class UserConsentPo {

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

    /** 授权主体 */
    @TableField("user_id")
    private Long userId;

    /** AI_PROCESSING / NOTIFICATION / EXTERNAL_HEALTH */
    @TableField("purpose_code")
    private String purposeCode;

    /** NOT_GRANTED / GRANTED / REVOKED */
    @TableField("status")
    private String status;

    /** 被展示的说明版本 */
    @TableField("policy_version")
    private String policyVersion;

    /** 授权能力白名单，不允许通配全部数据 */
    @TableField("scope_json")
    private String scopeJson;

    /** 每次授权状态或范围变化递增 */
    @TableField("consent_version")
    private Long consentVersion;

    /** 最近允许时间 */
    @TableField("granted_at")
    private LocalDateTime grantedAt;

    /** 最近撤回时间 */
    @TableField("revoked_at")
    private LocalDateTime revokedAt;

    /** 如用途限定有效期 */
    @TableField("expires_at")
    private LocalDateTime expiresAt;

    /** 授权发起设备 */
    @TableField("installation_id")
    private Long installationId;

    /** 最后一条 consent_event */
    @TableField("last_event_id")
    private Long lastEventId;
}
