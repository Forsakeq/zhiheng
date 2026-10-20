package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户生活目标。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("user_goal")
public class UserGoalPo {

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

    /** 目标字典 FK */
    @TableField("goal_definition_id")
    private Long goalDefinitionId;

    /** 该用户的目标名称快照 */
    @TableField("display_name")
    private String displayName;

    /** 按目标 schema 校验，不能保存自由医疗指令 */
    @TableField("parameters_json")
    private String parametersJson;

    /** 采用的字典解释版本 */
    @TableField("parameter_schema_version")
    private Integer parameterSchemaVersion;

    /** 1–5，值越大优先级越高 */
    @TableField("priority")
    private Integer priority;

    /** ACTIVE / PAUSED / COMPLETED / ARCHIVED */
    @TableField("status")
    private String status;

    /** 有效开始日 */
    @TableField("start_local_date")
    private LocalDate startLocalDate;

    /** 可选目标结束日 */
    @TableField("end_local_date")
    private LocalDate endLocalDate;

    /** 设置目标时区 */
    @TableField("goal_time_zone")
    private String goalTimeZone;

    /** 用户确认的可测行为口径，不是医疗效果 */
    @TableField("success_criterion_json")
    private String successCriterionJson;

    /** 可选动机备注密文 */
    @TableField("motivation_cipher")
    private byte[] motivationCipher;

    /** 密钥引用 */
    @TableField("encryption_key_ref")
    private String encryptionKeyRef;

    /** 用户确认目标完成时间 */
    @TableField("completed_at")
    private LocalDateTime completedAt;
}
