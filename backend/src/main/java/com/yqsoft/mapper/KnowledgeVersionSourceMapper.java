package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.KnowledgeVersionSourcePo;

/** 知识版本与来源关系基础持久化映射；复杂业务通过事务服务处理。 */
public interface KnowledgeVersionSourceMapper extends BaseMapper<KnowledgeVersionSourcePo> {}
