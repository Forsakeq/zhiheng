package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 计划版本与封存。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("plan_version")
public class PlanVersionPo {

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

    /** 长期计划 FK */
    @TableField("plan_id")
    private Long planId;

    /** 计划内 1 起递增，与 row_version 不同 */
    @TableField("version_no")
    private Integer versionNo;

    /** DRAFT / SEALED / DISCARDED */
    @TableField("status")
    private String status;

    /** 创建时基础版本 */
    @TableField("based_on_version_id")
    private Long basedOnVersionId;

    /** 从历史复制时的来源版本 */
    @TableField("restore_from_version_id")
    private Long restoreFromVersionId;

    /** MANUAL / AI_CONFIRMED / RESTORE */
    @TableField("source_type")
    private String sourceType;

    /** 来源草稿 */
    @TableField("source_draft_id")
    private Long sourceDraftId;

    /** 本次变更开始影响的 UTC 时点 */
    @TableField("effective_from")
    private LocalDateTime effectiveFrom;

    /** 明确本次允许修改/移除的稳定任务 ID 字符串列表 */
    @TableField("affected_task_ids_json")
    private String affectedTaskIdsJson;

    /** 用户可读的变更摘要 */
    @TableField("change_summary")
    private String changeSummary;

    /** 最小目标版本、时间规则版本、生成参数快照，不含完整敏感备注 */
    @TableField("constraint_snapshot_json")
    private String constraintSnapshotJson;

    /** 冲突/越界/缺失校验结果及校验规则版本 */
    @TableField("validation_report_json")
    private String validationReportJson;

    /** 封存时间 */
    @TableField("sealed_at")
    private LocalDateTime sealedAt;

    /** 封存幂等操作 UUID */
    @TableField("sealed_by_operation_id")
    private String sealedByOperationId;

    /** 规范化快照内容摘要 */
    @TableField("content_digest")
    private byte[] contentDigest;
}
