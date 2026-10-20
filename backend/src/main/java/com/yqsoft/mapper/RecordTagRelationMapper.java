package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.RecordTagRelationPo;

/** 记录与标签关系基础持久化映射；复杂业务通过事务服务处理。 */
public interface RecordTagRelationMapper extends BaseMapper<RecordTagRelationPo> {}
