package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.AuditLogPo;

/** 最小安全审计基础持久化映射；复杂业务通过事务服务处理。 */
public interface AuditLogMapper extends BaseMapper<AuditLogPo> {}
