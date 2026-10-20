package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.ReminderDeliveryPo;

/** 一次系统提醒调度/投递尝试基础持久化映射；复杂业务通过事务服务处理。 */
public interface ReminderDeliveryMapper extends BaseMapper<ReminderDeliveryPo> {}
