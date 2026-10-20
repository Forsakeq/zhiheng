package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息可核验资料引用。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("ai_message_citation")
public class AiMessageCitationPo {

    /** 内部主键；API使用十进制字符串 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 服务端写入UTC创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 消息所属账号 */
    @TableField("user_id")
    private Long userId;

    /** 助手消息 */
    @TableField("message_id")
    private Long messageId;

    /** 回答内从 1 起的引用编号 */
    @TableField("citation_no")
    private Integer citationNo;

    /** 当时发布的知识版本 */
    @TableField("article_version_id")
    private Long articleVersionId;

    /** 真实来源 */
    @TableField("knowledge_source_id")
    private Long knowledgeSourceId;

    /** 资料版本内段落锚点 */
    @TableField("section_anchor")
    private String sectionAnchor;

    /** 确需复核时保存的短论述密文，受引用长度限制 */
    @TableField("claim_excerpt_cipher")
    private byte[] claimExcerptCipher;

    /** 密钥引用 */
    @TableField("encryption_key_ref")
    private String encryptionKeyRef;

    /** 检索时间 */
    @TableField("retrieved_at")
    private LocalDateTime retrievedAt;

    /** KEYWORD / TAG / MANUAL；未建向量检索不填 VECTOR */
    @TableField("retrieval_method")
    private String retrievalMethod;
}
