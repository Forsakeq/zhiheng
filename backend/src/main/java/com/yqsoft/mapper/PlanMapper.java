package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.PlanPo;

/** 计划长期主体基础持久化映射；复杂业务通过事务服务处理。 */
public interface PlanMapper extends BaseMapper<PlanPo> {}
