package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 记录附件关系。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("record_attachment")
public class RecordAttachmentPo {

    /** 内部主键；API使用十进制字符串 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 服务端写入UTC创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 同账号 */
    @TableField("user_id")
    private Long userId;

    /** 记录 FK */
    @TableField("record_id")
    private Long recordId;

    /** file_object FK */
    @TableField("file_id")
    private Long fileId;

    /** PHOTO / NOTE_ATTACHMENT */
    @TableField("attachment_role")
    private String attachmentRole;

    /** 同记录内顺序 */
    @TableField("sort_order")
    private Integer sortOrder;

    /** 可选说明密文 */
    @TableField("caption_cipher")
    private byte[] captionCipher;

    /** 说明密钥引用 */
    @TableField("encryption_key_ref")
    private String encryptionKeyRef;

    /** 关联操作号 */
    @TableField("operation_id")
    private String operationId;
}
