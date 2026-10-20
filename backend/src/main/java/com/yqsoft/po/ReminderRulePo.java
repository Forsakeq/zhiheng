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
 * 提醒意愿与规则。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("reminder_rule")
public class ReminderRulePo {

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

    /** 提醒规则稳定 UUID */
    @TableField("rule_key")
    private String ruleKey;

    /** 任务提醒指向实例；全局记录提醒为空 */
    @TableField("task_id")
    private Long taskId;

    /** TASK_START / RECORD_CHECKIN */
    @TableField("rule_type")
    private String ruleType;

    /** LOCAL_SYSTEM / IN_APP；按验证后的能力启用 */
    @TableField("channel")
    private String channel;

    /** 目标安装，空表示受支持的当前手机策略 */
    @TableField("target_installation_id")
    private Long targetInstallationId;

    /** OFFSET / DAILY_LOCAL */
    @TableField("trigger_kind")
    private String triggerKind;

    /** 任务开始前或准时分钟，-1440–0；首版不支持延后提醒 */
    @TableField("offset_minutes")
    private Integer offsetMinutes;

    /** 日常记录提醒本地时分 */
    @TableField("local_fire_time")
    private LocalTime localFireTime;

    /** 解释时间的 IANA 时区 */
    @TableField("time_zone")
    private String timeZone;

    /** 用户是否启用 */
    @TableField("is_enabled")
    private Integer isEnabled;

    /** 是否采用免打扰策略 */
    @TableField("respect_quiet_hours")
    private Integer respectQuietHours;

    /** SUPPRESS / NEXT_ALLOWED；须显式配置 */
    @TableField("quiet_policy")
    private String quietPolicy;

    /** 开始日期 */
    @TableField("valid_from")
    private LocalDate validFrom;

    /** 截止日期 */
    @TableField("valid_until")
    private LocalDate validUntil;
}
