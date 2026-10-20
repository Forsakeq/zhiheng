package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务执行与调整事件。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("task_event")
public class TaskEventPo {

    /** 内部主键；API使用十进制字符串 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 服务端写入UTC创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 账号 */
    @TableField("user_id")
    private Long userId;

    /** 稳定任务 FK */
    @TableField("task_id")
    private Long taskId;

    /** CREATE / COMPLETE / SKIP / REOPEN / RESCHEDULE / EDIT / CANCEL */
    @TableField("event_type")
    private String eventType;

    /** 客户端业务操作 UUID */
    @TableField("operation_id")
    private String operationId;

    /** MOBILE / WATCH / AI_APPLY / SYSTEM */
    @TableField("source_type")
    private String sourceType;

    /** 动作前任务版本 */
    @TableField("before_row_version")
    private Long beforeRowVersion;

    /** 动作后任务版本 */
    @TableField("after_row_version")
    private Long afterRowVersion;

    /** 服务器动作确认 UTC */
    @TableField("occurred_at")
    private LocalDateTime occurredAt;

    /** 客户端声称发生时间，不能作同步序号 */
    @TableField("client_occurred_at")
    private LocalDateTime clientOccurredAt;

    /** 创建为空 */
    @TableField("old_status")
    private String oldStatus;

    /** 动作后状态 */
    @TableField("new_status")
    private String newStatus;

    /** 改时前本地日/UTC/时区/计划分钟最小快照 */
    @TableField("before_schedule_json")
    private String beforeScheduleJson;

    /** 改时后最小快照 */
    @TableField("after_schedule_json")
    private String afterScheduleJson;

    /** BUSY / NOT_READY / WEATHER / OTHER 等 */
    @TableField("reason_code")
    private String reasonCode;

    /** 用户原因密文 */
    @TableField("reason_cipher")
    private byte[] reasonCipher;

    /** 密钥引用 */
    @TableField("encryption_key_ref")
    private String encryptionKeyRef;

    /** 内容变更对应新封存版本 */
    @TableField("plan_version_id")
    private Long planVersionId;

    /** 触发时不可变配置快照 FK，COMPLETE/SKIP/RESCHEDULE/EDIT 必填 */
    @TableField("plan_version_task_id")
    private Long planVersionTaskId;
}
