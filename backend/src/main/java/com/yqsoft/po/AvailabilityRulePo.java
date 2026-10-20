package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每周时间安排规则。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("availability_rule")
public class AvailabilityRulePo {

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

    /** 课程、社团或休息等自定义名称 */
    @TableField("label")
    private String label;

    /** AVAILABLE / BUSY / RESERVED_REST */
    @TableField("rule_kind")
    private String ruleKind;

    /** 1–7，ISO 星期 */
    @TableField("weekday")
    private Integer weekday;

    /** 0–1439，本地当日开始分钟 */
    @TableField("start_minute")
    private Integer startMinute;

    /** 0–1439，本地结束分钟，配合 end_day_offset */
    @TableField("end_minute")
    private Integer endMinute;

    /** 规则的 IANA 时区 */
    @TableField("time_zone")
    private String timeZone;

    /** 本地有效开始日 */
    @TableField("valid_from")
    private LocalDate validFrom;

    /** 本地有效结束日 */
    @TableField("valid_until")
    private LocalDate validUntil;

    /** 同类覆盖优先级 */
    @TableField("priority")
    private Integer priority;

    /** 0 / 1 */
    @TableField("is_enabled")
    private Integer isEnabled;

    /** MANUAL / AI_CONFIRMED / COURSE_IMPORT */
    @TableField("source_type")
    private String sourceType;

    /** P2 课程导入批次 */
    @TableField("source_batch_id")
    private Long sourceBatchId;

    /** 0 当日 / 1 次日；允许跨午夜，不用end<start推断 */
    @TableField("end_day_offset")
    private Integer endDayOffset;
}
