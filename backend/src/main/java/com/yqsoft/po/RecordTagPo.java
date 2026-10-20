package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户记录标签。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("record_tag")
public class RecordTagPo {

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

    /** 账号 */
    @TableField("user_id")
    private Long userId;

    /** 稳定标签 UUID，不使用显示名做外键 */
    @TableField("tag_code")
    private String tagCode;

    /** 1–30 字显示名密文 */
    @TableField("name_cipher")
    private byte[] nameCipher;

    /** 按用户域 HMAC 归一化标签名，用于去重 */
    @TableField("normalized_name_hash")
    private byte[] normalizedNameHash;

    /** 密钥引用 */
    @TableField("encryption_key_ref")
    private String encryptionKeyRef;

    /** 预定义颜色码，拒绝任意样式脚本 */
    @TableField("color_token")
    private String colorToken;

    /** 空表示各类型通用 */
    @TableField("applicable_record_type")
    private String applicableRecordType;

    /** 用户展示顺序 */
    @TableField("sort_order")
    private Integer sortOrder;

    /** 归档隐藏但保留历史关联 */
    @TableField("is_archived")
    private Integer isArchived;
}
