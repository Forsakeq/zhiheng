package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 稳定执行实例。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("task")
public class TaskPo {

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

    /** 所属计划 */
    @TableField("plan_id")
    private Long planId;

    /** 最近更新该实例配置的封存版本 */
    @TableField("current_plan_version_id")
    private Long currentPlanVersionId;

    /** P1 循环逻辑规则 */
    @TableField("rule_id")
    private Long ruleId;

    /** 循环规则内稳定发生键，不使用可变标题 */
    @TableField("occurrence_key")
    private String occurrenceKey;

    /** 任务名称 */
    @TableField("title")
    private String title;

    /** ROUTINE / ACTIVITY / MEAL_HABIT / RECORDING / CUSTOM */
    @TableField("task_type")
    private String taskType;

    /** 计划开始 UTC */
    @TableField("scheduled_start_at")
    private LocalDateTime scheduledStartAt;

    /** 计划结束 UTC */
    @TableField("scheduled_end_at")
    private LocalDateTime scheduledEndAt;

    /** 开始所在的已确认业务日 */
    @TableField("task_local_date")
    private LocalDate taskLocalDate;

    /** 冻结解释时区 */
    @TableField("task_time_zone")
    private String taskTimeZone;

    /** 计划时长 1–1440，不是实际活动量 */
    @TableField("planned_duration_minutes")
    private Integer plannedDurationMinutes;

    /** DRAFT / PENDING / COMPLETED / SKIPPED / CANCELLED */
    @TableField("status")
    private String status;

    /** 1–5 */
    @TableField("priority")
    private Integer priority;

    /** 固定时间任务调整须明确确认 */
    @TableField("is_fixed")
    private Integer isFixed;

    /** 可选执行提示密文，不存医学处方 */
    @TableField("instruction_cipher")
    private byte[] instructionCipher;

    /** 密钥引用 */
    @TableField("encryption_key_ref")
    private String encryptionKeyRef;

    /** 状态完成时确认时间 */
    @TableField("completed_at")
    private LocalDateTime completedAt;

    /** 从当前执行集合移除时间 */
    @TableField("cancelled_at")
    private LocalDateTime cancelledAt;

    /** MANUAL / AI_CONFIRMED / RULE_EXPANDED */
    @TableField("source_type")
    private String sourceType;
}
