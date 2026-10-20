package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.ClientInstallationPo;

/** 客户端安装与同步租约基础持久化映射；复杂业务通过事务服务处理。 */
public interface ClientInstallationMapper extends BaseMapper<ClientInstallationPo> {}
