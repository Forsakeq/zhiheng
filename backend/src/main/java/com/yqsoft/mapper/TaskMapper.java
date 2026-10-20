package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.TaskPo;

/** 稳定执行实例基础持久化映射；复杂业务通过事务服务处理。 */
public interface TaskMapper extends BaseMapper<TaskPo> {}
