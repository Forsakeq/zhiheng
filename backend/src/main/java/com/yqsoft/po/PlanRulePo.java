package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 循环任务逻辑规则。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("plan_rule")
public class PlanRulePo {

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

    /** 所属长期计划 */
    @TableField("plan_id")
    private Long planId;

    /** 规则稳定 UUID */
    @TableField("rule_key")
    private String ruleKey;

    /** 模板标题 */
    @TableField("title")
    private String title;

    /** 模板类型 */
    @TableField("task_type")
    private String taskType;

    /** DAILY / WEEKLY；首版不支持任意 RRULE */
    @TableField("recurrence_kind")
    private String recurrenceKind;

    /** WEEKLY 时 1–7 唯一数组 */
    @TableField("weekdays_json")
    private String weekdaysJson;

    /** 当天本地时分 */
    @TableField("start_local_time")
    private LocalTime startLocalTime;

    /** 1–1440 */
    @TableField("planned_duration_minutes")
    private Integer plannedDurationMinutes;

    /** 规则时区 */
    @TableField("time_zone")
    private String timeZone;

    /** 起始业务日 */
    @TableField("valid_from")
    private LocalDate validFrom;

    /** 截止业务日 */
    @TableField("valid_until")
    private LocalDate validUntil;

    /** ACTIVE / PAUSED / ARCHIVED */
    @TableField("status")
    private String status;

    /** 已确认展开到的本地日 */
    @TableField("last_expanded_through")
    private LocalDate lastExpandedThrough;

    /** 模板修订，不回改过去实例 */
    @TableField("template_revision")
    private Integer templateRevision;

    /** 结束本地时分，结合end_day_offset形成可用窗 */
    @TableField("end_local_time")
    private LocalTime endLocalTime;

    /** 0 当日 / 1 次日 */
    @TableField("end_day_offset")
    private Integer endDayOffset;

    /** 展开任务的固定时间意愿 */
    @TableField("is_fixed")
    private Integer isFixed;

    /** 模板说明密文，展开后冻结入快照 */
    @TableField("instruction_cipher")
    private byte[] instructionCipher;

    /** 说明密钥引用 */
    @TableField("encryption_key_ref")
    private String encryptionKeyRef;

    /** 模板提醒参数，无平台凭据 */
    @TableField("reminder_snapshot_json")
    private String reminderSnapshotJson;
}
