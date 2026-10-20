package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 账号角色分配。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("user_role_assignment")
public class UserRoleAssignmentPo {

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

    /** 被授予账号 */
    @TableField("user_id")
    private Long userId;

    /** 角色 */
    @TableField("role_id")
    private Long roleId;

    /** ACTIVE / REVOKED */
    @TableField("status")
    private String status;

    /** 授权生效时间 */
    @TableField("valid_from")
    private LocalDateTime validFrom;

    /** 到期时间 */
    @TableField("valid_until")
    private LocalDateTime validUntil;

    /** 授权操作者 */
    @TableField("granted_by_user_id")
    private Long grantedByUserId;

    /** 授予原因 */
    @TableField("grant_reason")
    private String grantReason;

    /** 撤销操作者 */
    @TableField("revoked_by_user_id")
    private Long revokedByUserId;

    /** 撤销时间 */
    @TableField("revoked_at")
    private LocalDateTime revokedAt;
}
