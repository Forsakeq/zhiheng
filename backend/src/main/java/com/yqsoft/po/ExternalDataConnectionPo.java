package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 外部数据授权连接。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("external_data_connection")
public class ExternalDataConnectionPo {

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

    /** 已验证提供方代码 */
    @TableField("provider_code")
    private String providerCode;

    /** 本次连接 UUID */
    @TableField("connection_key")
    private String connectionKey;

    /** 提供方用户标识 HMAC */
    @TableField("provider_subject_hash")
    private byte[] providerSubjectHash;

    /** AUTHORIZING / ACTIVE / EXPIRED / REVOKED / ERROR */
    @TableField("status")
    private String status;

    /** 一次性 OAuth state 的 hash，回调事务消费 */
    @TableField("state_hash")
    private byte[] stateHash;

    /** 本次 state/授权交互截止 */
    @TableField("authorization_expires_at")
    private LocalDateTime authorizationExpiresAt;

    /** 真实核准 scope 白名单，不宣称能读未授权字段 */
    @TableField("scopes_json")
    private String scopesJson;

    /** 授权、token 和撤权代际，旧任务必须核验 */
    @TableField("authorization_revision")
    private Long authorizationRevision;

    /** EXTERNAL_HEALTH 同意版本 */
    @TableField("consent_version")
    private Long consentVersion;

    /** 提供方令牌密文 */
    @TableField("access_token_cipher")
    private byte[] accessTokenCipher;

    /** 如提供方允许刷新则保存密文 */
    @TableField("refresh_token_cipher")
    private byte[] refreshTokenCipher;

    /** 外部令牌密钥引用 */
    @TableField("encryption_key_ref")
    private String encryptionKeyRef;

    /** 令牌截止 */
    @TableField("token_expires_at")
    private LocalDateTime tokenExpiresAt;

    /** 授权成功时间 */
    @TableField("authorized_at")
    private LocalDateTime authorizedAt;

    /** 本地停止读取时刻，不等待远程撤销成功 */
    @TableField("revoked_at")
    private LocalDateTime revokedAt;

    /** 最近成功导入时间 */
    @TableField("last_sync_at")
    private LocalDateTime lastSyncAt;

    /** 必要的外部增量游标密文 */
    @TableField("provider_cursor_cipher")
    private byte[] providerCursorCipher;

    /** 脱敏错误 */
    @TableField("last_error_code")
    private String lastErrorCode;

    /**
     * GENERATED ALWAYS AS (CASE WHEN status='ACTIVE' AND deleted_at IS NULL THEN provider_code ELSE
     * NULL END) STORED
     */
    @TableField(
            value = "active_provider",
            insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER,
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private String activeProvider;
}
