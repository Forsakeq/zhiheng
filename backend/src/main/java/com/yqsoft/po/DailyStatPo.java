package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 可重建每日统计。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("daily_stat")
public class DailyStatPo {

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

    /** 统计业务日 */
    @TableField("stat_local_date")
    private LocalDate statLocalDate;

    /** 采用的日历解释，历史记录按冻结业务日归集 */
    @TableField("stat_time_zone")
    private String statTimeZone;

    /** 如 DAILY_V1，口径变更必须升级 */
    @TableField("metric_version")
    private String metricVersion;

    /** 计算使用的已提交数据高水位 */
    @TableField("data_as_of_seq")
    private Long dataAsOfSeq;

    /** 本次统计对应的数据代际 */
    @TableField("dataset_epoch")
    private Long datasetEpoch;

    /** FRESH / STALE / BUILDING / FAILED */
    @TableField("status")
    private String status;

    /** 计算完成 UTC */
    @TableField("computed_at")
    private LocalDateTime computedAt;

    /** 有效原始记录数量，计数 0 是已观测无记录 */
    @TableField("valid_record_count")
    private Integer validRecordCount;

    /** 各类型 validCount/hasData/排除原因；缺测不转为测量值 0 */
    @TableField("coverage_json")
    private String coverageJson;

    /** 唯一明确主睡眠区间分钟；冲突时为空并标记 */
    @TableField("main_sleep_elapsed_minutes")
    private Integer mainSleepElapsedMinutes;

    /** 不重叠小睡区间并集分钟，有记录才有值 */
    @TableField("nap_elapsed_minutes")
    private Integer napElapsedMinutes;

    /** 有效实际活动区间口径；缺记录 NULL */
    @TableField("actual_activity_minutes")
    private Integer actualActivityMinutes;

    /** 餐次记录条数，不能推断吃了几顿 */
    @TableField("meal_record_count")
    private Integer mealRecordCount;

    /** 精力自评样本数 */
    @TableField("energy_sample_count")
    private Integer energySampleCount;

    /** 有效等级和，仅用于明确标注的自评分汇总 */
    @TableField("energy_level_sum")
    private Integer energyLevelSum;

    /** PENDING+COMPLETED+SKIPPED 的执行集合 */
    @TableField("task_denominator")
    private Integer taskDenominator;

    /** 已完成实例数 */
    @TableField("task_completed_count")
    private Integer taskCompletedCount;

    /** 跳过实例数，仍在分母 */
    @TableField("task_skipped_count")
    private Integer taskSkippedCount;

    /** 重叠、缺失、来源或时区不确定等白名单标记 */
    @TableField("quality_flags_json")
    private String qualityFlagsJson;

    /** 该业务日参与数据的原始时区去重列表，只解释来源，不重切业务日 */
    @TableField("source_time_zones_json")
    private String sourceTimeZonesJson;
}
