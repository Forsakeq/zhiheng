package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.AppConfigurationPo;

/** 可审计配置基础持久化映射；复杂业务通过事务服务处理。 */
public interface AppConfigurationMapper extends BaseMapper<AppConfigurationPo> {}
