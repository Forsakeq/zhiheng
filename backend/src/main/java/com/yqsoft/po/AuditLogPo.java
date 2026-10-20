package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 最小安全审计。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("audit_log")
public class AuditLogPo {

    /** 内部主键；API使用十进制字符串 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 服务端写入UTC创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 操作账号，系统作业可空 */
    @TableField("actor_user_id")
    private Long actorUserId;

    /** 受影响账号，后台内容操作可空 */
    @TableField("subject_user_id")
    private Long subjectUserId;

    /** USER / ADMIN / WORKER / ANONYMOUS */
    @TableField("actor_type")
    private String actorType;

    /** LOGIN_FAILURE / DATA_EXPORT / CONSENT_REVOKE / CONTENT_PUBLISH 等 */
    @TableField("action_code")
    private String actionCode;

    /** 资源白名单 */
    @TableField("resource_type")
    private String resourceType;

    /** 最小内部 ID，不做弱权限查询入口 */
    @TableField("resource_id")
    private Long resourceId;

    /** 服务请求追踪 UUID */
    @TableField("request_id")
    private String requestId;

    /** 业务幂等编号 */
    @TableField("operation_id")
    private String operationId;

    /** SUCCESS / DENIED / FAILED */
    @TableField("outcome")
    private String outcome;

    /** 规则/错误编号 */
    @TableField("reason_code")
    private String reasonCode;

    /** 只允许字段名、版本、计数和安全码，不含内容原文 */
    @TableField("metadata_json")
    private String metadataJson;

    /** 短保留域分离 HMAC */
    @TableField("ip_hash")
    private byte[] ipHash;

    /** 按事件类型配置保留期 */
    @TableField("retention_until")
    private LocalDateTime retentionUntil;
}
