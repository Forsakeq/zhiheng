package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 运行与异步控制。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("ai_run")
public class AiRunPo {

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

    /** 一次用户意图运行 UUID */
    @TableField("run_key")
    private String runKey;

    /**
     * PLAN_GENERATE / PLAN_ADJUST / RECORD_EXTRACT / CHAT / WEEKLY_REVIEW / VOICE_TRANSCRIBE /
     * COURSE_IMPORT
     */
    @TableField("kind")
    private String kind;

    /** 会话 FK */
    @TableField("conversation_id")
    private Long conversationId;

    /** 输入消息 FK */
    @TableField("input_message_id")
    private Long inputMessageId;

    /** QUEUED / RUNNING / SUCCEEDED / FAILED / CANCEL_REQUESTED / CANCELLED */
    @TableField("status")
    private String status;

    /** 模型提供方适配代码 */
    @TableField("provider_code")
    private String providerCode;

    /** 实际调用模型名，不存 API 密钥 */
    @TableField("model_name")
    private String modelName;

    /** 系统提示模板版本 */
    @TableField("prompt_template_version")
    private String promptTemplateVersion;

    /** 确需复核的最小输入快照密文，支持短保留 */
    @TableField("input_snapshot_cipher")
    private byte[] inputSnapshotCipher;

    /** 密钥引用 */
    @TableField("encryption_key_ref")
    private String encryptionKeyRef;

    /** 排队时健康数据代际 */
    @TableField("captured_dataset_epoch")
    private Long capturedDatasetEpoch;

    /** AI_PROCESSING 授权版本 */
    @TableField("captured_consent_version")
    private Long capturedConsentVersion;

    /** 运行尝试次数，设置硬上限 */
    @TableField("attempt_count")
    private Integer attemptCount;

    /** 开始时间 */
    @TableField("started_at")
    private LocalDateTime startedAt;

    /** 终态时间 */
    @TableField("finished_at")
    private LocalDateTime finishedAt;

    /** 超时截止 */
    @TableField("timeout_at")
    private LocalDateTime timeoutAt;

    /** 提供方返回的计数，未知 NULL */
    @TableField("input_tokens")
    private Integer inputTokens;

    /** 提供方计数 */
    @TableField("output_tokens")
    private Integer outputTokens;

    /** 脱敏结构化失败码，不存含提示词的整段异常 */
    @TableField("error_code")
    private String errorCode;

    /** 排障追踪号，按期限清理 */
    @TableField("provider_request_id")
    private String providerRequestId;
}
