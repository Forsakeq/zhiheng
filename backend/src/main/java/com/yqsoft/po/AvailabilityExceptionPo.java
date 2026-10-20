package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 指定日期时间例外。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("availability_exception")
public class AvailabilityExceptionPo {

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

    /** 例外起始业务日 */
    @TableField("exception_date")
    private LocalDate exceptionDate;

    /** 解释所有 slot 的 IANA 时区 */
    @TableField("time_zone")
    private String timeZone;

    /** REPLACE_DAY / APPEND */
    @TableField("mode")
    private String mode;

    /**
     * 受 schema 校验的数组：slotKey、kind(BUSY/AVAILABLE/REST)、startTime/endTime、endDayOffset(0/1)、必要
     * offset
     */
    @TableField("slots_json")
    private String slotsJson;

    /** 区间协议版本 */
    @TableField("slots_schema_version")
    private Integer slotsSchemaVersion;

    /** 考试日、调课等名称 */
    @TableField("label")
    private String label;

    /** 可选原因密文 */
    @TableField("reason_cipher")
    private byte[] reasonCipher;

    /** 原因密钥引用 */
    @TableField("encryption_key_ref")
    private String encryptionKeyRef;

    /** 0 / 1 */
    @TableField("is_enabled")
    private Integer isEnabled;

    /** MANUAL / AI_CONFIRMED / COURSE_IMPORT */
    @TableField("source_type")
    private String sourceType;

    /** P2 来源导入批次 */
    @TableField("source_batch_id")
    private Long sourceBatchId;
}
