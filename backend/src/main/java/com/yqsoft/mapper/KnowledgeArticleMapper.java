package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.KnowledgeArticlePo;

/** 科普知识长期条目基础持久化映射；复杂业务通过事务服务处理。 */
public interface KnowledgeArticleMapper extends BaseMapper<KnowledgeArticlePo> {}
