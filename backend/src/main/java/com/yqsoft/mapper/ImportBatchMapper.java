package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.ImportBatchPo;

/** 导入任务与校验摘要基础持久化映射；复杂业务通过事务服务处理。 */
public interface ImportBatchMapper extends BaseMapper<ImportBatchPo> {}
