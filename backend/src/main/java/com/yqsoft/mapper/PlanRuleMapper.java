package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.PlanRulePo;

/** 循环任务逻辑规则基础持久化映射；复杂业务通过事务服务处理。 */
public interface PlanRuleMapper extends BaseMapper<PlanRulePo> {}
