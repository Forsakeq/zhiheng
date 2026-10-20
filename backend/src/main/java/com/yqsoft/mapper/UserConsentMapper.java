package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.UserConsentPo;

/** 按用途的当前授权基础持久化映射；复杂业务通过事务服务处理。 */
public interface UserConsentMapper extends BaseMapper<UserConsentPo> {}
