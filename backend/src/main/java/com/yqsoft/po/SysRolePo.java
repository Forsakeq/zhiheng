package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 后台角色。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("sys_role")
public class SysRolePo {

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

    /** CONTENT_EDITOR / CONTENT_REVIEWER / SUPPORT / SYSTEM_ADMIN 等 */
    @TableField("role_code")
    private String roleCode;

    /** 展示名称 */
    @TableField("name")
    private String name;

    /** 权限用途 */
    @TableField("description")
    private String description;

    /** ACTIVE / DISABLED */
    @TableField("status")
    private String status;

    /** 内建角色不能直接删名改义 */
    @TableField("is_builtin")
    private Integer isBuiltin;

    /** 后台排序 */
    @TableField("sort_order")
    private Integer sortOrder;

    /** 权限集合变化递增 */
    @TableField("permission_revision")
    private Long permissionRevision;
}
