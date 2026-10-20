package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 助手会话。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("ai_conversation")
public class AiConversationPo {

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

    /** 客户端稳定会话 UUID */
    @TableField("conversation_key")
    private String conversationKey;

    /** 可能包含健康隐私的会话标题密文 */
    @TableField("title_cipher")
    private byte[] titleCipher;

    /** 标题密钥引用 */
    @TableField("encryption_key_ref")
    private String encryptionKeyRef;

    /** ACTIVE / ARCHIVED */
    @TableField("status")
    private String status;

    /** 最小摘要策略代码 */
    @TableField("context_policy")
    private String contextPolicy;

    /** 最近消息时间 */
    @TableField("last_message_at")
    private LocalDateTime lastMessageAt;

    /** 会话内最后消息 */
    @TableField("last_message_id")
    private Long lastMessageId;

    /** 可重建计数，非计费依据 */
    @TableField("message_count")
    private Integer messageCount;

    /** 置顶时间 */
    @TableField("pinned_at")
    private LocalDateTime pinnedAt;

    /** 用户选定的会话内容保留截止 */
    @TableField("retention_until")
    private LocalDateTime retentionUntil;

    /** GENERAL / PLAN / RECORD / REVIEW，只选上下文策略不增加权限 */
    @TableField("scene")
    private String scene;
}
