package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.AuthIdentityPo;

/** 第三方身份绑定基础持久化映射；复杂业务通过事务服务处理。 */
public interface AuthIdentityMapper extends BaseMapper<AuthIdentityPo> {}
