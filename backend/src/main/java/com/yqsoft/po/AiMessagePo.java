package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话消息与操作卡片。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("ai_message")
public class AiMessagePo {

    /** 内部主键；API使用十进制字符串 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 服务端写入UTC创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 账号 */
    @TableField("user_id")
    private Long userId;

    /** 会话 FK */
    @TableField("conversation_id")
    private Long conversationId;

    /** 消息请求 UUID，去重 */
    @TableField("client_message_id")
    private String clientMessageId;

    /** USER / ASSISTANT / SYSTEM_NOTICE；SQL落地勘误：VARCHAR16可容纳SYSTEM_NOTICE（13字符） */
    @TableField("role")
    private String role;

    /** TEXT / CLARIFICATION / PLAN_CARD / RECORD_CARD / SOURCE_CARD / ERROR_NOTICE */
    @TableField("message_type")
    private String messageType;

    /** 正文密文，输入建议上限 6000 字 */
    @TableField("content_cipher")
    private byte[] contentCipher;

    /** 密钥引用 */
    @TableField("encryption_key_ref")
    private String encryptionKeyRef;

    /** ACCEPTED / GENERATING / READY / FAILED / CANCELLED */
    @TableField("delivery_status")
    private String deliveryStatus;

    /** 同会话上游消息 */
    @TableField("reply_to_message_id")
    private Long replyToMessageId;

    /** 该助手输出的运行记录 */
    @TableField("ai_run_id")
    private Long aiRunId;

    /** 操作卡片指向草稿 */
    @TableField("draft_id")
    private Long draftId;

    /** STOP / LENGTH / FILTERED / CANCELLED / PROVIDER_ERROR */
    @TableField("finish_reason")
    private String finishReason;

    /** 消息内容完整性校验，非跨用户文本去重 */
    @TableField("content_digest")
    private byte[] contentDigest;
}
