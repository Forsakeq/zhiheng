package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 计划长期主体。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("plan")
public class PlanPo {

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

    /** 客户端稳定计划 UUID */
    @TableField("plan_key")
    private String planKey;

    /** 计划名称 */
    @TableField("name")
    private String name;

    /** DAY / WEEK / CUSTOM */
    @TableField("plan_kind")
    private String planKind;

    /** DRAFT / ACTIVE / PAUSED / ARCHIVED */
    @TableField("status")
    private String status;

    /** 计划显示起始本地日 */
    @TableField("range_start_date")
    private LocalDate rangeStartDate;

    /** 结束本地日，建议单次不超过 31 日 */
    @TableField("range_end_date")
    private LocalDate rangeEndDate;

    /** 计划解释时区 */
    @TableField("plan_time_zone")
    private String planTimeZone;

    /** 本计划当前 SEALED 版本，精确归属外键 */
    @TableField("active_version_id")
    private Long activeVersionId;

    /** 在锁 plan 后递增，仅分配版本号 */
    @TableField("latest_version_no")
    private Integer latestVersionNo;

    /** MANUAL / AI_CONFIRMED / RESTORE */
    @TableField("source_type")
    private String sourceType;

    /** 初始 AI 草稿 */
    @TableField("origin_draft_id")
    private Long originDraftId;

    /** 暂停时间 */
    @TableField("paused_at")
    private LocalDateTime pausedAt;

    /** 归档时间 */
    @TableField("archived_at")
    private LocalDateTime archivedAt;
}
