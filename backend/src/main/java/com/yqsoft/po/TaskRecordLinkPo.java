package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务与实际记录关联。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("task_record_link")
public class TaskRecordLinkPo {

    /** 内部主键；API使用十进制字符串 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 服务端写入UTC创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 同账号 */
    @TableField("user_id")
    private Long userId;

    /** 任务 FK */
    @TableField("task_id")
    private Long taskId;

    /** 实际记录 FK */
    @TableField("record_id")
    private Long recordId;

    /** USER_CONFIRMED / AI_CONFIRMED */
    @TableField("link_type")
    private String linkType;

    /** 关联动作 UUID */
    @TableField("operation_id")
    private String operationId;

    /** 确认关联时间 */
    @TableField("linked_at")
    private LocalDateTime linkedAt;
}
