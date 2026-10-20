package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 跨 REST/离线命令幂等回执。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("sync_operation")
public class SyncOperationPo {

    /** 内部主键；API使用十进制字符串 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 服务端写入UTC创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 账号 */
    @TableField("user_id")
    private Long userId;

    /** REST Idempotency-Key 与离线 operationId 同键 */
    @TableField("client_operation_id")
    private String clientOperationId;

    /** CREATE_RECORD / APPLY_PLAN_DRAFT / COMPLETE_TASK 等动作码 */
    @TableField("operation_code")
    private String operationCode;

    /** 规范化 operationCode+业务 payload+epoch 的 HMAC */
    @TableField("request_hash")
    private byte[] requestHash;

    /** 该命令的数据代际 */
    @TableField("dataset_epoch")
    private Long datasetEpoch;

    /** PROCESSING / SUCCEEDED；业务回滚时行也回滚 */
    @TableField("status")
    private String status;

    /** 主要结果类型 */
    @TableField("resource_type")
    private String resourceType;

    /** 主要结果 ID */
    @TableField("resource_id")
    private Long resourceId;

    /** 结果版本 */
    @TableField("resource_row_version")
    private Long resourceRowVersion;

    /** 该操作最后分配 seq */
    @TableField("last_change_seq")
    private Long lastChangeSeq;

    /** 仅资源 ID/版本/seq/状态等 MutationResult 最小回执 */
    @TableField("result_json")
    private String resultJson;

    /** 同事务完成时间 */
    @TableField("completed_at")
    private LocalDateTime completedAt;

    /** 建议保留 120 天 */
    @TableField("expires_at")
    private LocalDateTime expiresAt;

    /** 命令发起安装 */
    @TableField("installation_id")
    private Long installationId;
}
