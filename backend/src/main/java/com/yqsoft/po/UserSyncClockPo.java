package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 每用户已提交同步时钟。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("user_sync_clock")
public class UserSyncClockPo {

    /** 账号 FK，注册时创建且健康擦除不删除 */
    @TableId(value = "user_id", type = IdType.INPUT)
    private Long userId;

    /** 已经提交的最大该用户变更序列 */
    @TableField("committed_seq")
    private Long committedSeq;

    /** 当前还能拉到的最小事件序号；无事件为 committed_seq+1 */
    @TableField("retained_from_seq")
    private Long retainedFromSeq;

    /** 全量健康擦除后递增，永不回退/重置 */
    @TableField("dataset_epoch")
    private Long datasetEpoch;

    /** 最近成功提交时间 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
