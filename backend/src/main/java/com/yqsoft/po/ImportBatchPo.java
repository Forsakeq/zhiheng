package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 导入任务与校验摘要。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("import_batch")
public class ImportBatchPo {

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

    /** 导入 UUID */
    @TableField("batch_key")
    private String batchKey;

    /** EXTERNAL_RECORDS / COURSE_IMAGE / VOICE_RECORD */
    @TableField("kind")
    private String kind;

    /** 外部记录连接 */
    @TableField("source_connection_id")
    private Long sourceConnectionId;

    /** 图片/音频临时文件，同用途授权 */
    @TableField("source_file_id")
    private Long sourceFileId;

    /** QUEUED / RUNNING / AWAITING_CONFIRM / SUCCEEDED / PARTIAL_FAILED / FAILED / CANCELLED */
    @TableField("status")
    private String status;

    /** 排队时数据代际 */
    @TableField("captured_dataset_epoch")
    private Long capturedDatasetEpoch;

    /** 对应授权用途版本 */
    @TableField("captured_consent_version")
    private Long capturedConsentVersion;

    /** 外部连接捕获代际 */
    @TableField("authorization_revision")
    private Long authorizationRevision;

    /** 此次读取起点密文 */
    @TableField("input_cursor_cipher")
    private byte[] inputCursorCipher;

    /** 成功读取的终点，失败不冒进外部游标 */
    @TableField("result_cursor_cipher")
    private byte[] resultCursorCipher;

    /** 游标/候选内容密钥引用 */
    @TableField("encryption_key_ref")
    private String encryptionKeyRef;

    /** 候选课程/记录的加密 envelope，未确认不写事实表 */
    @TableField("candidate_payload_json")
    private String candidatePayloadJson;

    /** 解析候选数，未知 NULL */
    @TableField("total_count")
    private Integer totalCount;

    /** 真实成功条数 */
    @TableField("imported_count")
    private Integer importedCount;

    /** 外部稳定键去重数 */
    @TableField("duplicate_count")
    private Integer duplicateCount;

    /** 校验失败数 */
    @TableField("rejected_count")
    private Integer rejectedCount;

    /** 开始时间 */
    @TableField("started_at")
    private LocalDateTime startedAt;

    /** 终态时间 */
    @TableField("finished_at")
    private LocalDateTime finishedAt;

    /** 行号/字段码/错误码，不保敏感行原文 */
    @TableField("error_summary_json")
    private String errorSummaryJson;
}
