package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.OutboxEventPo;

/** 事务事件待办基础持久化映射；复杂业务通过事务服务处理。 */
public interface OutboxEventMapper extends BaseMapper<OutboxEventPo> {}
