package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.UserGoalPo;

/** 用户生活目标基础持久化映射；复杂业务通过事务服务处理。 */
public interface UserGoalMapper extends BaseMapper<UserGoalPo> {}
