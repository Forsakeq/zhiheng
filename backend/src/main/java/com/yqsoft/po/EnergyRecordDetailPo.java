package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 主观精力记录详情。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("energy_record_detail")
public class EnergyRecordDetailPo {

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

    /** CHECK 固定 ENERGY */
    @TableField("detail_type")
    private String detailType;

    /** 1 很低 / 2 较低 / 3 一般 / 4 较好 / 5 很好 */
    @TableField("energy_level")
    private Integer energyLevel;

    /** 锚点解释版本 */
    @TableField("scale_version")
    private Integer scaleVersion;

    /** WAKE / STUDY / ACTIVITY / EVENING / OTHER */
    @TableField("context_code")
    private String contextCode;

    /** 可选用户文字描述密文 */
    @TableField("description_cipher")
    private byte[] descriptionCipher;

    /** 密钥引用 */
    @TableField("encryption_key_ref")
    private String encryptionKeyRef;

    /** SELF_REPORT / AI_CONFIRMED；AI 只能提取用户明确描述 */
    @TableField("reported_method")
    private String reportedMethod;
}
