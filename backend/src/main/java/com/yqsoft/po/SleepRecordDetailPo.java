package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 睡眠记录详情。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("sleep_record_detail")
public class SleepRecordDetailPo {

    /** 父health_record主键；详情无独立版本或软删除列 */
    @TableId(value = "record_id", type = IdType.INPUT)
    private Long recordId;

    /** 服务端写入UTC创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 随主记录同事务更新UTC时间 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    /** 同主记录账号，用组合 FK 防跨账号 */
    @TableField("user_id")
    private Long userId;

    /** CHECK 固定 SLEEP，用于父类型 FK */
    @TableField("detail_type")
    private String detailType;

    /** 填写入睡 UTC 时间，等于主表 started_at */
    @TableField("sleep_start_at")
    private LocalDateTime sleepStartAt;

    /** 填写起床 UTC 时间，等于主表 ended_at */
    @TableField("wake_at")
    private LocalDateTime wakeAt;

    /** 两时间间隔分钟，不命名为深睡或实际总睡眠 */
    @TableField("elapsed_minutes")
    private Integer elapsedMinutes;

    /** 1 很疲惫 / 2 较疲惫 / 3 一般 / 4 较精神 / 5 很精神 */
    @TableField("rested_level")
    private Integer restedLevel;

    /** MAIN / NAP，用户确认 */
    @TableField("sleep_kind")
    private String sleepKind;

    /** SELF_REPORTED / VERIFIED_PROVIDER，后者 P2 核验后启用 */
    @TableField("measurement_method")
    private String measurementMethod;

    /** 用户自述中断，非自动推断次数 */
    @TableField("interruption_note_cipher")
    private byte[] interruptionNoteCipher;

    /** 附加备注密钥引用 */
    @TableField("encryption_key_ref")
    private String encryptionKeyRef;
}
