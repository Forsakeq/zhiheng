package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.AuthSessionPo;

/** 刷新令牌代际与会话链基础持久化映射；复杂业务通过事务服务处理。 */
public interface AuthSessionMapper extends BaseMapper<AuthSessionPo> {}
