package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.TaskRecordLinkPo;

/** 任务与实际记录关联基础持久化映射；复杂业务通过事务服务处理。 */
public interface TaskRecordLinkMapper extends BaseMapper<TaskRecordLinkPo> {}
