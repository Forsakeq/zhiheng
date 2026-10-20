package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.KnowledgeArticleVersionPo;

/** 知识审核与发布快照基础持久化映射；复杂业务通过事务服务处理。 */
public interface KnowledgeArticleVersionMapper extends BaseMapper<KnowledgeArticleVersionPo> {}
