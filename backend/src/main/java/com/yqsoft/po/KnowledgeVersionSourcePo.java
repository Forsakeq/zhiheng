package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识版本与来源关系。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("knowledge_version_source")
public class KnowledgeVersionSourcePo {

    /** 内部主键；API使用十进制字符串 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 服务端写入UTC创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 知识版本 */
    @TableField("article_version_id")
    private Long articleVersionId;

    /** 来源 */
    @TableField("knowledge_source_id")
    private Long knowledgeSourceId;

    /** 该版本内参考编号 */
    @TableField("reference_no")
    private Integer referenceNo;

    /** 该来源支撑的主题/段落，避免笼统背书 */
    @TableField("support_scope")
    private String supportScope;

    /** 原资料章节、页码或锚点 */
    @TableField("source_locator")
    private String sourceLocator;

    /** 关联人工核对时间 */
    @TableField("checked_at")
    private LocalDateTime checkedAt;
}
