package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.PlanVersionTaskPo;

/** 计划版本任务快照基础持久化映射；复杂业务通过事务服务处理。 */
public interface PlanVersionTaskMapper extends BaseMapper<PlanVersionTaskPo> {}
