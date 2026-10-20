package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.PlanVersionPo;

/** 计划版本与封存基础持久化映射；复杂业务通过事务服务处理。 */
public interface PlanVersionMapper extends BaseMapper<PlanVersionPo> {}
