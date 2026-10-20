package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.DailyStatPo;

/** 可重建每日统计基础持久化映射；复杂业务通过事务服务处理。 */
public interface DailyStatMapper extends BaseMapper<DailyStatPo> {}
