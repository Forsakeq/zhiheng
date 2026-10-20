package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 可应用 AI 草稿。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("ai_draft")
public class AiDraftPo {

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

    /** 草稿稳定 UUID */
    @TableField("draft_key")
    private String draftKey;

    /** PLAN_CREATE / PLAN_ADJUST / RECORD_EXTRACT */
    @TableField("kind")
    private String kind;

    /** NEEDS_INPUT / READY / APPLIED / EXPIRED / CANCELLED / FAILED */
    @TableField("status")
    private String status;

    /** 来源 AI 运行 */
    @TableField("source_run_id")
    private Long sourceRunId;

    /** 所属会话 */
    @TableField("conversation_id")
    private Long conversationId;

    /** 当前 revision 指针 */
    @TableField("current_revision_no")
    private Integer currentRevisionNo;

    /** 通过业务校验的修订号 */
    @TableField("validated_revision_no")
    private Integer validatedRevisionNo;

    /** 调整针对的计划 */
    @TableField("base_plan_id")
    private Long basePlanId;

    /** 生成时现行版本 */
    @TableField("base_plan_version_id")
    private Long basePlanVersionId;

    /** 草稿所依赖的数据代际 */
    @TableField("captured_dataset_epoch")
    private Long capturedDatasetEpoch;

    /** 草稿可应用截止 */
    @TableField("expires_at")
    private LocalDateTime expiresAt;

    /** 成功应用时间 */
    @TableField("applied_at")
    private LocalDateTime appliedAt;

    /** PLAN / PLAN_VERSION / HEALTH_RECORD */
    @TableField("applied_resource_type")
    private String appliedResourceType;

    /** 应用结果 ID，按 type 白名单校验 */
    @TableField("applied_resource_id")
    private Long appliedResourceId;

    /** 唯一应用操作号 */
    @TableField("applied_operation_id")
    private String appliedOperationId;
}
