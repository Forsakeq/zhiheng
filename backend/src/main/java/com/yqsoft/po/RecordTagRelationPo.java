package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 记录与标签关系。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("record_tag_relation")
public class RecordTagRelationPo {

    /** 内部主键；API使用十进制字符串 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 服务端写入UTC创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 同记录与标签账号 */
    @TableField("user_id")
    private Long userId;

    /** 生活记录 FK */
    @TableField("record_id")
    private Long recordId;

    /** 标签 FK */
    @TableField("tag_id")
    private Long tagId;

    /** MANUAL / AI_CONFIRMED，用户确认后才关联 */
    @TableField("assigned_source")
    private String assignedSource;

    /** 此次关联操作 */
    @TableField("operation_id")
    private String operationId;
}
