package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色权限关系。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("role_permission")
public class RolePermissionPo {

    /** 内部主键；API使用十进制字符串 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 服务端写入UTC创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 角色 FK */
    @TableField("role_id")
    private Long roleId;

    /** 权限 FK */
    @TableField("permission_id")
    private Long permissionId;

    /** 修改权限操作者；SQL落地修订：仅受控初始化种子允许NULL，真实后台授权仍必须真实操作者 */
    @TableField("granted_by_user_id")
    private Long grantedByUserId;

    /** 批次操作 UUID */
    @TableField("operation_id")
    private String operationId;
}
