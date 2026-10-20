package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 后台权限目录。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("sys_permission")
public class SysPermissionPo {

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

    /** knowledge:edit、knowledge:review、feedback:handle 等 */
    @TableField("permission_code")
    private String permissionCode;

    /** 权限说明 */
    @TableField("name")
    private String name;

    /** KNOWLEDGE / CONFIG / AUDIT / FEEDBACK / ROLE */
    @TableField("resource_group")
    private String resourceGroup;

    /** READ / CREATE / UPDATE / REVIEW / PUBLISH / ASSIGN */
    @TableField("action")
    private String action;

    /** 边界说明 */
    @TableField("description")
    private String description;

    /** 敏感管理动作二次鉴权/审计 */
    @TableField("is_sensitive")
    private Integer isSensitive;

    /** ACTIVE / DISABLED */
    @TableField("status")
    private String status;
}
