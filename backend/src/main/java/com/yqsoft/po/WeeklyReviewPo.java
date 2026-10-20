package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每周回顾快照。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("weekly_review")
public class WeeklyReviewPo {

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

    /** 回顾起始业务日 */
    @TableField("week_start_date")
    private LocalDate weekStartDate;

    /** 起始后 6 日 */
    @TableField("week_end_date")
    private LocalDate weekEndDate;

    /** 生成时区 */
    @TableField("review_time_zone")
    private String reviewTimeZone;

    /** 该周回顾修订，旧修订保留 */
    @TableField("revision_no")
    private Integer revisionNo;

    /** GENERATING / READY / STALE / FAILED / ARCHIVED */
    @TableField("status")
    private String status;

    /** 统计口径版本 */
    @TableField("metric_version")
    private String metricVersion;

    /** 所基于个人数据序列 */
    @TableField("data_as_of_seq")
    private Long dataAsOfSeq;

    /** 数据代际 */
    @TableField("dataset_epoch")
    private Long datasetEpoch;

    /** 7 天各类型覆盖，未记录与零行为区分 */
    @TableField("coverage_json")
    private String coverageJson;

    /** 人工可读回顾文字密文 */
    @TableField("summary_cipher")
    private byte[] summaryCipher;

    /** 密钥引用 */
    @TableField("encryption_key_ref")
    private String encryptionKeyRef;

    /** 加密 envelope 的可选行为建议，不能自动应用目标 */
    @TableField("suggestions_json")
    private String suggestionsJson;

    /** AI 生成链路 */
    @TableField("ai_run_id")
    private Long aiRunId;

    /** 生成完成时间 */
    @TableField("generated_at")
    private LocalDateTime generatedAt;
}
