package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.HealthRecordPo;

/** 生活记录主表基础持久化映射；复杂业务通过事务服务处理。 */
public interface HealthRecordMapper extends BaseMapper<HealthRecordPo> {}
