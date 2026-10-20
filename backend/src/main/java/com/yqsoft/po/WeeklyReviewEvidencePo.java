package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 周报结论证据。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("weekly_review_evidence")
public class WeeklyReviewEvidencePo {

    /** 内部主键；API使用十进制字符串 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 服务端写入UTC创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 账号 */
    @TableField("user_id")
    private Long userId;

    /** 周报 FK */
    @TableField("review_id")
    private Long reviewId;

    /** 结论稳定编号，如 activity.total */
    @TableField("claim_key")
    private String claimKey;

    /** DAILY_STAT / RECORD / TASK_EVENT */
    @TableField("evidence_type")
    private String evidenceType;

    /** 统计证据 */
    @TableField("daily_stat_id")
    private Long dailyStatId;

    /** 原始生活记录证据 */
    @TableField("record_id")
    private Long recordId;

    /** 执行事件证据 */
    @TableField("task_event_id")
    private Long taskEventId;

    /** CM 证据的版本；CA 事件可空 */
    @TableField("evidence_row_version")
    private Long evidenceRowVersion;

    /** 读取证据时序列 */
    @TableField("evidence_as_of_seq")
    private Long evidenceAsOfSeq;

    /** 该结论所需的最小数字/日期快照，不含原始备注 */
    @TableField("value_snapshot_json")
    private String valueSnapshotJson;
}
