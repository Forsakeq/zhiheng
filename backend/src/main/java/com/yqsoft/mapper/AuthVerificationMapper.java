package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.AuthVerificationPo;

/** 一次性身份验证挑战基础持久化映射；复杂业务通过事务服务处理。 */
public interface AuthVerificationMapper extends BaseMapper<AuthVerificationPo> {}
