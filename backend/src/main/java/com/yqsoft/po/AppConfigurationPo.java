package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 可审计配置。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("app_configuration")
public class AppConfigurationPo {

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

    /** 稳定配置代码 */
    @TableField("config_key")
    private String configKey;

    /** DEV / TEST / PROD */
    @TableField("environment")
    private String environment;

    /** PUBLIC / SERVER，PUBLIC 才允许返回手机 */
    @TableField("scope")
    private String scope;

    /** BOOLEAN / NUMBER / STRING / OBJECT */
    @TableField("value_type")
    private String valueType;

    /** 按 schema 校验的值，禁止密钥/token/连接密码 */
    @TableField("value_json")
    private String valueJson;

    /** 配置范围和结构约束 */
    @TableField("schema_json")
    private String schemaJson;

    /** 对用户/运维的用途说明 */
    @TableField("description")
    private String description;

    /** 生效时点 */
    @TableField("effective_at")
    private LocalDateTime effectiveAt;

    /** 配置修改人 */
    @TableField("changed_by_user_id")
    private Long changedByUserId;

    /** 变更原因 */
    @TableField("change_reason")
    private String changeReason;

    /** 禁用后回退到受控缺省值 */
    @TableField("is_enabled")
    private Integer isEnabled;
}
