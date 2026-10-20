package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.KnowledgeSourcePo;

/** 外部权威来源登记基础持久化映射；复杂业务通过事务服务处理。 */
public interface KnowledgeSourceMapper extends BaseMapper<KnowledgeSourcePo> {}
