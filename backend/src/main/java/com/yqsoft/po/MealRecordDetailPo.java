package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 饮食习惯记录详情。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("meal_record_detail")
public class MealRecordDetailPo {

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

    /** CHECK 固定 MEAL */
    @TableField("detail_type")
    private String detailType;

    /** BREAKFAST / LUNCH / DINNER / SNACK / OTHER */
    @TableField("meal_type")
    private String mealType;

    /** 用户餐食描述密文，明文 1–2000 字 */
    @TableField("description_cipher")
    private byte[] descriptionCipher;

    /** 描述密钥引用 */
    @TableField("encryption_key_ref")
    private String encryptionKeyRef;

    /** 0 / 1 / 未记录 NULL */
    @TableField("vegetable_present")
    private Integer vegetablePresent;

    /** 0 / 1 / 未记录 NULL */
    @TableField("fruit_present")
    private Integer fruitPresent;

    /** 用户确认是否有蛋白质食物，非营养达标结论 */
    @TableField("protein_food_present")
    private Integer proteinFoodPresent;

    /** 0 / 1 / 未记录 NULL */
    @TableField("whole_grain_present")
    private Integer wholeGrainPresent;

    /** 1 未饱 / 2 较少 / 3 适中 / 4 较饱 / 5 很饱，主观 */
    @TableField("satiety_level")
    private Integer satietyLevel;

    /** CANTEEN / HOME / TAKEAWAY / RESTAURANT / OTHER */
    @TableField("meal_context")
    private String mealContext;
}
