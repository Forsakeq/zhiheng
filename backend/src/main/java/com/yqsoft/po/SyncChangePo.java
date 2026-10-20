package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户增量与删除墓碑。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("sync_change")
public class SyncChangePo {

    /** 内部主键；API使用十进制字符串 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 服务端写入UTC创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 账号 */
    @TableField("user_id")
    private Long userId;

    /** 该用户严格按提交顺序分配 */
    @TableField("change_seq")
    private Long changeSeq;

    /** 所属数据代际 */
    @TableField("dataset_epoch")
    private Long datasetEpoch;

    /** 同步白名单实体类型 */
    @TableField("entity_type")
    private String entityType;

    /** 对象 ID；删除墓碑不做实体物理 FK */
    @TableField("entity_id")
    private Long entityId;

    /** 客户端比较版本，关系集合跟父版本 */
    @TableField("entity_row_version")
    private Long entityRowVersion;

    /** UPSERT / DELETE */
    @TableField("operation")
    private String operation;

    /** 服务器事务时间，非游标 */
    @TableField("changed_at")
    private LocalDateTime changedAt;

    /** 同步投影协议版本 */
    @TableField("payload_schema_version")
    private Integer payloadSchemaVersion;

    /** 最小加密投影或 DTO；DELETE 仅 ID/版本/epoch，不保原内容 */
    @TableField("payload_json")
    private String payloadJson;

    /** 关联业务命令 UUID */
    @TableField("operation_id")
    private String operationId;
}
