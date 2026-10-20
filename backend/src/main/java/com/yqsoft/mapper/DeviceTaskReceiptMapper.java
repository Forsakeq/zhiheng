package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.DeviceTaskReceiptPo;

/** 手表任务动作回执基础持久化映射；复杂业务通过事务服务处理。 */
public interface DeviceTaskReceiptMapper extends BaseMapper<DeviceTaskReceiptPo> {}
