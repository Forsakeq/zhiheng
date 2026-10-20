package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 应用内通知。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("notification_inbox")
public class NotificationInboxPo {

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

    /** 收件账号 */
    @TableField("user_id")
    private Long userId;

    /** 幂等通知 UUID */
    @TableField("notification_key")
    private String notificationKey;

    /** PLAN / SYNC / PRIVACY / REMINDER / REVIEW / SYSTEM */
    @TableField("category")
    private String category;

    /** 脱敏标题 */
    @TableField("title")
    private String title;

    /** 确需个性化正文时加密 */
    @TableField("body_cipher")
    private byte[] bodyCipher;

    /** 密钥引用 */
    @TableField("encryption_key_ref")
    private String encryptionKeyRef;

    /** 白名单 PLAN / TASK / PRIVACY_REQUEST 等 */
    @TableField("target_type")
    private String targetType;

    /** 目标 ID，服务验证归属与可见性 */
    @TableField("target_id")
    private Long targetId;

    /** LOW / NORMAL / HIGH；非医疗紧急程度 */
    @TableField("priority")
    private String priority;

    /** 首次查看时间 */
    @TableField("read_at")
    private LocalDateTime readAt;

    /** 不再展示截止 */
    @TableField("expires_at")
    private LocalDateTime expiresAt;

    /** 同一事件同一用户只生成一条 */
    @TableField("dedup_key")
    private String dedupKey;
}
