package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.TaskEventPo;

/** 任务执行与调整事件基础持久化映射；复杂业务通过事务服务处理。 */
public interface TaskEventMapper extends BaseMapper<TaskEventPo> {}
