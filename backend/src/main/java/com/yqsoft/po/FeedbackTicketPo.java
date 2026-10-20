package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户反馈工单。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("feedback_ticket")
public class FeedbackTicketPo {

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

    /** 提交人 */
    @TableField("user_id")
    private Long userId;

    /** 公开工单编号 */
    @TableField("ticket_no")
    private String ticketNo;

    /** BUG / SUGGESTION / SYNC / PRIVACY / AI_QUALITY */
    @TableField("category")
    private String category;

    /** 提醒用户避免填敏感原文的标题 */
    @TableField("subject")
    private String subject;

    /** 问题描述密文 */
    @TableField("description_cipher")
    private byte[] descriptionCipher;

    /** 密钥引用 */
    @TableField("encryption_key_ref")
    private String encryptionKeyRef;

    /** OPEN / IN_PROGRESS / WAITING_USER / RESOLVED / CLOSED */
    @TableField("status")
    private String status;

    /** 经用户确认附带的安装信息 */
    @TableField("installation_id")
    private Long installationId;

    /** 脱敏 requestId，不自动附完整日志 */
    @TableField("diagnostic_reference")
    private String diagnosticReference;

    /** 处理人员账号，需工单权限 */
    @TableField("assigned_to_user_id")
    private Long assignedToUserId;

    /** 处理结果密文，不通过此表给医疗建议 */
    @TableField("resolution_cipher")
    private byte[] resolutionCipher;

    /** 解决时间 */
    @TableField("resolved_at")
    private LocalDateTime resolvedAt;

    /** 关闭时间 */
    @TableField("closed_at")
    private LocalDateTime closedAt;

    /** 最多3个同账号CLEAN/FEEDBACK_ATTACHMENT私有文件ID字符串，允许[] */
    @TableField("attachment_file_ids_json")
    private String attachmentFileIdsJson;

    /** 用户自愿补充的联系信息密文，不自动复制账号邮箱 */
    @TableField("contact_cipher")
    private byte[] contactCipher;

    /** 经用户确认的脱敏版本/requestId/错误码白名单，不接收整份日志 */
    @TableField("diagnostics_json")
    private String diagnosticsJson;

    /** 最近一次可见回复时间，与resolved_at不同 */
    @TableField("replied_at")
    private LocalDateTime repliedAt;

    /** 后台受限处理备注密文，不返回用户接口 */
    @TableField("internal_note_cipher")
    private byte[] internalNoteCipher;
}
