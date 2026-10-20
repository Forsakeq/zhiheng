package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.UserConsentEventPo;

/** 授权变更证据基础持久化映射；复杂业务通过事务服务处理。 */
public interface UserConsentEventMapper extends BaseMapper<UserConsentEventPo> {}
