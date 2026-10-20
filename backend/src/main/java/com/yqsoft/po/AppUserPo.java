package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 账号主体。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("app_user")
public class AppUserPo {

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

    /** 公开账号编号，创建后不复用 */
    @TableField("account_no")
    private String accountNo;

    /** 自适应密码哈希，第三方登录账号可空 */
    @TableField("password_hash")
    private String passwordHash;

    /** 密码最近更新 UTC 时间 */
    @TableField("password_updated_at")
    private LocalDateTime passwordUpdatedAt;

    /** ACTIVE / LOCKED / DELETING / DELETED */
    @TableField("status")
    private String status;

    /** 密码变更、全端退出时递增，令牌需核对 */
    @TableField("security_version")
    private Long securityVersion;

    /** OPEN / ERASING / ACCOUNT_DELETING */
    @TableField("data_write_fence")
    private String dataWriteFence;

    /** 注册成功时间 */
    @TableField("registered_at")
    private LocalDateTime registeredAt;

    /** 最近成功登录，低频异步汇总 */
    @TableField("last_login_at")
    private LocalDateTime lastLoginAt;

    /** 临时账号锁定截止 */
    @TableField("lock_until")
    private LocalDateTime lockUntil;

    /** 注销申请受理时间 */
    @TableField("delete_requested_at")
    private LocalDateTime deleteRequestedAt;

    /** 敏感标识清理完成时间 */
    @TableField("anonymized_at")
    private LocalDateTime anonymizedAt;

    /** 规范化邮箱的域分离 HMAC，用于登录和唯一去重，不存邮箱明文 */
    @TableField("email_lookup_hmac")
    private byte[] emailLookupHmac;

    /** 邮箱认证加密密文；注销后替换为不可恢复占位 */
    @TableField("email_cipher")
    private byte[] emailCipher;

    /** 邮箱密钥引用 */
    @TableField("email_key_ref")
    private String emailKeyRef;

    /** 首次验证码验证通过时间 */
    @TableField("email_verified_at")
    private LocalDateTime emailVerifiedAt;

    /** 注册时明确同意的服务条款/隐私说明组合版本 */
    @TableField("registration_policy_version")
    private String registrationPolicyVersion;

    /** 服务端确认接受时间，不能代替可撤回AI等独立授权 */
    @TableField("registration_policy_accepted_at")
    private LocalDateTime registrationPolicyAcceptedAt;
}
