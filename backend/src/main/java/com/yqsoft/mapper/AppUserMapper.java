package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.AppUserPo;

/** 账号主体基础持久化映射；复杂业务通过事务服务处理。 */
public interface AppUserMapper extends BaseMapper<AppUserPo> {}
