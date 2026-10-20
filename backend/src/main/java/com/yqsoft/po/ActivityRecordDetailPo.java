package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 实际活动记录详情。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("activity_record_detail")
public class ActivityRecordDetailPo {

    /** 父health_record主键；详情无独立版本或软删除列 */
    @TableId(value = "record_id", type = IdType.INPUT)
    private Long recordId;

    /** 服务端写入UTC创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 随主记录同事务更新UTC时间 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    /** 同主记录账号 */
    @TableField("user_id")
    private Long userId;

    /** CHECK 固定 ACTIVITY */
    @TableField("detail_type")
    private String detailType;

    /** WALK / RUN / CYCLING / STRETCH / SPORT / OTHER 等白名单 */
    @TableField("activity_type")
    private String activityType;

    /** OTHER 必填，非临床运动处方 */
    @TableField("custom_activity_name")
    private String customActivityName;

    /** 实际活动 1–1440 分钟 */
    @TableField("actual_duration_minutes")
    private Integer actualDurationMinutes;

    /** SELF_REPORTED / VERIFIED_PROVIDER */
    @TableField("duration_method")
    private String durationMethod;

    /** EASY / MODERATE / HARD，用户自述，非诊断 */
    @TableField("perceived_intensity")
    private String perceivedIntensity;

    /** 用户明确填写或已核验来源，0–1000000 */
    @TableField("distance_meters")
    private BigDecimal distanceMeters;

    /** SELF_REPORTED / VERIFIED_PROVIDER */
    @TableField("distance_method")
    private String distanceMethod;

    /** 可选粗粒度场地名称；不采精确 GPS */
    @TableField("location_label_cipher")
    private byte[] locationLabelCipher;

    /** 场地密钥引用 */
    @TableField("encryption_key_ref")
    private String encryptionKeyRef;
}
