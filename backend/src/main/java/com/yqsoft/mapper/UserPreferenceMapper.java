package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.UserPreferencePo;

/** 生活与交互偏好基础持久化映射；复杂业务通过事务服务处理。 */
public interface UserPreferenceMapper extends BaseMapper<UserPreferencePo> {}
