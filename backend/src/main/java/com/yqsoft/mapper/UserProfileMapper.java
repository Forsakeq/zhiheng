package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.UserProfilePo;

/** 个人档案基础持久化映射；复杂业务通过事务服务处理。 */
public interface UserProfileMapper extends BaseMapper<UserProfilePo> {}
