package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 生活与交互偏好。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("user_preference")
public class UserPreferencePo {

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

    /** 账号 FK */
    @TableField("user_id")
    private Long userId;

    /** 期望起床本地时间，时分精度 */
    @TableField("preferred_wake_time")
    private LocalTime preferredWakeTime;

    /** 期望就寝时间 */
    @TableField("preferred_bed_time")
    private LocalTime preferredBedTime;

    /** 活动类型白名单数组，允许空数组 */
    @TableField("preferred_activity_types_json")
    private String preferredActivityTypesJson;

    /** 不愿推荐的活动数组 */
    @TableField("excluded_activity_types_json")
    private String excludedActivityTypesJson;

    /** 建议 1–12，程序硬上限 12 */
    @TableField("daily_task_limit")
    private Integer dailyTaskLimit;

    /** 手工新任务建议时长，1–240 分钟 */
    @TableField("default_task_minutes")
    private Integer defaultTaskMinutes;

    /** 用户愿意单次投入时长，1–240 */
    @TableField("preferred_session_minutes")
    private Integer preferredSessionMinutes;

    /** NEUTRAL / ENCOURAGING / CONCISE */
    @TableField("plan_tone")
    private String planTone;

    /** SYSTEM / LIGHT / DARK */
    @TableField("theme_mode")
    private String themeMode;

    /** 0 / 1，减少动效 */
    @TableField("reduced_motion")
    private Integer reducedMotion;

    /** 0.80–2.00，前端仍须适配 */
    @TableField("font_scale")
    private BigDecimal fontScale;

    /** 是否启用免打扰 */
    @TableField("quiet_hours_enabled")
    private Integer quietHoursEnabled;

    /** 本地免打扰开始 */
    @TableField("quiet_start")
    private LocalTime quietStart;

    /** 本地免打扰结束，可跨午夜 */
    @TableField("quiet_end")
    private LocalTime quietEnd;

    /** JSON 与规则解释版本 */
    @TableField("preference_schema_version")
    private Integer preferenceSchemaVersion;
}
