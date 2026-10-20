package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 导出、删除预览与后台工单。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("privacy_request")
public class PrivacyRequestPo {

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

    /** 请求账号；完成注销保留最小内部主体键 */
    @TableField("user_id")
    private Long userId;

    /** 请求 UUID */
    @TableField("request_key")
    private String requestKey;

    /** EXPORT / DELETE_RECORDS / ERASE_HEALTH_DATA / DELETE_ACCOUNT */
    @TableField("request_type")
    private String requestType;

    /**
     * PREVIEW_READY / QUEUED / PROCESSING / COMPLETED / PARTIAL_FAILED / FAILED / CANCELLED /
     * EXPIRED
     */
    @TableField("status")
    private String status;

    /** 脱敏选择条件和模块白名单 */
    @TableField("scope_filter_json")
    private String scopeFilterJson;

    /** 冻结目标清单文件，导出也冻结范围 */
    @TableField("scope_manifest_file_id")
    private Long scopeManifestFileId;

    /** 规范化目标清单摘要，确认时核对 */
    @TableField("scope_digest")
    private byte[] scopeDigest;

    /** 按模块行数/附件数/影响概述，无原始内容 */
    @TableField("scope_summary_json")
    private String scopeSummaryJson;

    /** 预览数据代际 */
    @TableField("captured_dataset_epoch")
    private Long capturedDatasetEpoch;

    /** 预览时已提交高水位；全量擦除确认须相等 */
    @TableField("preview_data_seq")
    private Long previewDataSeq;

    /** 建议 15 分钟 */
    @TableField("preview_expires_at")
    private LocalDateTime previewExpiresAt;

    /** 明确确认时间 */
    @TableField("confirmed_at")
    private LocalDateTime confirmedAt;

    /** 确认动作幂等 UUID */
    @TableField("confirmation_operation_id")
    private String confirmationOperationId;

    /** 执行开始 */
    @TableField("started_at")
    private LocalDateTime startedAt;

    /** 完成时间 */
    @TableField("completed_at")
    private LocalDateTime completedAt;

    /** 分模块批次检查点/成功失败计数，不能靠显示百分比判成功 */
    @TableField("progress_json")
    private String progressJson;

    /** 导出文件，同用户用途 EXPORT */
    @TableField("result_file_id")
    private Long resultFileId;

    /** 注销后仅查询工单状态的高熵回执 token 哈希 */
    @TableField("receipt_token_hash")
    private byte[] receiptTokenHash;

    /** 回执失效时间 */
    @TableField("receipt_expires_at")
    private LocalDateTime receiptExpiresAt;

    /** 脱敏失败码 */
    @TableField("error_code")
    private String errorCode;
}
