package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 手表任务动作回执。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("device_task_receipt")
public class DeviceTaskReceiptPo {

    /** 内部主键；API使用十进制字符串 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 服务端写入UTC创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 设备绑定账号 */
    @TableField("user_id")
    private Long userId;

    /** WATCH 安装 FK */
    @TableField("installation_id")
    private Long installationId;

    /** 目标稳定任务 */
    @TableField("task_id")
    private Long taskId;

    /** 设备命令 UUID */
    @TableField("device_operation_id")
    private String deviceOperationId;

    /** COMPLETE / SKIP，首阶段仅白名单 */
    @TableField("action")
    private String action;

    /** 设备展示时任务版本 */
    @TableField("expected_task_row_version")
    private Long expectedTaskRowVersion;

    /** 设备拿到任务的数据代际 */
    @TableField("captured_dataset_epoch")
    private Long capturedDatasetEpoch;

    /** 设备凭据代际 */
    @TableField("credential_revision")
    private Long credentialRevision;

    /** 设备本地时钟换算，可信度有限 */
    @TableField("device_occurred_at")
    private LocalDateTime deviceOccurredAt;

    /** 服务器接收时刻 */
    @TableField("server_received_at")
    private LocalDateTime serverReceivedAt;

    /** ACCEPTED / REPLAYED / REJECTED */
    @TableField("status")
    private String status;

    /** VERSION_CONFLICT / CREDENTIAL_REVOKED / EPOCH_CHANGED 等 */
    @TableField("rejection_code")
    private String rejectionCode;

    /** 统一业务幂等服务接受的 UUID */
    @TableField("applied_operation_id")
    private String appliedOperationId;

    /** 成功后的任务版本 */
    @TableField("result_task_row_version")
    private Long resultTaskRowVersion;
}
