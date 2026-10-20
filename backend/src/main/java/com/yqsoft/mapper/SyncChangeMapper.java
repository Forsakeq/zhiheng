package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.SyncChangePo;

/** 用户增量与删除墓碑基础持久化映射；复杂业务通过事务服务处理。 */
public interface SyncChangeMapper extends BaseMapper<SyncChangePo> {}
