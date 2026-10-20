package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 第三方身份绑定。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("auth_identity")
public class AuthIdentityPo {

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

    /** 绑定账号 */
    @TableField("user_id")
    private Long userId;

    /** 启用的身份提供方代码 */
    @TableField("provider")
    private String provider;

    /** 应用或租户命名空间 */
    @TableField("provider_tenant")
    private String providerTenant;

    /** provider subject 的域分离 HMAC */
    @TableField("subject_hash")
    private byte[] subjectHash;

    /** 确有重新绑定需要时保存的 subject 密文 */
    @TableField("subject_cipher")
    private byte[] subjectCipher;

    /** 提供方跨应用标识 HMAC，不盲目自动合并账号 */
    @TableField("union_subject_hash")
    private byte[] unionSubjectHash;

    /** ACTIVE / REVOKED */
    @TableField("binding_status")
    private String bindingStatus;

    /** 首次完成绑定时间 */
    @TableField("bound_at")
    private LocalDateTime boundAt;

    /** 最近该方式登录 */
    @TableField("last_authenticated_at")
    private LocalDateTime lastAuthenticatedAt;

    /** 解除绑定时间 */
    @TableField("revoked_at")
    private LocalDateTime revokedAt;

    /** 绑定凭据代际 */
    @TableField("authorization_revision")
    private Long authorizationRevision;

    /** 密钥引用 */
    @TableField("encryption_key_ref")
    private String encryptionKeyRef;
}
