package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.ReminderRulePo;

/** 提醒意愿与规则基础持久化映射；复杂业务通过事务服务处理。 */
public interface ReminderRuleMapper extends BaseMapper<ReminderRulePo> {}
