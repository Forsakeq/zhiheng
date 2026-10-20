package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 科普知识长期条目。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("knowledge_article")
public class KnowledgeArticlePo {

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

    /** 稳定条目代码 */
    @TableField("article_code")
    private String articleCode;

    /** 公开路径标识，唯一不复用 */
    @TableField("slug")
    private String slug;

    /** ROUTINE / ACTIVITY / MEAL_HABIT / RECORDING / APP_HELP */
    @TableField("category")
    private String category;

    /** DRAFT / ACTIVE / RETIRED */
    @TableField("status")
    private String status;

    /** 当前已发布版本 */
    @TableField("active_version_id")
    private Long activeVersionId;

    /** 编辑版本号分配 */
    @TableField("latest_version_no")
    private Integer latestVersionNo;

    /** 公开检索标题，跟发布版本同步更新 */
    @TableField("search_title")
    private String searchTitle;

    /** 审核后的关键词，供关键词检索 */
    @TableField("search_keywords")
    private String searchKeywords;

    /** 固定受控标签数组 */
    @TableField("tag_codes_json")
    private String tagCodesJson;

    /** 列表顺序 */
    @TableField("sort_order")
    private Integer sortOrder;

    /** 停止默认推荐时间 */
    @TableField("retired_at")
    private LocalDateTime retiredAt;
}
