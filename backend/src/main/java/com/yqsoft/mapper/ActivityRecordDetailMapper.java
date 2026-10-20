package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.ActivityRecordDetailPo;

/** 实际活动记录详情基础持久化映射；复杂业务通过事务服务处理。 */
public interface ActivityRecordDetailMapper extends BaseMapper<ActivityRecordDetailPo> {}
