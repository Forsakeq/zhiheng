package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 知识审核与发布快照。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("knowledge_article_version")
public class KnowledgeArticleVersionPo {

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

    /** 长期条目 FK */
    @TableField("article_id")
    private Long articleId;

    /** 条目内递增版本 */
    @TableField("version_no")
    private Integer versionNo;

    /** DRAFT / IN_REVIEW / PUBLISHED / REJECTED / RETIRED */
    @TableField("status")
    private String status;

    /** 版本标题 */
    @TableField("title")
    private String title;

    /** 摘要 */
    @TableField("summary")
    private String summary;

    /** 安全渲染的审核正文；禁止危险 HTML */
    @TableField("body_markdown")
    private String bodyMarkdown;

    /** 例如成年普通大学生，不扩展为临床建议 */
    @TableField("applicable_population")
    private String applicablePopulation;

    /** 适用限制、资料不足、何时寻求专业意见 */
    @TableField("limitations")
    private String limitations;

    /** 人工核对来源的时间 */
    @TableField("source_checked_at")
    private LocalDateTime sourceCheckedAt;

    /** 下次检查日期 */
    @TableField("review_due_at")
    private LocalDateTime reviewDueAt;

    /** 内容编辑账号，不公开个人资料 */
    @TableField("author_user_id")
    private Long authorUserId;

    /** 审核账号，按权限记录 */
    @TableField("reviewer_user_id")
    private Long reviewerUserId;

    /** 审核意见，不包含用户健康信息 */
    @TableField("review_comment")
    private String reviewComment;

    /** 首次发布 */
    @TableField("published_at")
    private LocalDateTime publishedAt;

    /** 规范化内容摘要 */
    @TableField("content_digest")
    private byte[] contentDigest;

    /** 发布时受控标签数组快照，供检索投影；不是用户记录标签 */
    @TableField("tags_json")
    private String tagsJson;

    /** 该科普版本标明的内容日期，未知为空不等于来源发布日期 */
    @TableField("content_date")
    private LocalDate contentDate;

    /** 本版本整合/引用的权利和使用依据说明 */
    @TableField("rights_note")
    private String rightsNote;

    /** APPROVED / REJECTED；审核元数据，不新增APPROVED生命周期状态 */
    @TableField("review_decision")
    private String reviewDecision;
}
