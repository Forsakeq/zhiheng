package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 刷新令牌代际与会话链。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("auth_session")
public class AuthSessionPo {

    /** 内部主键；API使用十进制字符串 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 服务端写入UTC创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 登录账号 */
    @TableField("user_id")
    private Long userId;

    /** 本机安装记录 FK */
    @TableField("installation_id")
    private Long installationId;

    /** 一次登录生成的随机 UUID，整链一致 */
    @TableField("family_id")
    private String familyId;

    /** 上一代 session FK */
    @TableField("parent_session_id")
    private Long parentSessionId;

    /** 旋转后的下一代 FK */
    @TableField("replaced_by_session_id")
    private Long replacedBySessionId;

    /** 高熵 refresh token 哈希，不存原 token */
    @TableField("token_hash")
    private byte[] tokenHash;

    /** 签发时账号安全版本 */
    @TableField("security_version")
    private Long securityVersion;

    /** 本代签发时间 */
    @TableField("issued_at")
    private LocalDateTime issuedAt;

    /** 本代令牌截止 */
    @TableField("expires_at")
    private LocalDateTime expiresAt;

    /** 绝对会话截止，不因旋转无限续期 */
    @TableField("family_expires_at")
    private LocalDateTime familyExpiresAt;

    /** 首次旋转消费时间 */
    @TableField("consumed_at")
    private LocalDateTime consumedAt;

    /** 撤销生效时间 */
    @TableField("revoked_at")
    private LocalDateTime revokedAt;

    /** LOGOUT / REUSE / PASSWORD_CHANGED / ACCOUNT_DELETED 等 */
    @TableField("revoke_reason")
    private String revokeReason;

    /** 安全页面展示的设备名称，客户端文本需清洗 */
    @TableField("client_label")
    private String clientLabel;

    /** 短期安全排查 IP 的 HMAC，非原 IP */
    @TableField("ip_hash")
    private byte[] ipHash;

    /** 客户端特征摘要，非设备指纹身份 */
    @TableField("user_agent_digest")
    private byte[] userAgentDigest;

    /** MOBILE / ADMIN，签发与refresh旋转严格继承 */
    @TableField("client_audience")
    private String clientAudience;

    /** family内代际，子代=父代+1 */
    @TableField("generation")
    private Integer generation;
}
