package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 计划版本任务快照。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("plan_version_task")
public class PlanVersionTaskPo {

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

    /** 版本 FK */
    @TableField("version_id")
    private Long versionId;

    /** 新增草稿暂空，SEALED 前必须分配稳定实例 */
    @TableField("task_id")
    private Long taskId;

    /** 版本内稳定草稿 UUID */
    @TableField("draft_task_key")
    private String draftTaskKey;

    /** 版本展示顺序 */
    @TableField("sort_order")
    private Integer sortOrder;

    /** 当时任务名快照 */
    @TableField("title")
    private String title;

    /** 当时类型 */
    @TableField("task_type")
    private String taskType;

    /** 当时计划开始 UTC */
    @TableField("scheduled_start_at")
    private LocalDateTime scheduledStartAt;

    /** 当时计划结束 UTC */
    @TableField("scheduled_end_at")
    private LocalDateTime scheduledEndAt;

    /** 当时计划分钟 */
    @TableField("planned_duration_minutes")
    private Integer plannedDurationMinutes;

    /** 当时 IANA 时区 */
    @TableField("task_time_zone")
    private String taskTimeZone;

    /** 当时业务日 */
    @TableField("task_local_date")
    private LocalDate taskLocalDate;

    /** 当时固定时间标记 */
    @TableField("is_fixed")
    private Integer isFixed;

    /** 当时优先级 */
    @TableField("priority")
    private Integer priority;

    /** 当时提示密文 */
    @TableField("instruction_cipher")
    private byte[] instructionCipher;

    /** 密钥引用 */
    @TableField("encryption_key_ref")
    private String encryptionKeyRef;

    /** 提醒意愿参数快照，无 OS 凭据 */
    @TableField("reminder_snapshot_json")
    private String reminderSnapshotJson;

    /** ADD / KEEP / UPDATE / REMOVE */
    @TableField("change_action")
    private String changeAction;

    /** 当时逻辑循环模板ID快照；手工单次为空 */
    @TableField("rule_id")
    private Long ruleId;

    /** 当时规则内发生键，与rule_id同空同有 */
    @TableField("occurrence_key")
    private String occurrenceKey;

    /** 服务端从version读取的计划ID，不接受客户端可写；用于防同账号跨计划拼接 */
    @TableField("plan_id")
    private Long planId;
}
