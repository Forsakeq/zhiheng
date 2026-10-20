package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 外部权威来源登记。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("knowledge_source")
public class KnowledgeSourcePo {

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

    /** 稳定资料编号 */
    @TableField("source_code")
    private String sourceCode;

    /** 文献或网页标题 */
    @TableField("title")
    private String title;

    /** 发布机构 */
    @TableField("publisher")
    private String publisher;

    /** 规范化 HTTPS 来源链接，不指向搜索结果页 */
    @TableField("canonical_url")
    private String canonicalUrl;

    /** 规范化 URL 摘要 */
    @TableField("url_hash")
    private byte[] urlHash;

    /**
     * OFFICIAL_WEB / GUIDELINE / PAPER /
     * APP_DOCUMENTATION；SQL落地勘误：VARCHAR24可容纳APP_DOCUMENTATION（17字符）
     */
    @TableField("source_kind")
    private String sourceKind;

    /** 原资料发布日期，未知为空 */
    @TableField("published_date")
    private LocalDate publishedDate;

    /** 最近访问核验时间 */
    @TableField("accessed_at")
    private LocalDateTime accessedAt;

    /** PENDING / VERIFIED / UNAVAILABLE / REJECTED */
    @TableField("verification_status")
    private String verificationStatus;

    /** LINK_ONLY / PERMITTED_EXCERPT / LICENSED / PUBLIC_DOMAIN，需记录依据 */
    @TableField("usage_basis")
    private String usageBasis;

    /** 授权/引用限制简述，非法律结论 */
    @TableField("license_note")
    private String licenseNote;

    /** 资料语言 */
    @TableField("source_language")
    private String sourceLanguage;

    /** 允许获取时保存版本摘要，用于变化提醒 */
    @TableField("content_fingerprint")
    private byte[] contentFingerprint;

    /** 脱敏访问错误，不直接解释为内容无效 */
    @TableField("last_check_error")
    private String lastCheckError;

    /** 人工核对结论/支持范围，不保存用户内容；与访问错误分开 */
    @TableField("verification_note")
    private String verificationNote;
}
