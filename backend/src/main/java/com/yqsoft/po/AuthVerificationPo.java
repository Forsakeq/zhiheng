package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次性身份验证挑战。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("auth_verification")
public class AuthVerificationPo {

    /** 内部主键；API使用十进制字符串 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 服务端写入UTC创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 随机挑战编号 */
    @TableField("challenge_id")
    private String challengeId;

    /** 未注册场景可空 */
    @TableField("user_id")
    private Long userId;

    /** REGISTER / RESET_PASSWORD / REAUTH / CONTACT_CHANGE */
    @TableField("purpose")
    private String purpose;

    /** P0 固定 EMAIL；其他验证渠道启用时另行迁移/校验，不提供伪短信入口 */
    @TableField("channel")
    private String channel;

    /** 目标地址域分离 HMAC */
    @TableField("destination_hash")
    private byte[] destinationHash;

    /** 确需短期投递时加密地址 */
    @TableField("destination_cipher")
    private byte[] destinationCipher;

    /** 验证码加盐/HMAC，不保存原码 */
    @TableField("proof_hash")
    private byte[] proofHash;

    /** 短期收件地址密钥引用 */
    @TableField("encryption_key_ref")
    private String encryptionKeyRef;

    /** 挑战过期，建议验证码 5–10 分钟 */
    @TableField("expires_at")
    private LocalDateTime expiresAt;

    /** 失败尝试次数 */
    @TableField("attempt_count")
    private Integer attemptCount;

    /** 本挑战尝试上限 */
    @TableField("max_attempts")
    private Integer maxAttempts;

    /** 成功消费，单次使用 */
    @TableField("consumed_at")
    private LocalDateTime consumedAt;

    /** 超尝试上限时间 */
    @TableField("locked_at")
    private LocalDateTime lockedAt;

    /** 短期防滥用摘要 */
    @TableField("request_ip_hash")
    private byte[] requestIpHash;

    /** 重发/安全撤销使旧挑战作废的时间；验证须为空 */
    @TableField("invalidated_at")
    private LocalDateTime invalidatedAt;

    /** MOBILE / ADMIN；REAUTH凭证只能用于原audience */
    @TableField("client_audience")
    private String clientAudience;
}
