package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 目标字典。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("goal_definition")
public class GoalDefinitionPo {

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

    /** REGULAR_SLEEP / DAILY_ACTIVITY / LIFE_RECORD / CUSTOM 等稳定码 */
    @TableField("code")
    private String code;

    /** 目标名称 */
    @TableField("name")
    private String name;

    /** 用户可理解的目标解释 */
    @TableField("description")
    private String description;

    /** ROUTINE / ACTIVITY / RECORDING / CUSTOM */
    @TableField("goal_category")
    private String goalCategory;

    /** 允许的目标字段、单位、范围和是否可空 */
    @TableField("parameter_schema_json")
    private String parameterSchemaJson;

    /** 非医疗性质的可编辑示例参数 */
    @TableField("suggested_parameters_json")
    private String suggestedParametersJson;

    /** 前端图标白名单 */
    @TableField("icon_code")
    private String iconCode;

    /** 展示排序 */
    @TableField("sort_order")
    private Integer sortOrder;

    /** ACTIVE / DISABLED */
    @TableField("status")
    private String status;

    /** 参数解释版本 */
    @TableField("schema_version")
    private Integer schemaVersion;
}
